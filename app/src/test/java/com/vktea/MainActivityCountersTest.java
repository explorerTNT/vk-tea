package com.vktea;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.ComponentName;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import com.vktea.api.VKApi;
import com.vktea.audio.AudioPlaybackService;
import com.vktea.auth.Session;
import com.vktea.updates.UpdateChecker;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.List;

/** Deliberately reordered synthetic callbacks; no real API, updates or account. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = {MainActivityCountersTest.Api.class, MainActivityCountersTest.Updates.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class MainActivityCountersTest {
    private ActivityController<MainActivity> controller;
    private Context app;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        Session.save(app, "synthetic-first-session", "1");
        Api.profiles.clear();
        Api.counters.clear();
        shadowOf(RuntimeEnvironment.getApplication()).declareComponentUnbindable(
                new ComponentName(app, AudioPlaybackService.class));
        controller = Robolectric.buildActivity(MainActivity.class).setup().visible();
        idle();
        assertEquals(1, Api.profiles.size());
        assertEquals(1, Api.counters.size());
    }

    @After public void close() {
        if (controller != null) controller.close();
        app.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void profileCountersRemainVisibleWhenDedicatedRequestFails() throws Exception {
        Api.profiles.get(0).success(profile(2, 3));
        Api.counters.get(0).error(new Exception("Synthetic offline failure"));
        idle();
        assertBadge("5");
    }

    @Test public void lateProfileDoesNotOverwriteFresherDedicatedCounters() throws Exception {
        Api.counters.get(0).success(counters(10, 2));
        Api.profiles.get(0).success(profile(2, 3));
        idle();
        assertBadge("12");
    }

    @Test public void dedicatedZeroCountersHideEarlierProfileFallback() throws Exception {
        Api.profiles.get(0).success(profile(2, 3));
        idle();
        assertBadge("5");
        Api.counters.get(0).success(counters(0, 0));
        idle();
        assertEquals(View.GONE, badge().getVisibility());
    }

    @Test public void oldSessionResponsesCannotUpdateBadgeAndPendingRefreshUsesNewSession() throws Exception {
        Session.save(app, "synthetic-second-session", "2");
        ReflectionHelpers.callInstanceMethod(controller.get(), "onCounterEvent");
        Api.profiles.get(0).success(profile(7, 8));
        Api.counters.get(0).success(counters(10, 10));
        idle();
        assertEquals(View.GONE, badge().getVisibility());
        assertEquals(2, Api.counters.size());
        Api.counters.get(1).success(counters(1, 2));
        idle();
        assertBadge("3");
    }

    @Test public void stoppedActivityDiscardsEarlierReplyAndRefreshesWhenStartedAgain() throws Exception {
        controller.pause().stop();
        controller.start().resume();
        Api.counters.get(0).success(counters(7, 8));
        idle();
        assertEquals(View.GONE, badge().getVisibility());
        assertEquals(2, Api.counters.size());
        assertTrue("counter subscription should be active after restart",
                ReflectionHelpers.<Boolean>getField(controller.get(), "countersStarted"));
        assertEquals("counter request must belong to current session", Session.generation(),
                (long) ReflectionHelpers.<Long>getField(controller.get(), "countersSessionGeneration"));
        assertEquals("callback lifecycle must match current lifecycle",
                (long) ReflectionHelpers.<Long>getField(controller.get(), "countersLifecycleGeneration"),
                (long) ReflectionHelpers.<Long>getField(Api.counters.get(1), "val$requestLifecycleGeneration"));
        assertEquals("callback generation must match current request",
                (long) ReflectionHelpers.<Long>getField(controller.get(), "countersRequestGeneration"),
                (long) ReflectionHelpers.<Long>getField(Api.counters.get(1), "val$requestGeneration"));
        assertEquals("callback session must match current session", Session.generation(),
                (long) ReflectionHelpers.<Long>getField(Api.counters.get(1), "val$requestSessionGeneration"));
        Api.counters.get(1).success(counters(2, 2));
        idle();
        assertBadge("4");
    }

    @Test public void logoutDoesNotRequestCountersForAnEmptySession() {
        Session.clear(app);
        idle();
        ReflectionHelpers.callInstanceMethod(controller.get(), "onCounterEvent");
        idle();
        assertEquals(1, Api.counters.size());
        assertEquals(View.GONE, badge().getVisibility());
    }

    private TextView badge() { return controller.get().findViewById(R.id.toolbar_badge); }
    private void assertBadge(String value) {
        assertEquals(View.VISIBLE, badge().getVisibility());
        assertEquals(value, badge().getText().toString());
    }
    private static void idle() { shadowOf(Looper.getMainLooper()).idle(); }
    private static JSONObject profile(int messages, int notifications) throws Exception {
        return new JSONObject("{\"response\":[{\"id\":1,\"first_name\":\"Synthetic\",\"counters\":{" +
                "\"messages\":" + messages + ",\"notifications\":" + notifications + "}}]}");
    }
    private static JSONObject counters(int messages, int notifications) throws Exception {
        return new JSONObject("{\"response\":{\"messages\":" + messages +
                ",\"notifications\":" + notifications + "}}");
    }

    @Implements(value = VKApi.class, isInAndroidSdk = false)
    public static class Api {
        static final List<VKApi.Callback> profiles = new ArrayList<>();
        static final List<VKApi.Callback> counters = new ArrayList<>();
        @Implementation protected static void get(Context context, String method, String args, VKApi.Callback callback) {
            if ("users.get".equals(method) && args != null && args.contains("counters")) profiles.add(callback);
            else if ("account.getCounters".equals(method)) counters.add(callback);
            else callback.error(new Exception("Synthetic offline response"));
        }
    }

    @Implements(value = UpdateChecker.class, isInAndroidSdk = false)
    public static class Updates {
        @Implementation protected static void check(Context context, UpdateChecker.Callback callback) {
            callback.onUpToDate();
        }
    }
}
