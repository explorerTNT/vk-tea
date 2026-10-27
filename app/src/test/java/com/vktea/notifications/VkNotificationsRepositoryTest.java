package com.vktea.notifications;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Synthetic in-memory responses only: no VK endpoint, socket, or account. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class VkNotificationsRepositoryTest {
    private final AtomicReference<VkNotificationsRepository.Snapshot> session = new AtomicReference<>(
            new VkNotificationsRepository.Snapshot("synthetic-token", "synthetic-secret", "41"));
    private final List<okhttp3.Request> requests = new CopyOnWriteArrayList<>();
    private VkNotificationsRepository repository;

    @After public void close() {
        if (repository != null) repository.close();
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void pageReadCarriesOpaqueCursorAndOnlyOneRequestRuns() throws Exception {
        create(chain -> response(chain, 200,
                "{\"response\":{\"items\":[],\"profiles\":[],\"groups\":[],\"last_viewed\":7,\"next_from\":\"next/+?\"}}"));
        Result result = new Result();
        repository.load("first/+?", result);
        await(() -> result.calls == 1);
        assertNull(result.error);
        assertNotNull(result.page);
        assertEquals("next/+?", result.page.nextFrom);
        assertEquals(1, requests.size());
        assertEquals("GET", requests.get(0).method());
        assertEquals("/method/notifications.get", requests.get(0).url().encodedPath());
        assertEquals("first/+?", requests.get(0).url().queryParameter("start_from"));
        assertEquals("synthetic-token", requests.get(0).url().queryParameter("access_token"));
        assertTrue(result.main);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertEquals(1, requests.size());
    }

    @Test public void rateLimitAuthAndMalformedFailuresAreSanitizedAndNotRetried() throws Exception {
        String[] bodies = {"{\"error\":{\"error_code\":6,\"error_msg\":\"SYNTHETIC_PRIVATE\"}}",
                "{\"error\":{\"error_code\":5,\"error_msg\":\"SYNTHETIC_PRIVATE\"}}", "{}", "{}"};
        int[] codes = {200, 200, 200, 429};
        NotificationsError.Kind[] expected = {NotificationsError.Kind.RATE_LIMITED,
                NotificationsError.Kind.AUTH, NotificationsError.Kind.MALFORMED, NotificationsError.Kind.RATE_LIMITED};
        for (int i = 0; i < bodies.length; i++) {
            if (repository != null) repository.close();
            final int index = i;
            create(chain -> response(chain, codes[index], bodies[index]));
            Result result = new Result(); repository.load("", result);
            await(() -> result.calls == 1);
            assertNull(result.page); assertEquals(expected[index], result.error.kind);
            assertNull(result.error.getCause());
            assertFalse(result.error.toString().contains("SYNTHETIC_PRIVATE"));
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
            assertEquals(index + 1, requests.size());
        }
    }

    @Test public void changedSessionCannotDeliverPreviousAccountsEvents() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        create(chain -> {
            entered.countDown();
            try { if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("Synthetic timeout"); }
            catch (InterruptedException ignored) { throw new IOException("Synthetic interruption"); }
            return response(chain, 200, "{\"response\":{\"items\":[],\"profiles\":[],\"groups\":[]}}");
        });
        Result result = new Result(); repository.load("", result);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        session.set(new VkNotificationsRepository.Snapshot("another-token", "", "42"));
        release.countDown(); await(() -> result.calls == 1);
        assertNull(result.page); assertEquals(NotificationsError.Kind.AUTH, result.error.kind);
        assertEquals(1, requests.size());
    }

    @Test public void cancelSuppressesInFlightDelivery() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        create(chain -> {
            entered.countDown();
            try { if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("Synthetic timeout"); }
            catch (InterruptedException ignored) { throw new IOException("Synthetic interruption"); }
            return response(chain, 200, "{\"response\":{\"items\":[],\"profiles\":[],\"groups\":[]}}");
        });
        Result result = new Result(); NotificationsRepository.Request request = repository.load("", result);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        request.cancel(); release.countDown(); repository.close();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertEquals(0, result.calls);
    }

    private void create(Interceptor transport) {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.add(chain.request()); return transport.intercept(chain);
        }).build();
        repository = new VkNotificationsRepository(client, HttpUrl.get("https://synthetic-notifications.invalid/"), session::get);
    }
    private static Response response(Interceptor.Chain chain, int code, String text) {
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
                .message("Synthetic").body(ResponseBody.create(text, MediaType.get("application/json"))).build();
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < until) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(1);
        }
        assertTrue("Expected bounded synthetic callback", condition.getAsBoolean());
    }
    private static final class Result implements NotificationsRepository.Callback<EventPage> {
        EventPage page; NotificationsError error; int calls; boolean main;
        @Override public void success(EventPage value) { page = value; complete(); }
        @Override public void error(NotificationsError value) { error = value; complete(); }
        private void complete() { calls++; main = Looper.myLooper() == Looper.getMainLooper(); }
    }
}
