package com.vktea.feed;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.os.Looper;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Synthetic recipients and responses only; the retry check uses an owned loopback socket. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class VkPhotoShareRepositoryTest {
    private final AtomicReference<VkPhotoShareRepository.Snapshot> session = new AtomicReference<>(
            new VkPhotoShareRepository.Snapshot("synthetic-token", "synthetic-secret", "41"));
    private final AtomicReference<okhttp3.Request> request = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private VkPhotoShareRepository repository;
    @After public void close() { if (repository != null) repository.close(); shadowOf(Looper.getMainLooper()).idle(); }
    @Test public void recentConversationsKeepPersonalChatAndCommunityIdentitiesAndSkipDeniedWrites() throws Exception {
        create(chain -> response(chain, 200, "{\"response\":{\"items\":["
                + "{\"conversation\":{\"peer\":{\"id\":7}},\"last_message\":{\"peer_id\":7}},"
                + "{\"conversation\":{\"peer\":{\"id\":2000000003},\"chat_settings\":{\"title\":\"Synthetic chat\",\"photo\":{\"photo_100\":\"https://synthetic.invalid/chat\"}}},\"last_message\":{\"peer_id\":2000000003}},"
                + "{\"conversation\":{\"peer\":{\"id\":-9}},\"last_message\":{\"peer_id\":-9}},"
                + "{\"conversation\":{\"peer\":{\"id\":10},\"can_write\":{\"allowed\":false}},\"last_message\":{\"peer_id\":10}}],"
                + "\"profiles\":[{\"id\":7,\"first_name\":\"Synthetic\",\"last_name\":\"User\",\"photo_200\":\"https://synthetic.invalid/user\"}],"
                + "\"groups\":[{\"id\":9,\"name\":\"Synthetic group\",\"photo_100\":\"https://synthetic.invalid/group\"}]}}"));
        Result<List<PhotoShareRepository.Recipient>> result = new Result<>(); repository.recipients("", result); await(result);
        assertEquals("/method/messages.getConversations", request.get().url().encodedPath());
        assertEquals("40", request.get().url().queryParameter("count")); assertEquals("0", request.get().url().queryParameter("offset"));
        assertEquals("1", request.get().url().queryParameter("extended")); assertTrue(result.main);
        assertEquals(3, result.value.size()); assertEquals(7, result.value.get(0).peerId); assertEquals("Synthetic User", result.value.get(0).title);
        assertEquals(2_000_000_003L, result.value.get(1).peerId); assertEquals("https://synthetic.invalid/chat", result.value.get(1).photo);
        assertEquals(-9, result.value.get(2).peerId); assertEquals("Synthetic group", result.value.get(2).title);
    }
    @Test public void searchMatchesCoffeeArrayTypesAndLegacyAvatarFallbackWithoutAutomaticSelection() throws Exception {
        create(chain -> response(chain, 200, "{\"response\":["
                + "{\"id\":7,\"type\":\"profile\",\"first_name\":\"Synthetic\",\"photo_medium_rec\":\"https://synthetic.invalid/legacy\"},"
                + "{\"id\":3,\"type\":\"chat\",\"title\":\"Synthetic chat\"},"
                + "{\"id\":9,\"type\":\"group\",\"name\":\"Synthetic group\"},"
                + "{\"id\":3,\"type\":\"chat\",\"title\":\"Duplicate\"},null,{\"id\":0}]}"));
        Result<List<PhotoShareRepository.Recipient>> result = new Result<>(); repository.recipients(" Synthetic & query ", result); await(result);
        assertEquals("/method/messages.searchDialogs", request.get().url().encodedPath());
        assertEquals("Synthetic & query", request.get().url().queryParameter("q")); assertEquals("GET", request.get().method());
        assertEquals(3, result.value.size()); assertEquals("https://synthetic.invalid/legacy", result.value.get(0).photo);
        assertEquals(2_000_000_003L, result.value.get(1).peerId); assertEquals(-9, result.value.get(2).peerId);
        assertEquals(1, calls.get());
    }
    @Test public void attachmentSendIsSingleSignedPostWithStableExplicitIdAndNoPrivatePayloadInUrl() throws Exception {
        create(chain -> response(chain, 200, "{\"response\":123}"));
        Result<Long> result = new Result<>();
        repository.send(PhotoShareViewModelTest.photo(), PhotoShareViewModelTest.recipient(7), 654321, "Synthetic & draft + тест", result); await(result);
        okhttp3.Request sent = request.get(); assertEquals("POST", sent.method()); assertNull(sent.url().query());
        assertEquals("/method/messages.send", sent.url().encodedPath());
        LinkedHashMap<String, String> form = form(sent);
        assertEquals("7", form.get("peer_id")); assertNull(form.get("chat_id")); assertEquals("654321", form.get("random_id"));
        assertEquals("photo-41_12_synthetic-key", form.get("attachment")); assertFalse(form.containsKey("attachments"));
        assertEquals("Synthetic & draft + тест", form.get("message"));
        String signature = form.remove("sig"); StringBuilder raw = new StringBuilder("/method/messages.send?");
        for (Map.Entry<String, String> field : form.entrySet()) {
            if (raw.charAt(raw.length() - 1) != '?') raw.append('&'); raw.append(field.getKey()).append('=').append(field.getValue());
        }
        assertEquals(md5(raw + "synthetic-secret"), signature); assertEquals(Long.valueOf(123), result.value);
    }
    @Test public void chatUsesChatIdWhileCommunityKeepsNegativePeerAndPhotoKeyCanBeAbsent() throws Exception {
        create(chain -> response(chain, 200, "{\"response\":123}"));
        ViewerPhoto photo = new ViewerPhoto("synthetic", 12, -41, "", 0, 0, 0, false, false, false, false);
        Result<Long> chat = new Result<>(); repository.send(photo, PhotoShareViewModelTest.recipient(2_000_000_003L), 17, "", chat); await(chat);
        assertEquals("3", form(request.get()).get("chat_id")); assertNull(form(request.get()).get("peer_id"));
        assertEquals("photo-41_12", form(request.get()).get("attachment")); assertNull(form(request.get()).get("message"));
        Result<Long> group = new Result<>(); repository.send(photo, PhotoShareViewModelTest.recipient(-9), 18, "", group); await(group);
        assertEquals("-9", form(request.get()).get("peer_id")); assertNull(form(request.get()).get("chat_id"));
    }
    @Test public void transportAndVkErrorsAreSanitizedAndNeverResubmitAutomatically() throws Exception {
        create(chain -> { throw new IOException("SYNTHETIC_PRIVATE_TOKEN_AND_MESSAGE"); });
        Result<Long> result = new Result<>(); repository.send(PhotoShareViewModelTest.photo(), PhotoShareViewModelTest.recipient(7), 1, "Synthetic", result); await(result);
        assertEquals(PhotoSocialError.Kind.NETWORK, result.error.kind); assertNull(result.error.getCause());
        assertFalse(result.error.toString().contains("SYNTHETIC_PRIVATE")); assertEquals(1, calls.get());
        repository.close(); create(chain -> response(chain, 200, "{\"error\":{\"error_code\":901,\"error_msg\":\"SYNTHETIC_PRIVATE\"}}"));
        Result<Long> denied = new Result<>(); repository.send(PhotoShareViewModelTest.photo(), PhotoShareViewModelTest.recipient(7), 1, "Synthetic", denied); await(denied);
        assertEquals(PhotoSocialError.Kind.ACCESS, denied.error.kind); assertEquals(901, denied.error.code);
        assertEquals(2, calls.get()); assertFalse(denied.error.toString().contains("SYNTHETIC_PRIVATE"));
    }
    @Test public void changedSessionAndExplicitCancellationCannotDeliverOldRecipients() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        create(chain -> {
            entered.countDown();
            try { if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("Synthetic timeout"); }
            catch (InterruptedException ignored) { throw new IOException("Synthetic interruption"); }
            return response(chain, 200, "{\"response\":{\"items\":[]}}");
        });
        Result<List<PhotoShareRepository.Recipient>> stale = new Result<>(); repository.recipients("", stale);
        assertTrue(entered.await(3, TimeUnit.SECONDS)); session.set(new VkPhotoShareRepository.Snapshot("new-synthetic-token", "", "42"));
        release.countDown(); await(stale); assertNull(stale.value); assertEquals(PhotoSocialError.Kind.AUTH, stale.error.kind);
        Result<List<PhotoShareRepository.Recipient>> canceled = new Result<>(); PhotoShareRepository.Request operation = repository.recipients("", canceled); operation.cancel();
        for (int pass = 0; pass < 10; pass++) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(2); }
        assertEquals(0, canceled.callbacks);
    }
    @Test public void invalidPhotoPeerOrMissingSessionCannotContactTransport() throws Exception {
        create(chain -> response(chain, 200, "{}"));
        Result<Long> invalid = new Result<>(); repository.send(ViewerPhoto.fromUrl("synthetic"), PhotoShareViewModelTest.recipient(7), 1, "", invalid); await(invalid);
        assertEquals(PhotoSocialError.Kind.INVALID, invalid.error.kind);
        Result<Long> peer = new Result<>(); repository.send(PhotoShareViewModelTest.photo(), PhotoShareViewModelTest.recipient(0), 1, "", peer); await(peer);
        assertEquals(PhotoSocialError.Kind.INVALID, peer.error.kind);
        session.set(new VkPhotoShareRepository.Snapshot("", "", "")); Result<List<PhotoShareRepository.Recipient>> auth = new Result<>();
        repository.recipients("", auth); await(auth); assertEquals(PhotoSocialError.Kind.AUTH, auth.error.kind); assertEquals(0, calls.get());
    }
    @Test public void realOkHttp503RetryAfterZeroCannotDoubleSendMessage() throws Exception {
        AtomicInteger received = new AtomicInteger(); CountDownLatch finished = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(3000);
            Thread listener = new Thread(() -> {
                try {
                    while (!server.isClosed()) try (Socket socket = server.accept()) {
                        socket.setSoTimeout(1500);
                        BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                        int length = 0; String line;
                        while ((line = input.readLine()) != null && !line.isEmpty()) {
                            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                        }
                        for (int i = 0; i < length; i++) if (input.read() < 0) break;
                        received.incrementAndGet(); server.setSoTimeout(700);
                        socket.getOutputStream().write(("HTTP/1.1 503 Synthetic\r\nRetry-After: 0\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().flush();
                    }
                } catch (SocketTimeoutException expected) { /* No hidden follow-up within the bounded interval. */ }
                catch (IOException ignored) { /* The owned server is closing. */ }
                finally { finished.countDown(); }
            }, "synthetic-photo-message-loopback"); listener.start();
            repository = new VkPhotoShareRepository(new OkHttpClient(), HttpUrl.get("http://127.0.0.1:" + server.getLocalPort() + "/"), session::get);
            Result<Long> result = new Result<>(); repository.send(PhotoShareViewModelTest.photo(), PhotoShareViewModelTest.recipient(7), 17, "Synthetic", result); await(result);
            assertEquals(PhotoSocialError.Kind.API, result.error.kind); assertEquals(503, result.error.code);
            assertTrue(finished.await(2, TimeUnit.SECONDS)); assertEquals(1, received.get());
        }
    }
    private void create(Interceptor transport) {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> { calls.incrementAndGet(); request.set(chain.request()); return transport.intercept(chain); }).build();
        repository = new VkPhotoShareRepository(client, HttpUrl.get("https://synthetic-api.invalid/"), session::get);
    }
    private static Response response(Interceptor.Chain chain, int code, String body) {
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Synthetic")
                .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
    }
    private static LinkedHashMap<String, String> form(okhttp3.Request request) {
        FormBody body = (FormBody) request.body(); LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < body.size(); index++) result.put(body.name(index), body.value(index)); return result;
    }
    private static String md5(String value) throws Exception {
        StringBuilder hex = new StringBuilder(); for (byte digit : MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8))) hex.append(String.format(Locale.ROOT, "%02x", digit & 255)); return hex.toString();
    }
    private static void await(Result<?> result) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (result.callbacks == 0 && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(1); }
        assertEquals("Expected one synthetic callback", 1, result.callbacks);
    }
    private static final class Result<T> implements PhotoShareRepository.Callback<T> {
        T value; PhotoSocialError error; int callbacks; boolean main;
        @Override public void success(T value) { this.value = value; done(); }
        @Override public void error(PhotoSocialError error) { this.error = error; done(); }
        private void done() { callbacks++; main = Looper.myLooper() == Looper.getMainLooper(); }
    }
}
