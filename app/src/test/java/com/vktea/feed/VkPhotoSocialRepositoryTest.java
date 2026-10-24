package com.vktea.feed;

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

/** All values and responses are synthetic; only the retry test contacts its own loopback socket. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class VkPhotoSocialRepositoryTest {
    private final AtomicReference<VkPhotoSocialRepository.Snapshot> session = new AtomicReference<>(
            new VkPhotoSocialRepository.Snapshot("synthetic-token", "synthetic-secret", "41"));
    private final AtomicReference<okhttp3.Request> request = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private VkPhotoSocialRepository repository;
    @After public void close() { if (repository != null) repository.close(); shadowOf(Looper.getMainLooper()).idle(); }

    @Test public void metadataGetPreservesExplicitNestedDenialsAndChecksReturnedIdentity() throws Exception {
        create(chain -> response(chain, 200, "{\"response\":[{\"id\":1,\"owner_id\":41,"
                + "\"likes\":{\"count\":8,\"user_likes\":1,\"can_like\":0},\"comments\":{\"count\":3,\"can_post\":0},"
                + "\"reposts\":{\"count\":2},\"can_repost\":1}]}"));
        Result<ViewerPhoto> result = new Result<>(); repository.load(photo(), result); await(result);
        assertEquals("GET", request.get().method());
        assertEquals("41_1_synthetic-key", request.get().url().queryParameter("photos"));
        assertEquals("1", request.get().url().queryParameter("extended"));
        assertEquals(8, result.value.likes); assertTrue(result.value.liked);
        assertFalse(result.value.canLike); assertFalse(result.value.canComment); assertTrue(result.value.canRepost);
        assertTrue(result.main);
        repository.close(); create(chain -> response(chain, 200, "{\"response\":[{\"id\":99,\"owner_id\":41}]}"));
        Result<ViewerPhoto> wrong = new Result<>(); repository.load(photo(), wrong); await(wrong);
        assertNull(wrong.value); assertEquals(PhotoSocialError.Kind.UNAVAILABLE, wrong.error.kind);
    }
    @Test public void repostUsesSignedPostAndSeparateAccessKeyWithoutPrivateTextInUrl() throws Exception {
        create(chain -> response(chain, 200, "{\"response\":{\"post_id\":123,\"reposts_count\":5}}"));
        Result<Integer> result = new Result<>(); repository.repost(photo(), "Synthetic & text + тест", result); await(result);
        okhttp3.Request sent = request.get();
        assertEquals("POST", sent.method()); assertNull(sent.url().query()); assertEquals("/method/wall.repost", sent.url().encodedPath());
        LinkedHashMap<String, String> form = form(sent);
        assertEquals("photo41_1", form.get("object")); assertEquals("synthetic-key", form.get("access_key"));
        assertEquals("Synthetic & text + тест", form.get("message"));
        String sig = form.remove("sig");
        StringBuilder raw = new StringBuilder("/method/wall.repost?");
        for (Map.Entry<String, String> field : form.entrySet()) {
            if (raw.charAt(raw.length() - 1) != '?') raw.append('&'); raw.append(field.getKey()).append('=').append(field.getValue());
        }
        assertEquals(md5(raw + "synthetic-secret"), sig); assertEquals(Integer.valueOf(5), result.value);
    }
    @Test public void discussionOwnerLookupUsesSignedSafeUserOrGroupReadAndChecksIdentity() throws Exception {
        create(chain -> response(chain, 200, "{\"response\":[{\"id\":41,\"first_name\":\"Synthetic\",\"last_name\":\"Author\","
                + "\"photo_200\":\" \",\"photo_100\":\"file:///avatar\"}]}"));
        Result<PhotoSocialRepository.Owner> user = new Result<>(); repository.owner(photo(), user); await(user);
        assertEquals("GET", request.get().method());
        assertEquals("/method/users.get", request.get().url().encodedPath());
        assertEquals("41", request.get().url().queryParameter("user_ids"));
        assertEquals("photo_200", request.get().url().queryParameter("fields"));
        assertEquals("Synthetic Author", user.value.name); assertEquals("file:///avatar", user.value.photo);
        assertNotNull(request.get().url().queryParameter("sig"));
        repository.close(); create(chain -> response(chain, 200, "{\"response\":[{\"id\":91,\"name\":\"Synthetic group\"}]}"));
        ViewerPhoto groupPhoto = new ViewerPhoto("file:///photo", 17, -91, "", 0, 0, 0, false, false, false, false);
        Result<PhotoSocialRepository.Owner> group = new Result<>(); repository.owner(groupPhoto, group); await(group);
        assertEquals("/method/groups.getById", request.get().url().encodedPath());
        assertEquals("91", request.get().url().queryParameter("group_ids"));
        assertEquals("Synthetic group", group.value.name);
        Result<PhotoSocialRepository.Owner> wrong = new Result<>(); repository.owner(photo(), wrong); await(wrong);
        assertEquals(PhotoSocialError.Kind.UNAVAILABLE, wrong.error.kind);
    }
    @Test public void metadataKeepsKnownCaptionDateAndOwnerWhenServerOmitsThem() throws Exception {
        create(chain -> response(chain, 200, "{\"response\":[{\"id\":1,\"owner_id\":41}]}"));
        ViewerPhoto source = new ViewerPhoto("file:///photo", 1, 41, "synthetic-key", 0, 0, 0, false, false, false, false,
                "Synthetic owner", "file:///avatar", 1700000000, "Synthetic caption");
        Result<ViewerPhoto> result = new Result<>(); repository.load(source, result); await(result);
        assertEquals(source.ownerName, result.value.ownerName);
        assertEquals(source.ownerPhoto, result.value.ownerPhoto);
        assertEquals(source.date, result.value.date);
        assertEquals(source.text, result.value.text);
    }
    @Test public void likeAndCommentUsePhotoParametersAndActualResponseValues() throws Exception {
        create(chain -> response(chain, 200, chain.request().url().encodedPath().endsWith("likes.delete")
                ? "{\"response\":{\"likes\":7}}" : "{\"response\":88}"));
        Result<Integer> unlike = new Result<>(); repository.like(photo(), false, unlike); await(unlike);
        assertEquals("photo", form(request.get()).get("type")); assertEquals("1", form(request.get()).get("item_id"));
        assertEquals(Integer.valueOf(7), unlike.value);
        Result<Long> comment = new Result<>(); repository.comment(photo(), "Synthetic comment", comment); await(comment);
        assertEquals("/method/photos.createComment", request.get().url().encodedPath());
        assertEquals("41", form(request.get()).get("owner_id")); assertEquals("1", form(request.get()).get("photo_id"));
        assertEquals("Synthetic comment", form(request.get()).get("message")); assertEquals(Long.valueOf(88), comment.value);
    }
    @Test public void commentsUsePublicExtendedShapeAndReturnChronologicalNamesAndFallbacks() throws Exception {
        create(chain -> response(chain, 200, "{\"response\":{\"count\":3,\"items\":["
                + "{\"id\":3,\"from_id\":-7,\"text\":\"Synthetic latest\"},"
                + "{\"id\":2,\"from_id\":9,\"text\":null,\"attachments\":[{}]},"
                + "{\"id\":1,\"from_id\":9,\"text\":\"Synthetic oldest\"}],"
                + "\"profiles\":[{\"id\":9,\"first_name\":\"Synthetic\",\"last_name\":\"User\"}],"
                + "\"groups\":[{\"id\":7,\"name\":\"Synthetic group\"}]}}"));
        Result<PhotoSocialRepository.Comments> result = new Result<>(); repository.comments(photo(), result); await(result);
        assertEquals("/method/photos.getComments", request.get().url().encodedPath());
        assertEquals("50", request.get().url().queryParameter("count")); assertEquals("desc", request.get().url().queryParameter("sort"));
        assertEquals("1", request.get().url().queryParameter("extended"));
        assertEquals(1, result.value.items.get(0).id); assertEquals("Synthetic User", result.value.items.get(0).author);
        assertEquals("Вложение", result.value.items.get(1).text); assertEquals("Synthetic group", result.value.items.get(2).author);
        assertEquals(3, result.value.total);
    }
    @Test public void failuresExposeNoPayloadAndNeverReplayMutation() throws Exception {
        create(chain -> { throw new IOException("SYNTHETIC_PRIVATE_TOKEN_OR_DRAFT"); });
        Result<Integer> result = new Result<>(); repository.repost(photo(), "Synthetic", result); await(result);
        assertEquals(PhotoSocialError.Kind.NETWORK, result.error.kind); assertNull(result.error.getCause());
        assertFalse(result.error.toString().contains("SYNTHETIC_PRIVATE")); assertEquals(1, calls.get());
        repository.close(); create(chain -> response(chain, 200, "{\"error\":{\"error_code\":9,\"error_msg\":\"SYNTHETIC_PRIVATE\"}}"));
        Result<Integer> rate = new Result<>(); repository.like(photo(), true, rate); await(rate);
        assertEquals(PhotoSocialError.Kind.RATE_LIMITED, rate.error.kind); assertEquals(9, rate.error.code);
        assertNull(rate.error.getCause()); assertEquals(2, calls.get());
    }
    @Test public void staleSessionAndCancellationCannotDeliverPrivateOldResult() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        create(chain -> {
            entered.countDown();
            try { if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("Synthetic timeout"); }
            catch (InterruptedException ignored) { throw new IOException("Synthetic interruption"); }
            return response(chain, 200, "{\"response\":{\"likes\":3}}");
        });
        Result<Integer> stale = new Result<>(); repository.like(photo(), true, stale); assertTrue(entered.await(3, TimeUnit.SECONDS));
        session.set(new VkPhotoSocialRepository.Snapshot("other-synthetic-token", "", "42")); release.countDown(); await(stale);
        assertNull(stale.value); assertEquals(PhotoSocialError.Kind.AUTH, stale.error.kind);
        Result<Integer> canceled = new Result<>(); PhotoSocialRepository.Request operation = repository.like(photo(), true, canceled);
        operation.cancel();
        for (int pass = 0; pass < 10; pass++) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(2); }
        assertEquals(0, canceled.callbacks);
    }
    @Test public void invalidIdentityAndMissingSessionNeverReachTransport() throws Exception {
        create(chain -> response(chain, 200, "{}"));
        Result<ViewerPhoto> invalid = new Result<>(); repository.load(ViewerPhoto.fromUrl("synthetic"), invalid); await(invalid);
        assertEquals(PhotoSocialError.Kind.INVALID, invalid.error.kind);
        session.set(new VkPhotoSocialRepository.Snapshot("", "", ""));
        Result<Integer> auth = new Result<>(); repository.like(photo(), true, auth); await(auth);
        assertEquals(PhotoSocialError.Kind.AUTH, auth.error.kind); assertEquals(0, calls.get());
    }
    @Test public void realOkHttp503RetryAfterZeroCannotDoublePost() throws Exception {
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
                } catch (SocketTimeoutException expected) { /* Bounded quiet interval establishes no second request. */ }
                catch (IOException ignored) { /* Closing the owned socket ends the test. */ }
                finally { finished.countDown(); }
            }, "synthetic-photo-loopback");
            listener.start();
            repository = new VkPhotoSocialRepository(new OkHttpClient(), HttpUrl.get("http://127.0.0.1:" + server.getLocalPort() + "/"), session::get);
            Result<Integer> result = new Result<>(); repository.repost(photo(), "Synthetic", result); await(result);
            assertEquals(PhotoSocialError.Kind.API, result.error.kind); assertEquals(503, result.error.code);
            assertTrue(finished.await(2, TimeUnit.SECONDS)); assertEquals(1, received.get());
        }
    }
    private static ViewerPhoto photo() { return PhotoSocialViewModelTest.photo(1); }
    private void create(Interceptor transport) {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            calls.incrementAndGet(); request.set(chain.request()); return transport.intercept(chain);
        }).build();
        repository = new VkPhotoSocialRepository(client, HttpUrl.get("https://synthetic-api.invalid/"), session::get);
    }
    private static Response response(Interceptor.Chain chain, int code, String body) {
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Synthetic")
                .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
    }
    private static LinkedHashMap<String, String> form(okhttp3.Request request) {
        FormBody body = (FormBody) request.body(); LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        for (int i = 0; i < body.size(); i++) fields.put(body.name(i), body.value(i)); return fields;
    }
    private static String md5(String value) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte digit : MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8))) hex.append(String.format(Locale.ROOT, "%02x", digit & 255));
        return hex.toString();
    }
    private static void await(Result<?> result) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (result.callbacks == 0 && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(1); }
        assertEquals("Expected one synthetic callback", 1, result.callbacks);
    }
    private static final class Result<T> implements PhotoSocialRepository.Callback<T> {
        T value; PhotoSocialError error; int callbacks; boolean main;
        @Override public void success(T result) { value = result; completed(); }
        @Override public void error(PhotoSocialError failure) { error = failure; completed(); }
        private void completed() { callbacks++; main = Looper.myLooper() == Looper.getMainLooper(); }
    }
}
