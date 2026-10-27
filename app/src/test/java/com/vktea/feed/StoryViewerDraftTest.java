package com.vktea.feed;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

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
import com.vktea.auth.Session;
import org.junit.After;
import org.junit.Before;
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
    private StoryDraftTestSupport support;
    private ActivityController<TestStoryViewerActivity> controller;

    public static class TestStoryViewerActivity extends StoryViewerActivity {
        @Override public ViewModelProvider.Factory getDefaultViewModelProviderFactory() {
            return new ViewModelProvider.AndroidViewModelFactory(getApplication());
        }
    }

    @Before public void setup() throws Exception {
        support = new StoryDraftTestSupport();
        StoryViewerActivity.draftStores = context -> support.store;
        Session.save(RuntimeEnvironment.getApplication(), "synthetic-token", "456", "synthetic-secret");
    }
    @After public void clean() throws Exception {
        if (controller != null) controller.pause().stop().destroy();
        StoryDraftTestSupport.drain();
        StoryViewerActivity.draftStores = StoryDraftStore::create;
        Session.save(RuntimeEnvironment.getApplication(), "", "", "");
        shadowOf(Looper.getMainLooper()).idle();
    }
    private void openActivity() {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), TestStoryViewerActivity.class)
                .putExtra("name", "Synthetic").putExtra("stories", "[{\"id\":2,\"owner_id\":7,\"can_reply\":true}]");
        controller = Robolectric.buildActivity(TestStoryViewerActivity.class, intent).setup();
    }
    private void openEditor() throws Exception {
        StoryViewerActivity activity = controller.get();
        java.lang.reflect.Field ready = StoryViewerActivity.class.getDeclaredField("mediaReady");
        ready.setAccessible(true); ready.setBoolean(activity, true);
        java.lang.reflect.Method method = StoryViewerActivity.class.getDeclaredMethod("replyDialog");
        method.setAccessible(true);
        StoryActionsViewModel actions = new ViewModelProvider(activity).get(StoryActionsViewModel.class);
        assertEquals("Session token changed", "synthetic-token", Session.getToken(activity));
        assertEquals("Session user changed", "456", Session.getUserId(activity));
        assertEquals("Session secret changed", "synthetic-secret", Session.getSecret(activity));
        assertTrue("Synthetic session must be valid", actions.sameSession());
        assertTrue("Synthetic story must allow a reply", StoryActionsViewModel.canReply(new org.json.JSONObject().put("owner_id", 7).put("id", 2).put("can_reply", true), "456"));
        assertFalse("Synthetic story must not have an in-flight send", actions.isPending(new org.json.JSONObject().put("owner_id", 7).put("id", 2)));
        method.invoke(activity);
        assertNotNull("Opening editor must show a dialog", ShadowDialog.getLatestDialog());
        StoryDraftTestSupport.drain(); shadowOf(Looper.getMainLooper()).idle();
    }
    private static EditText currentInput() { return findInput(ShadowDialog.getLatestDialog().getWindow().getDecorView()); }

    @Test public void editorAndConfirmationSurviveRotationAndCancellationKeepsStoredDraft() throws Exception {
        openActivity(); openEditor();
        StoryViewerActivity activity = controller.get();
        StoryViewerActivity.ReplyDraft draft = new ViewModelProvider(activity).get(StoryViewerActivity.ReplyDraft.class);
        EditText input = currentInput(); assertNotNull(input); assertTrue(input.isEnabled());
        input.setText("Черновик\nтеста"); StoryDraftTestSupport.drain();
        assertEquals("Черновик\nтеста", support.store.load("456", 7, 2).text);
        Bundle saved = new Bundle(); activity.onSaveInstanceState(saved);
        assertFalse(saved.toString().contains("Черновик"));
        controller.recreate();
        Dialog restored = ShadowDialog.getLatestDialog(); assertTrue(restored.isShowing());
        input = currentInput(); assertEquals("Черновик\nтеста", input.getText().toString());
        ((AlertDialog) restored).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals(StoryViewerActivity.ReplyDraft.CONFIRM, draft.stage);
        controller.recreate(); restored = ShadowDialog.getLatestDialog();
        assertTrue(restored.isShowing()); assertTrue(containsText(restored.getWindow().getDecorView(), "Черновик\nтеста"));
        ((AlertDialog) restored).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(StoryViewerActivity.ReplyDraft.NONE, draft.stage);
        assertEquals("Черновик\nтеста", support.store.load("456", 7, 2).text);
    }

    @Test public void closedActivityRestoresDraftAndClearingInputRemovesIt() throws Exception {
        openActivity(); openEditor(); currentInput().setText("Сохранённый текст");
        StoryDraftTestSupport.drain();
        controller.pause().stop().destroy(); controller = null;
        openActivity(); openEditor();
        assertEquals("Сохранённый текст", currentInput().getText().toString());
        currentInput().setText(""); StoryDraftTestSupport.drain();
        assertEquals(StoryDraftStore.Status.MISSING, support.store.load("456", 7, 2).status);
    }

    @Test public void failedLoadDisablesEditingAndDoesNotOverwrite() throws Exception {
        assertTrue(support.store.save("456", 7, 2, "protected"));
        support.storage.fail = true;
        openActivity(); openEditor();
        assertFalse(currentInput().isEnabled());
        assertFalse(((AlertDialog) ShadowDialog.getLatestDialog()).getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        support.storage.fail = false;
        assertEquals("protected", support.store.load("456", 7, 2).text);
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
