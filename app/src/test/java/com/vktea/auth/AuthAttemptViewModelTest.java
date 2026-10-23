package com.vktea.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;

import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStore;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Exercises real worker ordering with synthetic requests and a manually drained main looper. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class AuthAttemptViewModelTest {
    private final List<ViewModelStore> stores = new ArrayList<>();
    private final List<AuthAttemptViewModel> models = new ArrayList<>();
    private final List<CountDownLatch> releaseLatches = new ArrayList<>();

    @After
    public void finishSyntheticWorkersAndClearOwners() throws Exception {
        for (CountDownLatch latch : releaseLatches) {
            latch.countDown();
        }
        for (AuthAttemptViewModel model : models) {
            await(() -> !model.isBusy());
        }
        for (ViewModelStore store : stores) {
            store.clear();
        }
    }

    @Test
    public void duplicateSubmitExecutesOnlyTheOriginalRequestOffTheMainThread() throws Exception {
        AuthAttemptViewModel model = newModel();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = newReleaseLatch();
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean workerWasMain = new AtomicBoolean(true);
        AuthResponse result = AuthResponse.password(200, new JSONObject());

        assertTrue(model.submit(AuthResponse.Method.PASSWORD, () -> {
            calls.incrementAndGet();
            workerWasMain.set(Looper.myLooper() == Looper.getMainLooper());
            entered.countDown();
            waitForRelease(release);
            return result;
        }));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertTrue(model.isBusy());
        assertEquals(Boolean.TRUE, model.getBusy().getValue());
        assertFalse(model.submit(AuthResponse.Method.TOKEN, () -> {
            calls.incrementAndGet();
            return AuthResponse.failure(AuthResponse.Method.TOKEN);
        }));

        release.countDown();
        awaitResponse(model);

        assertEquals(1, calls.get());
        assertFalse(workerWasMain.get());
        assertSame(result, model.takeResponse());
        assertFalse(model.isBusy());
    }

    @Test
    public void responseObserverCanConsumeAndSubmitTheNextExplicitAttempt() throws Exception {
        AuthAttemptViewModel model = newModel();
        List<AuthResponse> consumed = new ArrayList<>();
        AtomicBoolean nextAccepted = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        Observer<AuthResponse> observer = result -> {
            if (result == null) {
                return;
            }
            assertFalse(model.isBusy());
            assertEquals(Boolean.FALSE, model.getBusy().getValue());
            consumed.add(model.takeResponse());
            if (consumed.size() == 1) {
                nextAccepted.set(model.submit(AuthResponse.Method.VK_ID, () -> {
                    calls.incrementAndGet();
                    return AuthResponse.vkId(200, new JSONObject());
                }));
            }
        };
        model.getResponse().observeForever(observer);
        try {
            assertTrue(model.submit(AuthResponse.Method.PASSWORD, () -> {
                calls.incrementAndGet();
                return AuthResponse.password(400, new JSONObject());
            }));
            await(() -> consumed.size() == 2);

            assertTrue(nextAccepted.get());
            assertEquals(2, calls.get());
            assertEquals(AuthResponse.Method.PASSWORD, consumed.get(0).method);
            assertEquals(AuthResponse.Method.VK_ID, consumed.get(1).method);
            assertNull(consumed.get(1).suppliedToken);
            assertNull(consumed.get(1).suppliedSecret);
            assertNull(model.takeResponse());
        } finally {
            model.getResponse().removeObserver(observer);
        }
    }

    @Test
    public void processGateRejectsAnotherOwnerUntilTheCurrentWorkerEnds() throws Exception {
        AuthAttemptViewModel first = newModel();
        AuthAttemptViewModel second = newModel();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = newReleaseLatch();
        AtomicInteger secondCalls = new AtomicInteger();
        AuthAttemptViewModel.Request next = () -> {
            secondCalls.incrementAndGet();
            return AuthResponse.token(200, new JSONObject(), "synthetic-token", null);
        };
        assertTrue(first.submit(AuthResponse.Method.PASSWORD, blocking(entered, release,
                AuthResponse.password(200, new JSONObject()))));
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        assertFalse(second.submit(AuthResponse.Method.TOKEN, next));
        assertFalse(second.isBusy());
        assertEquals(0, secondCalls.get());

        release.countDown();
        awaitResponse(first);
        assertTrue(second.submit(AuthResponse.Method.TOKEN, next));
        awaitResponse(second);
        assertEquals(1, secondCalls.get());
    }

    @Test
    public void requestExceptionProducesSanitizedFailureAndReleasesProcessGate() throws Exception {
        AuthAttemptViewModel failed = newModel();
        assertTrue(failed.submit(AuthResponse.Method.TOKEN, () -> {
            throw new IOException("Synthetic private transport detail");
        }));
        awaitResponse(failed);

        AuthResponse result = failed.takeResponse();
        assertEquals(AuthResponse.Method.TOKEN, result.method);
        assertTrue(result.networkFailure);
        assertEquals(0, result.httpStatus);
        assertNull(result.json);
        assertNull(result.suppliedToken);
        assertNull(result.suppliedSecret);
        assertFalse(failed.isBusy());

        AuthAttemptViewModel next = newModel();
        assertTrue(next.submit(AuthResponse.Method.PASSWORD,
                () -> AuthResponse.password(200, new JSONObject())));
        awaitResponse(next);
    }

    @Test
    public void consumedResponseIsNotReplayedToAReplacementObserver() throws Exception {
        AuthAttemptViewModel model = newModel();
        AuthResponse expected = AuthResponse.token(200, new JSONObject(),
                "synthetic-one-shot-token", "synthetic-one-shot-secret");
        assertTrue(model.submit(AuthResponse.Method.TOKEN, () -> expected));
        awaitResponse(model);

        assertSame(expected, model.takeResponse());
        assertNull(model.takeResponse());
        AtomicInteger nonNullResults = new AtomicInteger();
        Observer<AuthResponse> replacement = result -> {
            if (result != null) {
                nonNullResults.incrementAndGet();
            }
        };
        model.getResponse().observeForever(replacement);
        try {
            assertEquals(0, nonNullResults.get());
            assertNull(model.getResponse().getValue());
        } finally {
            model.getResponse().removeObserver(replacement);
        }
    }

    @Test
    public void retainedStoreKeepsTheAttemptAndItsUnconsumedResultAcrossNewOwners() throws Exception {
        ViewModelStore retainedStore = newStore();
        AuthAttemptViewModel original = get(retainedStore);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = newReleaseLatch();
        AuthResponse result = AuthResponse.password(200, new JSONObject());
        assertTrue(original.submit(AuthResponse.Method.PASSWORD, blocking(entered, release, result)));
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        AuthAttemptViewModel afterRotation = get(retainedStore);
        assertSame(original, afterRotation);
        assertTrue(afterRotation.isBusy());
        release.countDown();
        awaitResponse(afterRotation);

        AuthAttemptViewModel nextOwner = get(retainedStore);
        assertSame(result, nextOwner.takeResponse());
        assertNull(get(retainedStore).getResponse().getValue());
    }

    @Test
    public void clearingOwnerDropsLateResultWithoutReleasingAnExecutingRequestEarly()
            throws Exception {
        ViewModelStore originalStore = newStore();
        AuthAttemptViewModel original = get(originalStore);
        AuthAttemptViewModel replacement = newModel();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = newReleaseLatch();
        AuthResponse pending = AuthResponse.token(200, new JSONObject(),
                "synthetic-late-token", "synthetic-late-secret");
        assertTrue(original.submit(AuthResponse.Method.TOKEN, blocking(entered, release, pending)));
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        originalStore.clear();
        assertFalse(original.submit(AuthResponse.Method.TOKEN, () -> pending));
        assertFalse(replacement.submit(AuthResponse.Method.PASSWORD,
                () -> AuthResponse.password(200, new JSONObject())));
        release.countDown();
        await(() -> !original.isBusy());

        assertNull(original.getResponse().getValue());
        assertNull(original.takeResponse());
        assertTrue(replacement.submit(AuthResponse.Method.PASSWORD,
                () -> AuthResponse.password(200, new JSONObject())));
        awaitResponse(replacement);
    }

    @Test
    public void clearingOwnerErasesItsUnconsumedCredentialResult() throws Exception {
        ViewModelStore store = newStore();
        AuthAttemptViewModel model = get(store);
        assertTrue(model.submit(AuthResponse.Method.TOKEN,
                () -> AuthResponse.token(200, new JSONObject(),
                        "synthetic-cleared-token", "synthetic-cleared-secret")));
        awaitResponse(model);

        store.clear();

        assertNull(model.getResponse().getValue());
        assertNull(model.takeResponse());
        assertFalse(model.submit(AuthResponse.Method.PASSWORD,
                () -> AuthResponse.password(200, new JSONObject())));
    }

    @Test
    public void clearingFromBusyObserverDiscardsTheCompletingResult() throws Exception {
        ViewModelStore store = newStore();
        AuthAttemptViewModel model = get(store);
        AtomicBoolean started = new AtomicBoolean();
        AtomicBoolean ownerCleared = new AtomicBoolean();
        Observer<Boolean> observer = value -> {
            if (Boolean.TRUE.equals(value)) {
                started.set(true);
            } else if (started.get()) {
                store.clear();
                ownerCleared.set(true);
            }
        };
        model.getBusy().observeForever(observer);
        try {
            assertTrue(model.submit(AuthResponse.Method.PASSWORD,
                    () -> AuthResponse.password(200, new JSONObject())));
            await(ownerCleared::get);
            assertNull(model.getResponse().getValue());
        } finally {
            model.getBusy().removeObserver(observer);
        }
    }

    private AuthAttemptViewModel newModel() {
        return get(newStore());
    }

    private ViewModelStore newStore() {
        ViewModelStore store = new ViewModelStore();
        stores.add(store);
        return store;
    }

    private AuthAttemptViewModel get(ViewModelStore store) {
        AuthAttemptViewModel model = new ViewModelProvider(store,
                new ViewModelProvider.NewInstanceFactory()).get(AuthAttemptViewModel.class);
        if (!models.contains(model)) {
            models.add(model);
        }
        return model;
    }

    private CountDownLatch newReleaseLatch() {
        CountDownLatch latch = new CountDownLatch(1);
        releaseLatches.add(latch);
        return latch;
    }

    private static AuthAttemptViewModel.Request blocking(CountDownLatch entered,
                                                        CountDownLatch release,
                                                        AuthResponse result) {
        return () -> {
            entered.countDown();
            waitForRelease(release);
            return result;
        };
    }

    private static void waitForRelease(CountDownLatch release) throws Exception {
        if (!release.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Synthetic worker was not released");
        }
    }

    private static void awaitResponse(AuthAttemptViewModel model) throws Exception {
        await(() -> model.getResponse().getValue() != null);
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(1);
        }
        assertTrue("Synthetic authentication worker did not complete", condition.getAsBoolean());
    }
}
