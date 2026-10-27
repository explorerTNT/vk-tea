package com.vktea.msg;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class MessageForwarderTest {
    private MessageForwarder forwarder;
    @After public void close() { if (forwarder != null) forwarder.close(); }

    @Test public void forwardUsesOneSignedPostWithMessageAndPeerIdentity() throws Exception {
        AtomicReference<okhttp3.Request> captured = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            calls.incrementAndGet(); captured.set(chain.request());
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("Synthetic").body(ResponseBody.create(MediaType.get("application/json"), "{\"response\":123}")).build();
        }).build();
        forwarder = new MessageForwarder(client, HttpUrl.get("https://synthetic.invalid/"),
                () -> new MessageForwarder.Credentials("synthetic-token", "synthetic-secret", "1"));
        Result result = new Result(); forwarder.send(-9, 71, result); await(result);
        assertNotNull(result.response); assertFalse(result.failed); assertEquals(1, calls.get());
        okhttp3.Request request = captured.get();
        assertEquals("POST", request.method()); assertEquals("/method/messages.send", request.url().encodedPath());
        assertNull(request.url().query());
        FormBody form = (FormBody) request.body();
        assertEquals("-9", value(form, "peer_id")); assertEquals("71", value(form, "forward_messages"));
        assertNotEquals("0", value(form, "random_id")); assertNotNull(value(form, "sig"));
    }

    @Test public void failedTransportNeverResubmitsAndInvalidMessageNeverSends() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            calls.incrementAndGet(); throw new IOException("synthetic private detail");
        }).build();
        forwarder = new MessageForwarder(client, HttpUrl.get("https://synthetic.invalid/"),
                () -> new MessageForwarder.Credentials("synthetic-token", "", "1"));
        Result invalid = new Result(); forwarder.send(7, -1, invalid); assertTrue(invalid.failed);
        assertEquals(0, calls.get());
        Result result = new Result(); forwarder.send(7, 1, result); await(result);
        assertTrue(result.failed); assertEquals(1, calls.get());
    }

    @Test public void textUsesPostWithExplicitRandomIdAndNoMessageInUrl() throws Exception {
        AtomicReference<okhttp3.Request> captured = new AtomicReference<>();
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            captured.set(chain.request());
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("Synthetic").body(ResponseBody.create(MediaType.get("application/json"), "{\"response\":8}")).build();
        }).build();
        forwarder = new MessageForwarder(client, HttpUrl.get("https://synthetic.invalid/"),
                () -> new MessageForwarder.Credentials("synthetic-token", "", "1"));
        Result result = new Result(); forwarder.sendText(7, "Synthetic & text", result); await(result);
        assertNotNull(result.response); assertEquals("POST", captured.get().method());
        assertNull(captured.get().url().query());
        FormBody form = (FormBody) captured.get().body();
        assertEquals("Synthetic & text", value(form, "message"));
        assertEquals("7", value(form, "peer_id")); assertNotEquals("0", value(form, "random_id"));
    }

    private static String value(FormBody form, String key) {
        for (int i = 0; i < form.size(); i++) if (key.equals(form.name(i))) return form.value(i);
        return null;
    }
    private static void await(Result result) throws Exception {
        for (int i = 0; i < 300 && !result.done; i++) { Thread.sleep(10); shadowOf(Looper.getMainLooper()).idle(); }
        assertTrue("callback timed out", result.done);
    }
    private static final class Result implements MessageForwarder.Callback {
        boolean done, failed; JSONObject response;
        @Override public void complete(JSONObject value) { response = value; done = true; }
        @Override public void failed() { failed = true; done = true; }
    }
}
