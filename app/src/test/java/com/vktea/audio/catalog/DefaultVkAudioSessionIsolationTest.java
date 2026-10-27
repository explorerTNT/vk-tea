package com.vktea.audio.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.vktea.auth.Session;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** All credentials and transport responses in these tests are synthetic. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class DefaultVkAudioSessionIsolationTest {
    private Context context;
    private DefaultVkAudioRepository repository;
    private final AtomicInteger calls = new AtomicInteger();

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
        Session.save(context, "old-token", "41", "old-secret");
    }

    @After public void tearDown() {
        if (repository != null) repository.close();
        Session.clear(context);
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void credentialsRetainOneAtomicSnapshotAcrossSessionChange() {
        Session.Snapshot snapshot = Session.snapshot(context);
        Session.save(context, "new-token", "42", "new-secret");
        DefaultVkAudioRepository.Credentials old = DefaultVkAudioRepository.Credentials.fromSnapshot(snapshot);
        DefaultVkAudioRepository.Credentials current = DefaultVkAudioRepository.Credentials.fromSnapshot(Session.snapshot(context));
        assertEquals("old-token", old.token);
        assertEquals("old-secret", old.secret);
        assertEquals("41", old.userId);
        assertEquals("new-token", current.token);
        assertEquals("new-secret", current.secret);
        assertEquals("42", current.userId);
        assertFalse(old.sameSession(current));
    }

    @Test public void oldPlayerCannotSendRequestUsingNewSessionOrAnAbaSession() throws Exception {
        long oldGeneration = Session.generation();
        create(new OkHttpClient.Builder().addInterceptor(chain -> {
            calls.incrementAndGet();
            return response(chain);
        }).build(), oldGeneration);
        Session.save(context, "new-token", "42", "new-secret");
        // Even switching back to the original token must not revive the old player.
        assertTrue(Session.switchTo(context, "41"));
        Result<java.util.List<VkAudioTrack>> result = new Result<>();
        repository.getById(Collections.singletonList("41_9"), result);
        await(result);
        assertEquals(VkAudioError.Kind.AUTH_REQUIRED, result.error.kind);
        assertEquals(0, calls.get());
    }

    @Test public void changingSessionCancelsRegisteredInflightAudioRequest() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Call> running = new AtomicReference<>();
        create(new OkHttpClient.Builder().addInterceptor(chain -> {
            calls.incrementAndGet();
            running.set(chain.call());
            started.countDown();
            try {
                if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("synthetic timeout");
            } catch (InterruptedException e) {
                throw new IOException("synthetic interruption");
            }
            return response(chain);
        }).build(), Session.generation());
        Result<VkAudioPage> result = new Result<>();
        try {
            repository.getMyAudio(0, 10, result);
            assertTrue(started.await(3, TimeUnit.SECONDS));
            Session.save(context, "new-token", "42", "new-secret");
            assertNotNull(running.get());
            assertTrue(running.get().isCanceled());
        } finally {
            release.countDown();
        }
        await(result);
        assertEquals(VkAudioError.Kind.AUTH_REQUIRED, result.error.kind);
        assertEquals(1, calls.get());
    }

    @Test public void sessionChangeBetweenSnapshotAndRegistrationNeverSendsRequest() throws Exception {
        long generation = Session.generation();
        CountDownLatch snapshotTaken = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            calls.incrementAndGet();
            return response(chain);
        }).build();
        repository = new DefaultVkAudioRepository(client, HttpUrl.get("https://synthetic.invalid/"), () -> {
            DefaultVkAudioRepository.Credentials snapshot =
                    DefaultVkAudioRepository.Credentials.fromSnapshot(Session.snapshot(context));
            snapshotTaken.countDown();
            try {
                if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("synthetic timeout");
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            return snapshot;
        }, new Handler(Looper.getMainLooper()), generation);
        Result<java.util.List<VkAudioTrack>> result = new Result<>();
        Thread request = new Thread(() -> repository.getById(Collections.singletonList("41_9"), result));
        request.start();
        try {
            assertTrue(snapshotTaken.await(3, TimeUnit.SECONDS));
            Session.save(context, "new-token", "42", "new-secret");
        } finally {
            release.countDown();
        }
        request.join(3000);
        assertFalse(request.isAlive());
        await(result);
        assertEquals(VkAudioError.Kind.AUTH_REQUIRED, result.error.kind);
        assertEquals(0, calls.get());
    }

    private void create(OkHttpClient client, long generation) {
        repository = new DefaultVkAudioRepository(client, HttpUrl.get("https://synthetic.invalid/"),
                context, generation, new Handler(Looper.getMainLooper()));
    }

    private static Response response(okhttp3.Interceptor.Chain chain) {
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("Synthetic")
                .body(ResponseBody.create("{\"response\":{\"count\":0,\"items\":[]}}", null)).build();
    }

    private static void await(Result<?> result) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (result.count == 0 && System.nanoTime() < until) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(1);
        }
        assertEquals(1, result.count);
        assertNotNull(result.error);
    }

    private static final class Result<T> implements VkAudioRepository.Callback<T> {
        int count;
        VkAudioError error;
        @Override public void onSuccess(T value) { count++; }
        @Override public void onError(VkAudioError error) { this.error = error; count++; }
    }
}
