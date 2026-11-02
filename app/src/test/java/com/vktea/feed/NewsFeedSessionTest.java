package com.vktea.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.vktea.R;
import com.vktea.api.VKApi;
import com.vktea.auth.Session;
import com.vktea.msg.MessageLongPoll;

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
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/** Only in-memory callbacks and synthetic account data are used. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class NewsFeedSessionTest {
    private Context context;
    private ActivityController<AppCompatActivity> host;
    private TestFeed fragment;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(MessageLongPoll.class, "instance", null);
        Session.clear(context);
        Session.save(context, "synthetic-token-a", "42");
        host = Robolectric.buildActivity(AppCompatActivity.class);
        host.get().setTheme(R.style.Theme_VKTea);
        host.setup();
        FrameLayout container = new FrameLayout(host.get());
        container.setId(View.generateViewId());
        host.get().setContentView(container);
        fragment = new TestFeed();
        Bundle args = new Bundle();
        args.putLong("list_id", 0);
        fragment.setArguments(args);
        host.get().getSupportFragmentManager().beginTransaction().add(container.getId(), fragment).commitNow();
        shadowOf(Looper.getMainLooper()).idle();
    }

    @After public void tearDown() {
        if (host != null) host.close();
        Session.clear(context);
        ReflectionHelpers.setStaticField(MessageLongPoll.class, "instance", null);
    }

    @Test public void sessionSwitchClearsVisibleFeedIgnoresLateCallbacksAndUsesScopedCache() throws Exception {
        File legacy = new File(context.getFilesDir(), "feed_cache.json");
        try (FileWriter writer = new FileWriter(legacy)) { writer.write(feed("legacy").toString()); }
        File cacheA = NewsFeedFragment.feedCacheFile(context, "42");
        File cacheB = NewsFeedFragment.feedCacheFile(context, "84");
        assertNotNull(cacheA);
        assertNotNull(cacheB);
        assertNotEquals(cacheA, cacheB);
        assertFalse(cacheA.getName().contains("42"));

        VKApi.Callback pendingA = fragment.feedRequests.get(0);
        pendingA.success(feed("account-a"));
        assertEquals(1, rowCount());
        assertTrue(cacheA.exists());
        assertFalse(cacheB.exists());

        Session.save(context, "synthetic-token-b", "84");
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, rowCount());
        assertEquals(2, fragment.feedRequests.size());
        SwipeRefreshLayout refresh = fragment.requireView().findViewById(R.id.feed_refresh);
        assertTrue(refresh.isRefreshing());
        pendingA.success(feed("late-a"));
        pendingA.error(new VKApi.SessionChangedException());
        assertEquals(0, rowCount());
        assertTrue(refresh.isRefreshing());
        assertFalse(cacheB.exists());

        fragment.feedRequests.get(1).error(new Exception("synthetic failure"));
        assertEquals(0, rowCount()); // Neither account A's cache nor legacy is read for B.
        assertFalse(refresh.isRefreshing());
        assertTrue(legacy.exists());

        ReflectionHelpers.callInstanceMethod(fragment, "load");
        fragment.feedRequests.get(2).success(feed("account-b"));
        assertEquals(1, rowCount());
        assertTrue(cacheB.exists());

        Session.save(context, "synthetic-token-a", "42");
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, rowCount());
        fragment.feedRequests.get(3).error(new Exception("synthetic failure"));
        assertEquals(1, rowCount()); // Only A's own cached feed is restored.
        assertTrue(legacy.exists());
    }

    @Test public void logoutClearsVisibleRowsWithoutNetworkAndStoppedScreenReloadsOnlyWhenStarted() throws Exception {
        VKApi.Callback pendingA = fragment.feedRequests.get(0);
        pendingA.success(feed("account-a"));
        assertEquals(1, rowCount());
        Session.clear(context);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, rowCount());
        assertEquals(1, fragment.feedRequests.size());
        pendingA.success(feed("late-after-logout"));
        assertEquals(0, rowCount());

        host.pause().stop();
        Session.save(context, "synthetic-token-b", "84");
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, fragment.feedRequests.size());
        host.start().resume();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(2, fragment.feedRequests.size());
        fragment.feedRequests.get(1).success(feed("account-b"));
        assertEquals(1, rowCount());
    }

    private int rowCount() {
        RecyclerView view = fragment.requireView().findViewById(R.id.feed_list);
        return view.getAdapter().getItemCount();
    }

    private static JSONObject feed(String text) throws Exception {
        return new JSONObject("{\"response\":{\"items\":[{\"source_id\":42,\"post_id\":1,"
                + "\"date\":1700000000,\"text\":\"" + text + "\"}],"
                + "\"profiles\":[{\"id\":42,\"first_name\":\"Synthetic\"}],\"groups\":[]}}");
    }

    public static final class TestFeed extends NewsFeedFragment {
        final List<VKApi.Callback> feedRequests = new ArrayList<>();
        @Override protected void requestFeed(String params, VKApi.Callback callback) { feedRequests.add(callback); }
        @Override protected void requestStories(VKApi.Callback callback) { }
    }
}
