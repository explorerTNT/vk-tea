package com.vktea.feed;

import static org.junit.Assert.*;

import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.vktea.auth.Session;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class StoryDraftsActivityTest {
    private final Map<String, byte[]> files = new HashMap<>();
    private StoryDraftStore store;
    private ActivityController<StoryDraftsActivity> controller;
    private StoryDraftsActivity.StoreFactory originalFactory;

    @Before public void setUp() {
        originalFactory = StoryDraftsActivity.storeFactory;
        Context app = RuntimeEnvironment.getApplication();
        Session.save(app, "synthetic-token", "12", "synthetic-secret");
        StoryDraftStore.Storage disk = new StoryDraftStore.Storage() {
            @Override public byte[] read(String name) { return files.get(name); }
            @Override public void write(String name, byte[] value) { files.put(name, value); }
            @Override public void delete(String name) { files.remove(name); }
            @Override public List<String> list(String prefix) {
                List<String> found = new ArrayList<>();
                for (String name : files.keySet()) if (name.startsWith(prefix)) found.add(name);
                return found;
            }
        };
        store = new StoryDraftStore(disk, create -> new SecretKeySpec(new byte[16], "AES"));
        StoryDraftsActivity.storeFactory = context -> new StoryDraftsActivity.Store() {
            @Override public StoryDraftStore.ListResult list(String account) { return store.list(account); }
            @Override public StoryDraftStore.ClearResult clearIfMatches(String account, long owner, long story, String text) {
                return store.clearIfMatches(account, owner, story, text);
            }
        };
    }

    @After public void tearDown() {
        if (controller != null) controller.pause().stop().destroy();
        StoryDraftsActivity.storeFactory = originalFactory;
        Session.clear(RuntimeEnvironment.getApplication());
    }

    @Test public void expiredStoryDraftCanBeViewedCopiedAndExplicitlyDeletedWithoutSavedState() throws Exception {
        assertTrue(store.save("12", -7, 25, "synthetic reply"));
        assertTrue(store.save("34", 7, 26, "other account"));
        controller = Robolectric.buildActivity(StoryDraftsActivity.class).setup();
        StoryDraftsActivity activity = controller.get();
        Button item = awaitButton(activity, "-7_25");
        assertNull(findButton(activity.getWindow().getDecorView(), "7_26"));
        item.performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        AlertDialog detail = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(detail.isShowing());
        assertTrue(contains(detail.getWindow().getDecorView(), "synthetic reply"));
        Bundle saved = new Bundle(); controller.saveInstanceState(saved);
        assertFalse(saved.toString().contains("synthetic reply"));
        detail.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        assertEquals("synthetic reply", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        assertEquals(StoryDraftStore.Status.FOUND, store.load("12", -7, 25).status);
        item.performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle(); detail = (AlertDialog) ShadowDialog.getLatestDialog();
        detail.getButton(AlertDialog.BUTTON_NEUTRAL).performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        AlertDialog confirm = (AlertDialog) ShadowDialog.getLatestDialog();
        confirm.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(StoryDraftStore.Status.FOUND, store.load("12", -7, 25).status);
        item.performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle(); ((AlertDialog) ShadowDialog.getLatestDialog()).getButton(AlertDialog.BUTTON_NEUTRAL).performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        ((AlertDialog) ShadowDialog.getLatestDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        awaitText(activity, "Сохранённых ответов пока нет");
        assertEquals(StoryDraftStore.Status.MISSING, store.load("12", -7, 25).status);
        assertEquals(StoryDraftStore.Status.FOUND, store.load("34", 7, 26).status);
    }

    @Test public void deletionOfStaleDisplayedTextPreservesNewerDraft() throws Exception {
        assertTrue(store.save("12", 7, 25, "older"));
        controller = Robolectric.buildActivity(StoryDraftsActivity.class).setup();
        Button item = awaitButton(controller.get(), "7_25");
        item.performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle(); ((AlertDialog) ShadowDialog.getLatestDialog()).getButton(AlertDialog.BUTTON_NEUTRAL).performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(store.save("12", 7, 25, "newer"));
        ((AlertDialog) ShadowDialog.getLatestDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        awaitButton(controller.get(), "7_25").performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(contains(ShadowDialog.getLatestDialog().getWindow().getDecorView(), "newer"));
        assertEquals("newer", store.load("12", 7, 25).text);
    }

    @Test public void unreadableRecordsRemainOnDiskAndReadableDraftsRemainAccessible() throws Exception {
        assertTrue(store.save("12", 7, 25, "unreadable"));
        String brokenName = files.keySet().iterator().next();
        files.put(brokenName, new byte[]{1, 2, 3});
        assertTrue(store.save("12", 8, 26, "readable"));
        controller = Robolectric.buildActivity(StoryDraftsActivity.class).setup();
        awaitButton(controller.get(), "8_26").performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(contains(ShadowDialog.getLatestDialog().getWindow().getDecorView(), "readable"));
        assertTrue(contains(controller.get().getWindow().getDecorView(), "Некоторые черновики не удалось прочитать"));
        assertArrayEquals(new byte[]{1, 2, 3}, files.get(brokenName));
    }
    @Test public void switchingAccountClosesScreenWithoutShowingPreviousDraft() throws Exception {
        assertTrue(store.save("12", 7, 25, "private"));
        controller = Robolectric.buildActivity(StoryDraftsActivity.class).setup();
        awaitButton(controller.get(), "7_25").performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        Session.save(RuntimeEnvironment.getApplication(), "other-token", "34", "other-secret");
        controller.pause().resume();
        assertTrue(controller.get().isFinishing());
        assertFalse(ShadowDialog.getLatestDialog().isShowing());
    }

    private static Button awaitButton(StoryDraftsActivity activity, String text) throws Exception {
        long until = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < until) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Button result = findButton(activity.getWindow().getDecorView(), text);
            if (result != null) return result;
            Thread.sleep(10);
        }
        fail("Button not shown: " + text); return null;
    }
    private static void awaitText(StoryDraftsActivity activity, String text) throws Exception {
        long until = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < until) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (contains(activity.getWindow().getDecorView(), text)) return;
            Thread.sleep(10);
        }
        fail("Text not shown: " + text);
    }
    private static Button findButton(View view, String text) {
        if (view instanceof Button && ((Button) view).getText().toString().contains(text)) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = findButton(group.getChildAt(i), text); if (found != null) return found;
            }
        }
        return null;
    }
    private static boolean contains(View view, String text) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(text)) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (contains(group.getChildAt(i), text)) return true;
        }
        return false;
    }
}
