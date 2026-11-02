package com.vktea.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;

import com.vktea.auth.Session;
import com.vktea.msg.MessageLongPoll;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Interceptors supply every response locally; no request reaches VK or a network. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class VKApiSessionTest {
    private Context context;
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger requests = new AtomicInteger();
    private JSONObject success;
    private Exception failure;
    private int completions;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(MessageLongPoll.class, "instance", null);
        Session.clear(context);
        Session.save(context, "synthetic-token-a", "42", "synthetic-secret-a");
    }

    @After public void tearDown() {
        release.countDown();
        Session.clear(context);
        ReflectionHelpers.setStaticField(MessageLongPoll.class, "instance", null);
    }

    @Test public void delayedSuccessFromFormerSessionCannotReachNewOrRestoredAccount() throws Exception {
        VKApi.get(context, "synthetic.method", "", callback(), client(false, false));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        Session.save(context, "synthetic-token-b", "84", "synthetic-secret-b");
        Session.save(context, "synthetic-token-a", "42", "synthetic-secret-a");
        release.countDown();
        awaitCompletion();
        assertNull(success);
        assertTrue(failure instanceof VKApi.SessionChangedException);
        assertEquals(1, completions);
        assertEquals(1, requests.get());
    }

    @Test public void delayedFailureFromFormerSessionIsSanitizedAndDoesNotRetry() throws Exception {
        VKApi.get(context, "synthetic.method", "", callback(), client(true, false));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        Session.clear(context);
        release.countDown();
        awaitCompletion();
        assertNull(success);
        assertTrue(failure instanceof VKApi.SessionChangedException);
        assertFalse(failure.toString().contains("synthetic-private"));
        assertEquals(1, completions);
        assertEquals(1, requests.get());
    }

    @Test public void currentSessionReceivesSuccessOrSanitizedNetworkFailure() throws Exception {
        release.countDown();
        VKApi.get(context, "synthetic.method", "", callback(), client(false, false));
        awaitCompletion();
        assertNotNull(success);
        assertNull(failure);
        assertEquals(1, success.getInt("response"));

        success = null;
        failure = null;
        completions = 0;
        VKApi.get(context, "synthetic.method", "", callback(), client(true, false));
        awaitCompletion();
        assertNull(success);
        assertTrue(failure instanceof IOException);
        assertFalse(failure.toString().contains("synthetic-private"));
        assertNull(failure.getCause());
        assertEquals(2, requests.get());
    }

    @Test public void apiErrorDoesNotExposeResponseAndMissingSessionDoesNotSend() throws Exception {
        release.countDown();
        VKApi.get(context, "synthetic.method", "", callback(), client(false, true));
        awaitCompletion();
        assertNotNull(failure);
        assertFalse(failure.toString().contains("synthetic-private"));

        failure = null;
        completions = 0;
        Session.clear(context);
        VKApi.get(context, "synthetic.method", "", callback(), client(false, false));
        awaitCompletion();
        assertNotNull(failure);
        assertEquals(1, requests.get());
    }

    private VKApi.Callback callback() {
        return new VKApi.Callback() {
            @Override public void success(JSONObject response) { success = response; completions++; }
            @Override public void error(Exception error) { failure = error; completions++; }
        };
    }

    private OkHttpClient client(boolean failTransport, boolean failApi) {
        return new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.incrementAndGet();
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Synthetic timeout");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Synthetic interruption");
            }
            if (failTransport) throw new IOException("synthetic-private-transport-data");
            String body = failApi ? "{\"error\":{\"error_msg\":\"synthetic-private-response\"}}"
                    : "{\"response\":1}";
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("Synthetic")
                    .body(ResponseBody.create(body, MediaType.parse("application/json"))).build();
        }).build();
    }

    private void awaitCompletion() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (completions == 0 && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Synthetic callback did not complete", 1, completions);
    }
}
