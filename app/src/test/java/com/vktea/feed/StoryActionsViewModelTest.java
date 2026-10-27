package com.vktea.feed;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Looper;
import com.vktea.auth.Session;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.FormBody;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** No connection leaves this process: the interceptor returns synthetic responses. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class StoryActionsViewModelTest {
    private final Application app = RuntimeEnvironment.getApplication();
    private final AtomicReference<okhttp3.Request> request = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger events = new AtomicInteger();
    private final AtomicReference<String> result = new AtomicReference<>();
    private StoryActionsViewModel model;
    @After public void clean() {
        if (model != null) model.onCleared();
        Session.save(app, "", "", "");
        shadowOf(Looper.getMainLooper()).idle();
    }
    private void create(Interceptor transport) {
        Session.save(app, "synthetic-token", "456", "synthetic-secret");
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            request.set(chain.request()); calls.incrementAndGet(); return transport.intercept(chain);
        }).build();
        model = new StoryActionsViewModel(app, client);
        model.events.observeForever(event -> { result.set(event.message); events.incrementAndGet(); });
    }
    private static JSONObject story(long id) throws Exception {
        return new JSONObject().put("owner_id", 123).put("id", id).put("access_key", "synthetic-key").put("can_reply", true);
    }
    private static Response response(Interceptor.Chain chain, String body) {
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
                .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
    }
    private static LinkedHashMap<String, String> form(okhttp3.Request request) {
        FormBody body = (FormBody) request.body(); LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < body.size(); i++) values.put(body.name(i), body.value(i)); return values;
    }
    private void awaitEvent() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (events.get() == 0 && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(2); }
        assertEquals("No synthetic callback", 1, events.get());
    }
    @Test public void markSeenIsSinglePostAndFailureDoesNotTriggerAnotherAttempt() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        create(chain -> { started.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new IOException(e); }
            return response(chain, "{\"error\":{\"error_code\":15}}"); });
        JSONObject story = story(701); model.markSeen(story);
        assertTrue(started.await(5, TimeUnit.SECONDS)); model.markSeen(story);
        assertEquals(1, calls.get()); assertEquals("POST", request.get().method());
        assertEquals("/method/stories.markSeen", request.get().url().encodedPath()); assertNull(request.get().url().query());
        LinkedHashMap<String, String> body = form(request.get());
        assertEquals("123", body.get("owner_id")); assertEquals("701", body.get("story_id"));
        assertEquals("synthetic-key", body.get("access_key")); assertEquals("list", body.get("source"));
        release.countDown(); awaitEvent(); assertTrue(result.get().contains("Не удалось подтвердить"));
        model.markSeen(story); assertEquals(1, calls.get());
    }
    @Test public void replySingleFlightSendsExplicitMessageAndStoryToAuthor() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        create(chain -> { started.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new IOException(e); }
            return response(chain, "{\"response\":42}"); });
        JSONObject story = story(702); assertTrue(model.sendReply(story, "Привет & тест"));
        assertTrue(started.await(5, TimeUnit.SECONDS)); assertTrue(model.isPending(story));
        assertFalse(model.sendReply(story, "Привет & тест")); assertEquals(1, calls.get());
        assertEquals("/method/messages.send", request.get().url().encodedPath()); assertNull(request.get().url().query());
        LinkedHashMap<String, String> body = form(request.get());
        assertEquals("123", body.get("peer_id")); assertTrue(Integer.parseInt(body.get("random_id")) > 0);
        assertEquals("https://vk.com/story123_702\nПривет & тест", body.get("message")); assertFalse(body.containsKey("access_key"));
        release.countDown(); awaitEvent(); assertEquals("Сообщение отправлено", result.get()); assertFalse(model.isPending(story));
    }
    @Test public void networkFailureIsUnknownAndBlocksRepeatedIdenticalSend() throws Exception {
        create(chain -> { throw new IOException("synthetic"); });
        JSONObject story = story(703); assertTrue(model.sendReply(story, "synthetic reply")); awaitEvent();
        assertTrue(result.get().contains("Результат неизвестен"));
        assertFalse(model.sendReply(story, "synthetic reply")); assertEquals(1, calls.get());
    }
    @Test public void malformedSuccessIsUnknownAndBlocksRepeatedSend() throws Exception {
        create(chain -> response(chain, "{\"unexpected\":true}"));
        JSONObject story = story(706); assertTrue(model.sendReply(story, "synthetic reply")); awaitEvent();
        assertTrue(result.get().contains("Результат неизвестен"));
        assertFalse(model.sendReply(story, "synthetic reply")); assertEquals(1, calls.get());
    }
    @Test public void internalVkErrorIsUnknownAndGuardSurvivesNewViewModel() throws Exception {
        create(chain -> response(chain, "{\"error\":{\"error_code\":10}}"));
        JSONObject story = story(708); assertTrue(model.sendReply(story, "synthetic private reply")); awaitEvent();
        assertTrue(result.get().contains("Результат неизвестен"));
        String guard = app.getSharedPreferences("story_send_guard", 0).getAll().toString();
        assertFalse(guard.contains("synthetic private reply")); assertFalse(guard.contains("synthetic-token"));
        model.onCleared();
        StoryActionsViewModel reopened = new StoryActionsViewModel(app, new OkHttpClient.Builder().addInterceptor(chain -> { fail("Unexpected network request"); throw new IOException(); }).build());
        assertFalse(reopened.sendReply(story, "synthetic private reply")); reopened.onCleared();
        assertEquals(1, calls.get());
    }
    @Test public void sessionChangedBeforeDispatchBlocksRequest() throws Exception {
        create(chain -> response(chain, "{\"response\":1}"));
        Session.save(app, "another-token", "999", "another-secret");
        JSONObject story = story(709); assertFalse(model.sendReply(story, "synthetic reply")); model.markSeen(story);
        assertEquals(0, calls.get());
    }
    @Test public void unavailablePermissionAndAlreadySeenStoryDoNotSend() throws Exception {
        create(chain -> response(chain, "{\"response\":1}"));
        JSONObject story = story(707).put("can_reply", false).put("seen", true);
        assertFalse(model.sendReply(story, "synthetic reply")); model.markSeen(story);
        assertEquals(0, calls.get()); assertNull(request.get());
    }
    @Test public void vkErrorIsDefiniteAndAllowsExplicitRetry() throws Exception {
        create(chain -> response(chain, "{\"error\":{\"error_code\":7}}"));
        JSONObject story = story(704); assertTrue(model.sendReply(story, "synthetic reply")); awaitEvent();
        assertEquals("Не удалось отправить сообщение", result.get()); assertEquals(1, calls.get());
        assertTrue(model.sendReply(story, "synthetic reply"));
    }
    @Test public void accountChangeBeforeAndDuringSendCannotReportSuccessOrDuplicate() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        create(chain -> { started.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new IOException(e); }
            return response(chain, "{\"response\":42}"); });
        JSONObject story = story(705); assertTrue(model.sendReply(story, "synthetic reply"));
        assertTrue(started.await(5, TimeUnit.SECONDS)); Session.save(app, "another-token", "999", "another-secret");
        assertFalse(model.sameSession()); assertFalse(model.sendReply(story, "second reply"));
        release.countDown(); awaitEvent(); assertTrue(result.get().contains("Результат неизвестен"));
        Session.save(app, "synthetic-token", "456", "synthetic-secret");
        assertFalse(model.sendReply(story, "synthetic reply")); assertEquals(1, calls.get());
    }
}
