package com.vktea.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;

import androidx.lifecycle.ViewModelProvider;

import com.vktea.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowToast;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import okhttp3.FormBody;
import okhttp3.HttpUrl;

/** Callback regressions use an injected client; no browser or auth endpoint is contacted. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class AuthWebViewActivityTest {
    private static final String SYNTHETIC_CODE = "synthetic-single-use-code";
    private static final String SYNTHETIC_VERIFIER = "synthetic-pkce-verifier-for-offline-tests";
    private static final String SYNTHETIC_DEVICE = "synthetic-device-for-offline-tests";

    private ActivityController<TestAuthWebViewActivity> controller;
    private AuthAttemptViewModel model;
    private FakeAuthClient client;

    @Before
    public void prepareOfflineCallback() {
        clearPreferences();
        ShadowLog.clear();
        ShadowToast.reset();
        client = new FakeAuthClient();
        TestAuthWebViewActivity.client = client;
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("auth", Context.MODE_PRIVATE).edit()
                .putString("code_verifier", SYNTHETIC_VERIFIER)
                .putString("device_id", SYNTHETIC_DEVICE)
                .commit();
    }

    @After
    public void releaseWorkerAndDestroyActivity() throws Exception {
        client.release.countDown();
        if (model != null) {
            await(() -> !model.isBusy());
        }
        if (controller != null) {
            controller.close();
        }
        TestAuthWebViewActivity.client = null;
        clearPreferences();
    }

    @Test
    public void duplicateCallbackIntentWhilePendingExchangesCodeOnlyOnce() throws Exception {
        client.block();
        launch(callbackIntent("vktea"));
        assertTrue(client.entered.await(5, TimeUnit.SECONDS));

        activity().onNewIntent(callbackIntent("vktea"));
        activity().onNewIntent(callbackIntent("vktea"));
        shadowOf(Looper.getMainLooper()).idle();

        assertTrue(model.isBusy());
        assertEquals(1, client.vkIdCalls.get());
        assertEquals(1, client.totalCalls());
        assertFalse(authPreferencesContainVerifier());
        assertFalse(activity().isFinishing());
        assertEquals(0, ShadowToast.shownToastCount());

        client.release.countDown();
        await(() -> !model.isBusy() && activity().isFinishing());
        assertEquals(1, client.totalCalls());
        assertNull(model.takeResponse());
        assertFalse(Session.isAuthorized(activity()));
        assertNoSensitiveCallbackLogs();
    }

    @Test
    public void rotationRetainsPendingExchangeAndOnlyNewOwnerHandlesItsResult() throws Exception {
        client.block();
        launch(callbackIntent("vktea"));
        assertTrue(client.entered.await(5, TimeUnit.SECONDS));
        AuthAttemptViewModel retained = model;
        TestAuthWebViewActivity oldOwner = activity();

        controller.recreate();
        model = requests();

        assertSame(retained, model);
        assertTrue(model.isBusy());
        assertEquals(1, client.totalCalls());
        assertFalse(activity().isFinishing());
        assertFalse(authPreferencesContainVerifier());
        client.release.countDown();
        await(() -> !model.isBusy() && activity().isFinishing());

        assertTrue(oldOwner.isDestroyed());
        assertFalse(oldOwner.isFinishing());
        assertEquals(1, ShadowToast.shownToastCount());
        assertEquals(activity().getString(R.string.auth_rejected), ShadowToast.getTextOfLatestToast());
        assertNull(model.getResponse().getValue());
        assertNull(model.takeResponse());
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MINUTES);
        assertEquals(1, ShadowToast.shownToastCount());
        assertEquals(1, client.totalCalls());
        assertNoSensitiveCallbackLogs();
    }

    @Test
    public void laterCallbackCannotReuseVerifierConsumedByCompletedExchange() throws Exception {
        launch(callbackIntent("vktea"));
        await(() -> !model.isBusy() && activity().isFinishing());
        assertEquals(1, client.totalCalls());
        assertFalse(authPreferencesContainVerifier());
        assertNull(model.getResponse().getValue());

        controller.close();
        controller = null;
        launch(callbackIntent("vktea"));
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MINUTES);

        assertTrue(activity().isFinishing());
        assertFalse(model.isBusy());
        assertEquals(1, client.vkIdCalls.get());
        assertEquals(1, client.totalCalls());
        assertFalse(authPreferencesContainVerifier());
        assertNull(model.getResponse().getValue());
        assertFalse(Session.isAuthorized(activity()));
        assertNoSensitiveCallbackLogs();
    }

    @Test
    public void wrongStateRejectsCallbackWithoutExchangingOrConsumingVerifier() {
        launch(callbackIntent("synthetic-wrong-state"));
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MINUTES);

        assertTrue(activity().isFinishing());
        assertFalse(model.isBusy());
        assertEquals(0, client.totalCalls());
        assertTrue(authPreferencesContainVerifier());
        assertEquals(1, ShadowToast.shownToastCount());
        assertEquals(activity().getString(R.string.auth_failed), ShadowToast.getTextOfLatestToast());
        assertNull(model.getResponse().getValue());
        assertFalse(Session.isAuthorized(activity()));
        assertNoSensitiveCallbackLogs();
    }

    private void launch(Intent callback) {
        controller = Robolectric.buildActivity(TestAuthWebViewActivity.class, callback).setup();
        model = requests();
    }

    private TestAuthWebViewActivity activity() {
        return controller.get();
    }

    private AuthAttemptViewModel requests() {
        return new ViewModelProvider(activity()).get(AuthAttemptViewModel.class);
    }

    private static Intent callbackIntent(String state) {
        Uri callback = Uri.parse("vk" + AuthWebViewActivity.CLIENT_ID + "://vk.ru/blank.html")
                .buildUpon()
                .appendQueryParameter("code", SYNTHETIC_CODE)
                .appendQueryParameter("state", state)
                .appendQueryParameter("device_id", SYNTHETIC_DEVICE)
                .build();
        return new Intent(RuntimeEnvironment.getApplication(), TestAuthWebViewActivity.class)
                .setData(callback)
                .putExtra(AuthWebViewActivity.EXTRA_MODE, AuthWebViewActivity.MODE_VK_ID);
    }

    private static boolean authPreferencesContainVerifier() {
        Context context = RuntimeEnvironment.getApplication();
        return context.getSharedPreferences("auth", Context.MODE_PRIVATE).contains("code_verifier");
    }

    private static void assertNoSensitiveCallbackLogs() {
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            String logged = String.valueOf(item.msg) + String.valueOf(item.throwable);
            assertFalse(logged.contains(SYNTHETIC_CODE));
            assertFalse(logged.contains(SYNTHETIC_VERIFIER));
            assertFalse(logged.contains(SYNTHETIC_DEVICE));
        }
    }

    private static void clearPreferences() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("auth", Context.MODE_PRIVATE).edit().clear().commit();
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(1);
        }
        assertTrue("Synthetic callback exchange did not complete", condition.getAsBoolean());
    }

    public static class TestAuthWebViewActivity extends AuthWebViewActivity {
        static FakeAuthClient client;

        @Override
        protected AuthHttpClient createAuthClient() {
            if (client == null) {
                throw new IllegalStateException("Offline callback client was not installed");
            }
            return client;
        }
    }

    private static class FakeAuthClient extends AuthHttpClient {
        final AtomicInteger passwordCalls = new AtomicInteger();
        final AtomicInteger tokenCalls = new AtomicInteger();
        final AtomicInteger vkIdCalls = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(0);

        void block() {
            release = new CountDownLatch(1);
        }

        int totalCalls() {
            return passwordCalls.get() + tokenCalls.get() + vkIdCalls.get();
        }

        @Override
        AuthResponse password(HttpUrl ignored) {
            passwordCalls.incrementAndGet();
            return AuthResponse.failure(AuthResponse.Method.PASSWORD);
        }

        @Override
        AuthResponse token(String ignoredToken, String ignoredSecret) {
            tokenCalls.incrementAndGet();
            return AuthResponse.failure(AuthResponse.Method.TOKEN);
        }

        @Override
        AuthResponse vkId(String ignoredEndpoint, FormBody ignoredForm) {
            vkIdCalls.incrementAndGet();
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return AuthResponse.failure(AuthResponse.Method.VK_ID);
        }
    }
}
