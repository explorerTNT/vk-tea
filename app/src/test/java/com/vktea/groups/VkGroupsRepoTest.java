package com.vktea.groups;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.os.Looper;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Interceptors supply every byte in memory. No real VK endpoint, socket or account is accessed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class VkGroupsRepoTest {
    private final AtomicReference<VkGroupsRepository.Snapshot> session = new AtomicReference<>(
            new VkGroupsRepository.Snapshot("synthetic-token", "synthetic-secret", "41"));
    private final AtomicInteger calls = new AtomicInteger();
    private final List<okhttp3.Request> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
    private VkGroupsRepository repository;
    @After public void close() { if (repository != null) repository.close(); shadowOf(Looper.getMainLooper()).idle(); }

    @Test public void signedReadUsesOwnAccountAndPublishesImmutableRichRowsOnMain() throws Exception {
        create(chain -> response(chain, 200, "{\"response\":{\"count\":2,\"items\":["
                + "{\"id\":7,\"name\":\"Synthetic Community\",\"is_admin\":1,\"admin_level\":1,\"type\":\"page\","
                + "\"photo_200\":\" \",\"photo_100\":\"file:///avatar\",\"members_count\":123,\"activity\":\"Synthetic activity\"},"
                + "{\"id\":8,\"deactivated\":\"deleted\"}]}}"));
        Result result = new Result(); repository.load(result); await(() -> result.callbacks == 1);
        okhttp3.Request request = requests.get(0);
        assertEquals("GET", request.method()); assertEquals("/method/groups.get", request.url().encodedPath());
        assertEquals("41", request.url().queryParameter("user_id"));
        assertEquals("1000", request.url().queryParameter("count")); assertEquals("0", request.url().queryParameter("offset"));
        assertEquals("1", request.url().queryParameter("extended"));
        assertNull(request.url().queryParameter("order")); assertNull(request.url().queryParameter("filter"));
        assertEquals("members_count,activity,screen_name,verified", request.url().queryParameter("fields"));
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        for (int index = 0; index < request.url().querySize(); index++) params.put(request.url().queryParameterName(index), request.url().queryParameterValue(index));
        String signature = params.remove("sig");
        StringBuilder raw = new StringBuilder("/method/groups.get?");
        for (Map.Entry<String, String> field : params.entrySet()) { if (raw.charAt(raw.length() - 1) != '?') raw.append('&'); raw.append(field.getKey()).append('=').append(field.getValue()); }
        assertEquals(md5(raw + "synthetic-secret"), signature);
        assertTrue(result.main); assertEquals(1, result.rows.size());
        assertEquals("Synthetic Community", result.rows.get(0).name); assertEquals("file:///avatar", result.rows.get(0).photo);
        assertTrue(result.rows.get(0).isManaged()); assertEquals(1, result.rows.get(0).adminLevel);
        assertEquals(123, result.rows.get(0).membersCount); assertEquals("page", result.rows.get(0).type);
        try { result.rows.clear(); fail("Repository result must be immutable"); } catch (UnsupportedOperationException expected) { }
    }

    @Test public void completePagingUsesRawOffsetsAndPacesPagesWithoutPartialSuccess() throws Exception {
        create(chain -> {
            if (chain.request().url().queryParameter("offset").equals("0"))
                return response(chain, 200, "{\"response\":{\"count\":5,\"items\":[{\"id\":7,\"name\":\"First\"},null,{\"id\":7,\"name\":\"Duplicate\"}]}}");
            return response(chain, 200, "{\"response\":{\"count\":5,\"items\":[{\"id\":8},{\"id\":9}]}}");
        });
        Result result = new Result(); GroupsRepository.Request operation = repository.load(result);
        awaitAcceptedPage(operation, 3);
        assertEquals(0, result.callbacks); assertEquals(1, calls.get());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(349)); assertEquals(1, calls.get());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1)); await(() -> result.callbacks == 1);
        assertEquals("3", requests.get(1).url().queryParameter("offset"));
        assertEquals(3, result.rows.size()); assertEquals(7, result.rows.get(0).id);
        assertEquals("First", result.rows.get(0).name); assertEquals(8, result.rows.get(1).id); assertEquals(9, result.rows.get(2).id);
    }

    @Test public void pageFailurePublishesErrorInsteadOfAnIncompleteCommunityList() throws Exception {
        create(chain -> {
            if (chain.request().url().queryParameter("offset").equals("0"))
                return response(chain, 200, "{\"response\":{\"count\":2,\"items\":[{\"id\":7}]}}");
            throw new IOException("SYNTHETIC_PRIVATE_FAILURE");
        });
        Result result = new Result(); GroupsRepository.Request operation = repository.load(result);
        awaitAcceptedPage(operation, 1);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));
        await(() -> result.callbacks == 1);
        assertNull(result.rows); assertEquals(GroupsError.Kind.NETWORK, result.error.kind);
        assertNull(result.error.getCause()); assertFalse(result.error.toString().contains("PRIVATE")); assertEquals(2, calls.get());
    }

    @Test public void cancelOrCloseBetweenPagesRemovesDelayedReadAndSuppressesCallbacks() throws Exception {
        create(chain -> response(chain, 200, "{\"response\":{\"count\":2,\"items\":[{\"id\":7}]}}"));
        Result result = new Result(); GroupsRepository.Request operation = repository.load(result);
        awaitAcceptedPage(operation, 1);
        operation.cancel(); repository.close();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
        assertEquals(1, calls.get()); assertEquals(0, result.callbacks);
    }

    @Test public void returningToSameCredentialsDoesNotDeliverAnEarlierSession() throws Exception {
        session.set(new VkGroupsRepository.Snapshot("synthetic-token", "synthetic-secret", "41", 1));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        create(chain -> {
            entered.countDown();
            try { if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("Synthetic timeout"); }
            catch (InterruptedException ignored) { throw new IOException("Synthetic interruption"); }
            return response(chain, 200, "{\"response\":{\"count\":1,\"items\":[{\"id\":7}]}}");
        });
        Result result = new Result(); repository.load(result); shadowOf(Looper.getMainLooper()).idle();
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        session.set(new VkGroupsRepository.Snapshot("other", "", "42", 2));
        session.set(new VkGroupsRepository.Snapshot("synthetic-token", "synthetic-secret", "41", 3));
        release.countDown(); await(() -> result.callbacks == 1);
        assertNull(result.rows); assertEquals(GroupsError.Kind.AUTH, result.error.kind);
        assertEquals(1, calls.get());
    }

    @Test public void changedSessionCannotDeliverAnOldAccountsCommunityRows() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        create(chain -> {
            entered.countDown();
            try { if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("Synthetic timeout"); }
            catch (InterruptedException ignored) { throw new IOException("Synthetic interruption"); }
            return response(chain, 200, "{\"response\":{\"count\":1,\"items\":[{\"id\":7}]}}");
        });
        Result result = new Result(); repository.load(result); shadowOf(Looper.getMainLooper()).idle();
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        session.set(new VkGroupsRepository.Snapshot("other-synthetic-token", "", "42")); release.countDown();
        await(() -> result.callbacks == 1);
        assertNull(result.rows); assertEquals(GroupsError.Kind.AUTH, result.error.kind);
        assertEquals(1, calls.get());
    }

    @Test public void classificationAndMalformedResponsesNeverLeakPayloadOrAutomaticallyRetry() throws Exception {
        String[] bodies = {"{\"error\":{\"error_code\":9,\"error_msg\":\"SYNTHETIC_PRIVATE\"}}", "{}", "{\"response\":{\"count\":2,\"items\":[]}}", "{}",
                "{\"error\":{\"error_code\":260}}", "{\"error\":{\"error_code\":5}}"};
        int[] codes = {200, 200, 200, 429, 200, 200};
        GroupsError.Kind[] kinds = {GroupsError.Kind.RATE_LIMITED, GroupsError.Kind.MALFORMED, GroupsError.Kind.MALFORMED,
                GroupsError.Kind.RATE_LIMITED, GroupsError.Kind.ACCESS, GroupsError.Kind.AUTH};
        for (int index = 0; index < bodies.length; index++) {
            if (repository != null) repository.close();
            final int item = index;
            create(chain -> response(chain, codes[item], bodies[item]));
            Result result = new Result(); repository.load(result); await(() -> result.callbacks == 1);
            assertEquals(kinds[index], result.error.kind); assertNull(result.error.getCause());
            assertFalse(result.error.toString().contains("PRIVATE"));
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
            assertEquals(index + 1, calls.get());
        }
    }

    @Test public void missingSessionNeverReachesTransportAndOversizedValidJsonIsRejected() throws Exception {
        create(chain -> response(chain, 200, "{\"response\":{\"count\":0,\"items\":[]},\"ignored\":\"" + "x".repeat(4 * 1024 * 1024) + "\"}"));
        session.set(new VkGroupsRepository.Snapshot("", "", "41"));
        Result unauthorized = new Result(); repository.load(unauthorized); await(() -> unauthorized.callbacks == 1);
        assertEquals(GroupsError.Kind.AUTH, unauthorized.error.kind); assertEquals(0, calls.get());
        session.set(new VkGroupsRepository.Snapshot("synthetic-token", "", "41"));
        Result oversized = new Result(); repository.load(oversized); await(() -> oversized.callbacks == 1);
        assertEquals(GroupsError.Kind.MALFORMED, oversized.error.kind); assertEquals(1, calls.get());
    }

    private void create(Interceptor transport) {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            calls.incrementAndGet(); requests.add(chain.request()); return transport.intercept(chain);
        }).build();
        repository = new VkGroupsRepository(client, HttpUrl.get("https://synthetic-groups.invalid/"), session::get);
    }
    private static Response response(Interceptor.Chain chain, int code, String text) {
        ResponseBody body = ResponseBody.create(text, MediaType.get("application/json"));
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Synthetic").body(body).build();
    }
    private static void awaitAcceptedPage(GroupsRepository.Request operation, int consumed) throws Exception {
        // Synchronize with accepted main-thread delivery, not body close (which precedes JSON parsing).
        // The actual next offset and 350 ms delay are asserted through captured HTTP requests.
        await(() -> ReflectionHelpers.<Integer>getField(operation, "offset") == consumed);
    }
    private static String md5(String input) throws Exception {
        StringBuilder output = new StringBuilder();
        for (byte value : MessageDigest.getInstance("MD5").digest(input.getBytes(StandardCharsets.UTF_8))) output.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return output.toString();
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(1); }
        assertTrue("Expected bounded synthetic callback", condition.getAsBoolean());
    }
    private static final class Result implements GroupsRepository.Callback<List<Group>> {
        List<Group> rows; GroupsError error; int callbacks; boolean main;
        @Override public void success(List<Group> rows) { this.rows = rows; complete(); }
        @Override public void error(GroupsError error) { this.error = error; complete(); }
        private void complete() { callbacks++; main = Looper.myLooper() == Looper.getMainLooper(); }
    }
}
