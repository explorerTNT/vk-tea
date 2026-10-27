package com.vktea.feed;

import static org.junit.Assert.*;

import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class StoryViewerDraftTest {
    @Test public void editorAndConfirmationSurviveRecreationWithoutSavingTextInBundle() throws Exception {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), StoryViewerActivity.class)
                .putExtra("name", "Synthetic").putExtra("stories", "[{\"id\":2,\"owner_id\":7,\"can_reply\":true}]");
        ActivityController<StoryViewerActivity> controller = Robolectric.buildActivity(StoryViewerActivity.class, intent).setup();
        StoryViewerActivity activity = controller.get();
        StoryViewerActivity.ReplyDraft draft = new ViewModelProvider(activity).get(StoryViewerActivity.ReplyDraft.class);
        draft.stage = StoryViewerActivity.ReplyDraft.EDIT;
        java.lang.reflect.Method editor = StoryViewerActivity.class.getDeclaredMethod("showReplyEditor");
        editor.setAccessible(true); editor.invoke(activity);
        EditText input = findInput(ShadowDialog.getLatestDialog().getWindow().getDecorView());
        assertNotNull(input); input.setText("Черновик\nтеста");
        Bundle saved = new Bundle(); activity.onSaveInstanceState(saved);
        assertFalse(saved.toString().contains("Черновик"));

        controller.recreate();
        Dialog restored = ShadowDialog.getLatestDialog();
        assertTrue(restored.isShowing());
        input = findInput(restored.getWindow().getDecorView());
        assertNotNull(input); assertEquals("Черновик\nтеста", input.getText().toString());
        ((AlertDialog) restored).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals(StoryViewerActivity.ReplyDraft.CONFIRM, draft.stage);

        controller.recreate();
        restored = ShadowDialog.getLatestDialog();
        assertTrue(restored.isShowing());
        assertTrue(containsText(restored.getWindow().getDecorView(), "Черновик\nтеста"));
        assertEquals(StoryViewerActivity.ReplyDraft.CONFIRM, draft.stage);
        ((AlertDialog) restored).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(StoryViewerActivity.ReplyDraft.NONE, draft.stage);
        controller.pause().stop().destroy();
    }

    private static EditText findInput(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText found = findInput(group.getChildAt(i)); if (found != null) return found;
            }
        }
        return null;
    }
    private static boolean containsText(View view, String value) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(value)) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (containsText(group.getChildAt(i), value)) return true;
        }
        return false;
    }
}
