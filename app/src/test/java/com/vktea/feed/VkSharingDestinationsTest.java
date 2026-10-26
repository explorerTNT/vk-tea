package com.vktea.feed;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.os.Looper;
import com.vktea.share.ShareAttachment;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Only synthetic pages and mutation responses; no account or external endpoint is contacted. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class VkSharingDestinationsTest {
    private final List<okhttp3.Request> requests = java.util.Collections.synchronizedList(new ArrayList<>());
    private final AtomicReference<VkPhotoShareRepository.Snapshot> session = new AtomicReference<>(
            new VkPhotoShareRepository.Snapshot("synthetic-token", "synthetic-secret", "41"));
    private VkPhotoShareRepository repository;
    @After public void close() { if (repository != null) repository.close(); shadowOf(Looper.getMainLooper()).idle(); }
    @Test public void communitiesCollectAllPagesByRawOffsetsAndOnlyKeepWritableEditorOrAdministratorGroups() throws Exception {
        create(chain -> response(chain, "0".equals(chain.request().url().queryParameter("offset"))
                ? "{\"response\":{\"count\":8,\"items\":[{\"id\":1,\"name\":\"Synthetic admin\",\"admin_level\":3,\"can_post\":1},"
                + "{\"id\":2,\"admin_level\":1,\"can_post\":1},{\"id\":3,\"admin_level\":2,\"can_post\":0},null,"
                + "{\"id\":4,\"admin_level\":3,\"deactivated\":\"deleted\"},{\"id\":5,\"name\":\"Synthetic editor\",\"admin_level\":2}]}}"
                : "{\"response\":{\"count\":8,\"items\":[{\"id\":1,\"name\":\"Duplicate\",\"admin_level\":3},"
                + "{\"id\":6,\"name\":\"Synthetic next page\",\"admin_level\":2,\"can_post\":true,\"photo_100\":\"https://synthetic.invalid/avatar\"}]}}"));
        Result<List<PhotoShareRepository.Community>> result = new Result<>(); repository.communities(result); await(result);
        assertEquals(2, requests.size()); assertEquals("/method/groups.get", requests.get(0).url().encodedPath());
        assertEquals("editor", requests.get(0).url().queryParameter("filter")); assertEquals("1", requests.get(0).url().queryParameter("extended"));
        assertEquals("1000", requests.get(0).url().queryParameter("count")); assertEquals("6", requests.get(1).url().queryParameter("offset"));
        assertEquals(3, result.value.size()); assertEquals(1, result.value.get(0).id); assertEquals(5, result.value.get(1).id); assertEquals(6, result.value.get(2).id);
        assertEquals("Synthetic admin", result.value.get(0).title); assertTrue(result.main);
    }
    @Test public void allFriendsIncludingNoExistingDialogArePagedAndLocallySearchable() throws Exception {
        create(chain -> response(chain, "0".equals(chain.request().url().queryParameter("offset"))
                ? "{\"response\":{\"count\":5,\"items\":[{\"id\":7,\"first_name\":\"Synthetic\",\"last_name\":\"Alpha\"},null,"
                + "{\"id\":8,\"first_name\":\"Synthetic\",\"last_name\":\"Beta\"}]}}"
                : "{\"response\":{\"count\":5,\"items\":[{\"id\":9,\"first_name\":\"Synthetic\",\"last_name\":\"Beta Two\"},"
                + "{\"id\":10,\"first_name\":\"Synthetic\",\"deactivated\":\"deleted\"}]}}"));
        Result<List<PhotoShareRepository.Recipient>> result = new Result<>(); repository.friends(" beta ", result); await(result);
        assertEquals(2, requests.size()); assertEquals("/method/friends.get", requests.get(0).url().encodedPath());
        assertEquals("hints", requests.get(0).url().queryParameter("order")); assertNull(requests.get(0).url().queryParameter("q"));
        assertEquals("3", requests.get(1).url().queryParameter("offset")); assertEquals(2, result.value.size());
        assertEquals(8, result.value.get(0).peerId); assertEquals(9, result.value.get(1).peerId);
    }
    @Test public void wallRepostUsesOwnOrPositiveCommunityDestinationAndSeparateAccessKey() throws Exception {
        create(chain -> response(chain, "{\"response\":{\"post_id\":88,\"reposts_count\":2,\"likes_count\":1}}"));
        ShareAttachment item = ShareAttachment.post(-41, 12, "synthetic-key");
        Result<Long> own = new Result<>(); repository.repost(item, 0, "Synthetic own caption", own); await(own);
        assertEquals("POST", requests.get(0).method()); assertNull(requests.get(0).url().query());
        assertEquals("/method/wall.repost", requests.get(0).url().encodedPath());
        assertEquals("wall-41_12", field(requests.get(0), "object")); assertNull(field(requests.get(0), "group_id"));
        assertEquals("synthetic-key", field(requests.get(0), "access_key")); assertNotNull(field(requests.get(0), "sig"));
        Result<Long> group = new Result<>(); repository.repost(item, 7, "Synthetic community caption", group); await(group);
        assertEquals("7", field(requests.get(1), "group_id")); assertEquals("Synthetic community caption", field(requests.get(1), "message"));
        assertEquals(Long.valueOf(88), group.value);
    }
    @Test public void genericMessagesSendUsesCoffeeWallIdentityAndExplicitRandomId() throws Exception {
        create(chain -> response(chain, "{\"response\":123}"));
        Result<Long> result = new Result<>(); repository.send(ShareAttachment.post(-41, 12, "synthetic-key"),
                new PhotoShareRepository.Recipient(7, "Synthetic friend", ""), 34567, "Synthetic draft", result); await(result);
        assertEquals("/method/messages.send", requests.get(0).url().encodedPath());
        assertEquals("wall-41_12", field(requests.get(0), "attachment"));
        assertEquals("34567", field(requests.get(0), "random_id")); assertEquals("7", field(requests.get(0), "peer_id"));
        assertNull(field(requests.get(0), "group_id")); assertNull(requests.get(0).url().query());
    }
    @Test public void failedLaterPageNeverReturnsAPartialSuccessAndIsNotRetried() throws Exception {
        create(chain -> response(chain, "0".equals(chain.request().url().queryParameter("offset"))
                ? "{\"response\":{\"count\":2,\"items\":[{\"id\":1,\"admin_level\":3}]}}"
                : "{\"error\":{\"error_code\":6,\"error_msg\":\"SYNTHETIC_PRIVATE_RESPONSE\"}}"));
        Result<List<PhotoShareRepository.Community>> result = new Result<>(); repository.communities(result); await(result);
        assertNull(result.value); assertEquals(PhotoSocialError.Kind.RATE_LIMITED, result.error.kind); assertEquals(2, requests.size());
        assertFalse(result.error.toString().contains("SYNTHETIC_PRIVATE")); assertNull(result.error.getCause());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1)); assertEquals(2, requests.size());
    }
    @Test public void cancellationAndSessionChangeStopPaginationWithoutReturningOldRows() throws Exception {
        CountDownLatch first = new CountDownLatch(1);
        create(chain -> { first.countDown(); return response(chain, "{\"response\":{\"count\":2,\"items\":[{\"id\":7,\"first_name\":\"Synthetic\"}]}}"); });
        Result<List<PhotoShareRepository.Recipient>> canceled = new Result<>(); PhotoShareRepository.Request operation = repository.friends("", canceled);
        shadowOf(Looper.getMainLooper()).idle(); assertTrue(first.await(3, TimeUnit.SECONDS)); operation.cancel();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1)); assertEquals(0, canceled.callbacks); assertEquals(1, requests.size());
        Result<List<PhotoShareRepository.Recipient>> stale = new Result<>(); repository.friends("", stale);
        for (int pass = 0; pass < 30; pass++) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(2); }
        session.set(new VkPhotoShareRepository.Snapshot("different-synthetic-token", "", "42")); await(stale);
        assertNull(stale.value); assertEquals(PhotoSocialError.Kind.AUTH, stale.error.kind); assertEquals(2, requests.size());
    }
    @Test public void invalidWallPermissionOrDestinationNeverReachesTheTransport() throws Exception {
        create(chain -> response(chain, "{}"));
        Result<Long> denied = new Result<>(); repository.repost(ShareAttachment.photo(-41, 12, "", false), 0, "", denied); await(denied);
        Result<Long> negative = new Result<>(); repository.repost(ShareAttachment.post(-41, 12, ""), -7, "", negative); await(negative);
        assertEquals(PhotoSocialError.Kind.INVALID, denied.error.kind); assertEquals(PhotoSocialError.Kind.INVALID, negative.error.kind); assertEquals(0, requests.size());
    }
    private void create(Interceptor response) {
        repository = new VkPhotoShareRepository(new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.add(chain.request()); return response.intercept(chain);
        }).build(), HttpUrl.get("https://synthetic-api.invalid/"), session::get);
    }
    private static Response response(Interceptor.Chain chain, String body) {
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
                .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
    }
    private static String field(okhttp3.Request request, String key) {
        FormBody body = (FormBody) request.body(); for (int index = 0; index < body.size(); index++) if (body.name(index).equals(key)) return body.value(index); return null;
    }
    private static void await(Result<?> result) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (result.callbacks == 0 && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10)); Thread.sleep(1); }
        assertEquals("Expected exactly one completed synthetic operation", 1, result.callbacks);
    }
    private static final class Result<T> implements PhotoShareRepository.Callback<T> {
        T value; PhotoSocialError error; int callbacks; boolean main;
        @Override public void success(T result) { value = result; done(); }
        @Override public void error(PhotoSocialError failure) { error = failure; done(); }
        private void done() { callbacks++; main = Looper.myLooper() == Looper.getMainLooper(); }
    }
}
