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

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class StoryReactionsViewModelTest {
    private final Application app = RuntimeEnvironment.getApplication();
    private final AtomicReference<okhttp3.Request> request = new AtomicReference<>();
    private final AtomicReference<StoryReactionsViewModel.Result> result = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private StoryReactionsViewModel model;
    @After public void cleanup() {
        if (model != null) model.onCleared();
        Session.save(app, "", "", "");
        shadowOf(Looper.getMainLooper()).idle();
        app.getSharedPreferences("story_reaction_guard", 0).edit().clear().commit();
    }
    private void create(Interceptor interceptor) {
        Session.save(app, "synthetic-token", "456", "synthetic-secret");
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            calls.incrementAndGet(); request.set(chain.request()); return interceptor.intercept(chain);
        }).build();
        model = new StoryReactionsViewModel(app, client);
        model.events.observeForever(event -> result.set(event.result));
    }
    private static JSONObject story(int id) throws Exception {
        return new JSONObject().put("owner_id", -123).put("id", id).put("access_key", "synthetic-key").put("can_like", true);
    }
    private static Response response(Interceptor.Chain chain, String body) {
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
                .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
    }
    private static String field(FormBody body, String name) {
        for (int i = 0; i < body.size(); i++) if (name.equals(body.name(i))) return body.value(i);
        return null;
    }
    private void awaitResult() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (result.get() == null && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(2); }
        assertNotNull("No synthetic callback", result.get());
    }
    @Test public void reactionUsesSinglePostAndReportsOnlyLikeConfirmed() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        create(chain -> { started.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new IOException(e); }
            return response(chain, "{\"response\":{\"likes\":1}}"); });
        JSONObject story = story(1901); assertTrue(model.send(story, 2)); assertTrue(started.await(5, TimeUnit.SECONDS));
        assertTrue(model.isPending(story)); assertFalse(model.send(story, 3));
        assertEquals("POST", request.get().method()); assertEquals("/method/likes.add", request.get().url().encodedPath());
        assertNull(request.get().url().query());
        FormBody form = (FormBody) request.get().body();
        assertEquals("story", field(form, "type")); assertEquals("-123", field(form, "owner_id"));
        assertEquals("1901", field(form, "item_id")); assertEquals("2", field(form, "reaction_id"));
        assertEquals("synthetic-key", field(form, "access_key")); assertNotNull(field(form, "sig"));
        release.countDown(); awaitResult(); assertEquals(StoryReactionsViewModel.Result.APPLIED, result.get());
        assertFalse(model.isPending(story)); assertEquals(2, calls.get());
        assertEquals("/method/stories.getById", request.get().url().encodedPath());
        assertEquals("-123_1901_synthetic-key", field((FormBody) request.get().body(), "stories"));
    }
    @Test public void readBackConfirmsOnlyExactStoryAndReactionId() throws Exception {
        create(chain -> {
            if (chain.request().url().encodedPath().endsWith("likes.add")) return response(chain, "{\"response\":{\"likes\":4}}");
            return response(chain, "{\"response\":{\"items\":[{\"owner_id\":-123,\"id\":1910,\"user_reaction_id\":6}]}}");
        });
        assertTrue(model.send(story(1910), 6)); awaitResult();
        assertEquals(StoryReactionsViewModel.Result.REACTION_CONFIRMED, result.get()); assertEquals(2, calls.get());
    }
    @Test public void readBackDifferentReactionDoesNotConfirmChosenId() throws Exception {
        create(chain -> {
            if (chain.request().url().encodedPath().endsWith("likes.add")) return response(chain, "{\"response\":{\"likes\":4}}");
            return response(chain, "{\"response\":{\"items\":[{\"owner_id\":-123,\"id\":1911,\"user_reaction_id\":3}]}}");
        });
        assertTrue(model.send(story(1911), 6)); awaitResult();
        assertEquals(StoryReactionsViewModel.Result.APPLIED, result.get()); assertEquals(2, calls.get());
    }
    @Test public void unavailableReadBackKeepsConfirmedLikeWithoutRetryingMutation() throws Exception {
        create(chain -> {
            if (chain.request().url().encodedPath().endsWith("likes.add")) return response(chain, "{\"response\":{\"likes\":4}}");
            throw new IOException("synthetic");
        });
        JSONObject story = story(1912); assertTrue(model.send(story, 5)); awaitResult();
        assertEquals(StoryReactionsViewModel.Result.APPLIED, result.get()); assertFalse(model.isUncertain(story)); assertEquals(2, calls.get());
    }
    @Test public void responseForAnotherStoryDoesNotConfirmChosenId() throws Exception {
        create(chain -> {
            if (chain.request().url().encodedPath().endsWith("likes.add")) return response(chain, "{\"response\":{\"likes\":4}}");
            return response(chain, "{\"response\":{\"items\":[{\"owner_id\":-123,\"id\":9999,\"user_reaction_id\":2}]}}");
        });
        assertTrue(model.send(story(1913), 2)); awaitResult();
        assertEquals(StoryReactionsViewModel.Result.APPLIED, result.get()); assertEquals(2, calls.get());
    }
    @Test public void deleteIncludesNoReactionIdAndAcceptsZeroLikes() throws Exception {
        create(chain -> response(chain, "{\"response\":{\"likes\":0}}"));
        assertTrue(model.send(story(1902), null)); awaitResult();
        assertEquals(StoryReactionsViewModel.Result.REMOVED, result.get());
        assertEquals("/method/likes.delete", request.get().url().encodedPath());
        assertNull(field((FormBody) request.get().body(), "reaction_id"));
    }
    @Test public void addWithZeroLikesIsUnknownAndDoesNotReadBack() throws Exception {
        create(chain -> response(chain, "{\"response\":{\"likes\":0}}"));
        JSONObject story = story(1916); assertTrue(model.send(story, 2)); awaitResult();
        assertEquals(StoryReactionsViewModel.Result.UNKNOWN, result.get());
        assertTrue(model.isUncertain(story)); assertFalse(model.send(story, 2)); assertEquals(1, calls.get());
    }
    @Test public void deleteWithNegativeLikesIsUnknown() throws Exception {
        create(chain -> response(chain, "{\"response\":{\"likes\":-1}}"));
        JSONObject story = story(1917); assertTrue(model.send(story, null)); awaitResult();
        assertEquals(StoryReactionsViewModel.Result.UNKNOWN, result.get()); assertTrue(model.isUncertain(story));
    }
    @Test public void failureAndMalformedResponseStayUnknownAndBlockFurtherMutation() throws Exception {
        create(chain -> response(chain, "{\"response\":{}}"));
        JSONObject story = story(1903); assertTrue(model.send(story, 0)); awaitResult();
        assertEquals(StoryReactionsViewModel.Result.UNKNOWN, result.get());
        assertTrue(model.isUncertain(story)); assertFalse(model.send(story, null)); assertEquals(1, calls.get());
    }
    @Test public void networkFailureIsUnknownAndIsNeverRetried() throws Exception {
        create(chain -> { throw new IOException("synthetic"); });
        JSONObject story = story(1907); assertTrue(model.send(story, 1)); awaitResult();
        assertEquals(StoryReactionsViewModel.Result.UNKNOWN, result.get());
        assertFalse(model.send(story, 1)); assertEquals(1, calls.get());
    }
    @Test public void internalErrorAndMissingLikesDoNotReportSuccess() throws Exception {
        create(chain -> response(chain, "{\"error\":{\"error_code\":10}}"));
        assertTrue(model.send(story(1908).put("can_like", 1), 3)); awaitResult();
        assertEquals(StoryReactionsViewModel.Result.UNKNOWN, result.get()); assertEquals(1, calls.get());
    }
    @Test public void unknownOutcomeSurvivesNewViewModelWithoutPrivateData() throws Exception {
        create(chain -> { throw new IOException("synthetic"); });
        JSONObject story = story(1914); assertTrue(model.send(story, 2)); awaitResult();
        String stored = app.getSharedPreferences("story_reaction_guard", 0).getAll().toString();
        assertFalse(stored.contains("synthetic-token")); assertFalse(stored.contains("synthetic-secret"));
        assertFalse(stored.contains("synthetic-key")); assertFalse(stored.contains("-123"));
        model.onCleared();
        StoryReactionsViewModel reopened = new StoryReactionsViewModel(app, new OkHttpClient.Builder().addInterceptor(chain -> {
            fail("Unexpected request"); throw new IOException();
        }).build());
        assertTrue(reopened.isUncertain(story)); assertFalse(reopened.send(story, 2));
        assertFalse(reopened.send(story, null)); reopened.onCleared(); assertEquals(1, calls.get());
    }
    @Test public void explicitAcknowledgementClearsUnknownWithoutSendingAnything() throws Exception {
        create(chain -> { throw new IOException("synthetic"); });
        JSONObject story = story(1918); assertTrue(model.send(story, 2)); awaitResult();
        assertTrue(model.isUncertain(story)); assertTrue(model.acknowledgeUnknown(story));
        assertFalse(model.isUncertain(story)); assertFalse(model.acknowledgeUnknown(story)); assertEquals(1, calls.get());
        StoryReactionsViewModel reopened = new StoryReactionsViewModel(app, new OkHttpClient());
        assertFalse(reopened.isUncertain(story)); reopened.onCleared();
    }
    @Test public void acknowledgementIsBlockedInFlightAndAfterSessionChange() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        create(chain -> { started.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new IOException(e); }
            throw new IOException("synthetic"); });
        JSONObject story = story(1919); assertTrue(model.send(story, 6)); assertTrue(started.await(5, TimeUnit.SECONDS));
        assertFalse(model.acknowledgeUnknown(story));
        release.countDown(); awaitResult(); assertTrue(model.isUncertain(story));
        Session.save(app, "different-token", "999", "different-secret");
        assertFalse(model.acknowledgeUnknown(story)); assertEquals(1, calls.get());
        Session.save(app, "synthetic-token", "456", "synthetic-secret");
        assertTrue(model.isUncertain(story)); assertTrue(model.acknowledgeUnknown(story));
    }
    @Test public void markerExistsBeforeResponseAndSurvivesSimulatedProcessRestart() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        create(chain -> { started.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new IOException(e); }
            return response(chain, "{\"response\":{\"likes\":1}}"); });
        JSONObject story = story(1915); assertTrue(model.send(story, 1)); assertTrue(started.await(5, TimeUnit.SECONDS));
        assertTrue(app.getSharedPreferences("story_reaction_guard", 0).getAll().size() >= 2);
        // Drop only process-local state as process death would; preferences remain intact.
        java.lang.reflect.Field field = StoryReactionsViewModel.class.getDeclaredField("pending"); field.setAccessible(true);
        ((java.util.Set<?>) field.get(null)).clear();
        StoryReactionsViewModel reopened = new StoryReactionsViewModel(app, new OkHttpClient.Builder().addInterceptor(chain -> {
            fail("Unexpected request"); throw new IOException();
        }).build());
        assertTrue(reopened.isUncertain(story)); assertFalse(reopened.send(story, 1));
        model.onCleared(); release.countDown(); awaitResult(); reopened.onCleared(); assertEquals(1, calls.get());
    }
    @Test public void definiteErrorAllowsExplicitRetry() throws Exception {
        create(chain -> response(chain, "{\"error\":{\"error_code\":7}}"));
        JSONObject story = story(1904); assertTrue(model.send(story, 5)); awaitResult();
        assertEquals(StoryReactionsViewModel.Result.REJECTED, result.get()); assertTrue(model.send(story, 5));
    }
    @Test public void permissionInvalidIdAndSessionChangeBlockRequest() throws Exception {
        create(chain -> response(chain, "{\"response\":{\"likes\":1}}"));
        JSONObject story = story(1905); assertFalse(model.send(story, 4));
        story.put("can_like", false); assertFalse(model.send(story, 1));
        story.remove("can_like"); assertFalse(model.send(story, 1));
        story.put("can_like", true); Session.save(app, "different", "456", "synthetic-secret");
        assertFalse(model.send(story, 1)); assertEquals(0, calls.get());
    }
    @Test public void accountChangeDuringRequestCannotReportSuccess() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        create(chain -> { started.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new IOException(e); }
            return response(chain, "{\"response\":{\"likes\":1}}"); });
        JSONObject story = story(1906); assertTrue(model.send(story, 6)); assertTrue(started.await(5, TimeUnit.SECONDS));
        Session.save(app, "different", "456", "synthetic-secret"); release.countDown(); awaitResult();
        assertEquals(StoryReactionsViewModel.Result.UNKNOWN, result.get()); assertEquals(1, calls.get());
    }
}
