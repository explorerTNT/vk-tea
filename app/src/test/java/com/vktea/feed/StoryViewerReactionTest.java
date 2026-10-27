package com.vktea.feed;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ListView;

import androidx.appcompat.app.AlertDialog;

import com.vktea.R;
import com.vktea.auth.AuthWebViewActivity;
import com.vktea.auth.Session;

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
import org.robolectric.shadows.ShadowDialog;

/** UI actions use a synthetic story and an in-process shadow; no VK request is sent. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = StoryViewerReactionTest.OfflineReactions.class)
public class StoryViewerReactionTest {
    @Implements(StoryReactionsViewModel.class)
    public static class OfflineReactions {
        static int calls;
        static Integer selected;
        static long owner, storyId;
        static boolean uncertain;
        static int acknowledgements;
        @Implementation protected boolean send(JSONObject story, Integer reaction) {
            calls++; selected = reaction; owner = story.optLong("owner_id"); storyId = story.optLong("id");
            return true;
        }
        @Implementation protected boolean sameSession() { return true; }
        @Implementation protected boolean isUncertain(JSONObject story) { return uncertain; }
        @Implementation protected boolean acknowledgeUnknown(JSONObject story) { acknowledgements++; uncertain = false; return true; }
    }
    private ActivityController<StoryViewerActivity> controller;
    @Before public void setup() {
        OfflineReactions.calls = 0; OfflineReactions.selected = null; OfflineReactions.uncertain = false; OfflineReactions.acknowledgements = 0;
        Session.save(RuntimeEnvironment.getApplication(), "synthetic-token", "8", "synthetic-secret");
    }
    @After public void cleanup() {
        if (controller != null) controller.pause().stop().destroy();
        Session.save(RuntimeEnvironment.getApplication(), "", "", "");
    }
    private StoryViewerActivity open(String permission) throws Exception {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), StoryViewerActivity.class)
                .putExtra("name", "Synthetic")
                .putExtra("stories", "[{\"owner_id\":7,\"id\":42" + permission + "}]");
        controller = Robolectric.buildActivity(StoryViewerActivity.class, intent).setup();
        StoryViewerActivity activity = controller.get();
        java.lang.reflect.Field ready = StoryViewerActivity.class.getDeclaredField("mediaReady");
        ready.setAccessible(true); ready.setBoolean(activity, true);
        java.lang.reflect.Method update = StoryViewerActivity.class.getDeclaredMethod("updateActions");
        update.setAccessible(true); update.invoke(activity);
        return activity;
    }
    private static AlertDialog openReactions(StoryViewerActivity activity) throws Exception {
        java.lang.reflect.Field buttonField = StoryViewerActivity.class.getDeclaredField("nativeReaction");
        buttonField.setAccessible(true); Button button = (Button) buttonField.get(activity);
        assertTrue(button.isEnabled()); button.performClick();
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }
    private static void choose(AlertDialog dialog, int index) {
        ListView list = dialog.getListView(); assertNotNull(list);
        View row = list.getAdapter().getView(index, null, list);
        list.performItemClick(row, index, list.getAdapter().getItemId(index));
    }
    @Test public void displayedReactionsAndRemovalSendMatchingIds() throws Exception {
        Integer[] expected = {0, 2, 6, 1, 3, 5, null};
        String[] labels = {"❤️", "🔥", "👍", "😂", "😍", "🙏"};
        for (int i = 0; i < expected.length; i++) {
            StoryViewerActivity activity = open(",\"can_like\":true");
            AlertDialog dialog = openReactions(activity);
            if (i < labels.length) assertEquals(labels[i], dialog.getListView().getAdapter().getItem(i));
            else assertEquals(activity.getString(R.string.story_native_remove), dialog.getListView().getAdapter().getItem(i));
            choose(dialog, i);
            assertEquals(i + 1, OfflineReactions.calls); assertEquals(expected[i], OfflineReactions.selected);
            assertEquals(7, OfflineReactions.owner); assertEquals(42, OfflineReactions.storyId);
            controller.pause().stop().destroy(); controller = null;
        }
    }
    @Test public void noPermissionOffersVkFallbackWithoutSending() throws Exception {
        for (String permission : new String[]{",\"can_like\":false", ""}) {
            StoryViewerActivity activity = open(permission);
            AlertDialog dialog = openReactions(activity);
            assertNull(dialog.getListView()); assertEquals(0, OfflineReactions.calls);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            Intent launched = shadowOf(activity).getNextStartedActivity();
            assertNotNull(launched); assertEquals(AuthWebViewActivity.class.getName(), launched.getComponent().getClassName());
            assertEquals("https://m.vk.com/story7_42", launched.getDataString());
            assertEquals(0, OfflineReactions.calls);
            controller.pause().stop().destroy(); controller = null;
        }
    }
    @Test public void permittedPickerAlsoOffersVkFallback() throws Exception {
        StoryViewerActivity activity = open(",\"can_like\":1");
        java.lang.reflect.Field storiesField = StoryViewerActivity.class.getDeclaredField("stories");
        storiesField.setAccessible(true);
        JSONObject story = ((org.json.JSONArray) storiesField.get(activity)).getJSONObject(0);
        assertTrue("numeric can_like must permit reaction: " + story.opt("can_like"), StoryReactionsViewModel.canLike(story));
        AlertDialog dialog = openReactions(activity);
        assertNotNull("expected reaction picker", dialog.getListView());
        assertEquals(activity.getString(R.string.story_open_vk), dialog.getListView().getAdapter().getItem(7));
        choose(dialog, 7);
        shadowOf(Looper.getMainLooper()).idle();
        Intent launched = shadowOf(activity).getNextStartedActivity();
        assertNotNull(launched); assertEquals("https://m.vk.com/story7_42", launched.getDataString());
        assertEquals(0, OfflineReactions.calls);
    }
    @Test public void unknownResultCanBeAcknowledgedWithoutSendingAnotherRequest() throws Exception {
        StoryViewerActivity activity = open(",\"can_like\":true");
        OfflineReactions.uncertain = true;
        AlertDialog dialog = openReactions(activity);
        assertNull(dialog.getListView());
        assertEquals(activity.getString(R.string.story_native_checked), dialog.getButton(AlertDialog.BUTTON_NEUTRAL).getText());
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, OfflineReactions.acknowledgements);
        assertEquals(0, OfflineReactions.calls);
        assertFalse(OfflineReactions.uncertain);
    }
}
