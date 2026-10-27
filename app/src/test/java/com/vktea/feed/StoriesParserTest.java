package com.vktea.feed;

import static org.junit.Assert.*;

import android.content.Intent;
import android.view.View;
import android.widget.Button;

import org.robolectric.RuntimeEnvironment;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class StoriesParserTest {
    @Test public void parsesIntegerCountOwnersAndMultipleStories() throws Exception {
        JSONObject root = new JSONObject("{\"response\":{\"count\":2,\"profiles\":[{\"id\":7,\"first_name\":\"Анна\",\"last_name\":\"Тест\",\"photo_100\":\"https://cdn.example/avatar\"}],\"groups\":[{\"id\":9,\"name\":\"Группа\"}],\"items\":[{\"owner_id\":7,\"stories\":[{\"id\":1,\"owner_id\":7,\"type\":\"photo\",\"photo\":{\"sizes\":[{\"type\":\"x\",\"url\":\"https://cdn.example/photo\"}]}},{\"id\":2,\"owner_id\":7,\"type\":\"video\",\"video\":{\"files\":{\"mp4_720\":\"https://cdn.example/video.mp4\"}}}]},{\"grouped\":[{\"owner_id\":-9,\"stories\":[{\"id\":3,\"owner_id\":-9,\"type\":\"photo\",\"is_expired\":1}]}]}]}}");
        List<StoriesParser.Owner> owners = StoriesParser.parse(root);
        assertEquals(2, owners.size());
        assertEquals("Анна Тест", owners.get(0).name);
        assertEquals(2, owners.get(0).stories.length());
        assertEquals("https://cdn.example/photo", owners.get(0).stories.getJSONObject(0).getString("url"));
        assertEquals("https://cdn.example/video.mp4", owners.get(0).stories.getJSONObject(1).getString("url"));
        assertEquals("Группа", owners.get(1).name);
        assertEquals("", owners.get(1).stories.getJSONObject(0).getString("url"));
    }
    @Test public void unsupportedAndUnsafeMediaRemainAvailableForExplicitFallback() throws Exception {
        JSONObject root = new JSONObject("{\"response\":{\"items\":[{\"first_name\":\"Старый формат\",\"stories\":[{\"id\":4,\"owner_id\":7,\"type\":\"photo\",\"photo\":{\"url\":\"file:///data/private\"}},{\"id\":5,\"owner_id\":7,\"type\":\"video\",\"video\":{}}]}]}}");
        StoriesParser.Owner owner = StoriesParser.parse(root).get(0);
        assertEquals("Старый формат", owner.name);
        assertEquals("", owner.stories.getJSONObject(0).getString("url"));
        assertEquals("", owner.stories.getJSONObject(1).getString("url"));
        assertEquals("", StoriesParser.safeUrl("https://user@cdn.example/path"));
    }
    @Test public void viewerNavigatesUnavailableStoriesWithoutAutomaticExternalLaunch() throws Exception {
        JSONObject root = new JSONObject("{\"response\":{\"items\":[{\"stories\":[{\"id\":4,\"owner_id\":7,\"type\":\"photo\"},{\"id\":5,\"owner_id\":7,\"type\":\"video\"}]}]}}");
        android.content.Context context = RuntimeEnvironment.getApplication();
        StoriesParser.Owner owner = StoriesParser.parse(root).get(0);
        Intent intent = new Intent(context, StoryViewerActivity.class)
                .putExtra("name", owner.name).putExtra("stories", owner.stories.toString());
        ActivityController<StoryViewerActivity> controller = Robolectric.buildActivity(StoryViewerActivity.class, intent).setup();
        StoryViewerActivity activity = controller.get();
        Button next = findButton(activity.getWindow().getDecorView(), "Далее");
        assertNotNull(next); next.performClick();
        assertFalse(activity.isFinishing());
        next.performClick(); assertTrue(activity.isFinishing());
        controller.pause().stop().destroy();
    }
    @Test public void missingIntentFinishesWithoutCrashingDuringLifecycle() {
        ActivityController<StoryViewerActivity> controller = Robolectric.buildActivity(StoryViewerActivity.class).create().start();
        assertTrue(controller.get().isFinishing());
        controller.stop().destroy();
    }
    @Test public void invalidIdentityDoesNotOfferWebFallback() {
        android.content.Context context = RuntimeEnvironment.getApplication();
        Intent intent = new Intent(context, StoryViewerActivity.class)
                .putExtra("name", "Test").putExtra("stories", "[{\"owner_id\":0,\"id\":0}]");
        ActivityController<StoryViewerActivity> controller = Robolectric.buildActivity(StoryViewerActivity.class, intent).setup();
        Button web = findButton(controller.get().getWindow().getDecorView(), "Открыть в VK");
        assertNotNull(web); assertEquals(View.GONE, web.getVisibility());
        controller.pause().stop().destroy();
    }
    private static Button findButton(View view, String text) {
        if (view instanceof Button && text.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { Button b = findButton(group.getChildAt(i), text); if (b != null) return b; }
        }
        return null;
    }
}
