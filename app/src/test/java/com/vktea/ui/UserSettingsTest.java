package com.vktea.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.vktea.R;
import com.vktea.api.VKApi;
import com.vktea.auth.Session;
import com.vktea.feed.NewsFeedFragment;

import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import androidx.appcompat.app.AppCompatActivity;

import android.view.View;
import android.widget.FrameLayout;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = UserSettingsTest.ShadowApi.class)
public class UserSettingsTest {
    private Context context;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE).edit().clear().commit();
        ShadowApi.storiesRequests = 0;
        ShadowApi.feedCallback = null;
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
        new File(context.getFilesDir(), "feed_cache_42.json").delete();
        new File(context.getFilesDir(), "feed_cache_43.json").delete();
    }

    @After public void cleanup() {
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
        new File(context.getFilesDir(), "feed_cache_42.json").delete();
        new File(context.getFilesDir(), "feed_cache_43.json").delete();
    }

    @Test public void defaultsAndChangesSurviveNewReaders() {
        assertTrue(UserSettings.showStories(context));
        assertTrue(UserSettings.checkNewPosts(context));
        UserSettings.setShowStories(context, false);
        UserSettings.setCheckNewPosts(context, false);
        assertFalse(UserSettings.showStories(RuntimeEnvironment.getApplication()));
        assertFalse(UserSettings.checkNewPosts(RuntimeEnvironment.getApplication()));
    }

    @Test public void disabledStoriesDoNotRequestOrShowStoriesWhenFeedReopens() {
        UserSettings.setShowStories(context, false);
        AppCompatActivity activity = Robolectric.buildActivity(AppCompatActivity.class).create().start().resume().get();
        FrameLayout container = new FrameLayout(activity);
        container.setId(View.generateViewId());
        activity.setContentView(container);
        NewsFeedFragment feed = NewsFeedFragment.newInstance(0);
        activity.getSupportFragmentManager().beginTransaction().replace(container.getId(), feed).commitNow();
        assertTrue(feed.requireView().findViewById(R.id.stories_row).getVisibility() == View.GONE);
        assertTrue(ShadowApi.storiesRequests == 0);
        UserSettings.setShowStories(context, true);
        NewsFeedFragment reopened = NewsFeedFragment.newInstance(0);
        activity.getSupportFragmentManager().beginTransaction().replace(container.getId(), reopened).commitNow();
        assertTrue(ShadowApi.storiesRequests == 1);
        activity.finish();
    }

    @Test public void staleFeedCallbackCannotWriteCacheForNewAccount() throws Exception {
        Session.save(context, "synthetic-42", "42");
        AppCompatActivity activity = Robolectric.buildActivity(AppCompatActivity.class).create().start().resume().get();
        FrameLayout container = new FrameLayout(activity);
        container.setId(View.generateViewId());
        activity.setContentView(container);
        activity.getSupportFragmentManager().beginTransaction().replace(container.getId(), NewsFeedFragment.newInstance(0)).commitNow();
        VKApi.Callback oldRequest = ShadowApi.feedCallback;
        Session.save(context, "synthetic-43", "43");
        JSONObject feed = new JSONObject("{response:{items:[],profiles:[],groups:[]}}");
        oldRequest.success(feed);
        assertFalse(new File(context.getFilesDir(), "feed_cache_42.json").exists());
        assertFalse(new File(context.getFilesDir(), "feed_cache_43.json").exists());
        activity.getSupportFragmentManager().beginTransaction().replace(container.getId(), NewsFeedFragment.newInstance(0)).commitNow();
        ShadowApi.feedCallback.success(feed);
        File current = new File(context.getFilesDir(), "feed_cache_43.json");
        assertTrue(current.exists());
        assertEquals(feed.toString(), new String(Files.readAllBytes(current.toPath()), StandardCharsets.UTF_8));
        activity.finish();
    }

    @Implements(VKApi.class)
    public static class ShadowApi {
        static int storiesRequests;
        static VKApi.Callback feedCallback;
        @Implementation public static void get(Context context, String method, String params, VKApi.Callback callback) {
            if ("stories.get".equals(method)) storiesRequests++;
            if ("newsfeed.get".equals(method)) feedCallback = callback;
        }
    }
}
