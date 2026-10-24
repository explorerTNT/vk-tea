package com.vktea.feed;

import static org.junit.Assert.*;
import android.app.Dialog;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
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

/** Real forms use the offline repository; opening, cancelling and recreation cannot publish anything. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PhotoSocialControllerTest {
    private final PhotoSocialViewModelTest.FakeRepository repository = new PhotoSocialViewModelTest.FakeRepository();
    private ActivityController<Host> activity;
    private PhotoSocialViewModel model;
    private PhotoSocialController controller;
    @Before public void setup() {
        activity = Robolectric.buildActivity(Host.class).setup();
        model = new PhotoSocialViewModel(RuntimeEnvironment.getApplication(), repository);
        model.select(PhotoSocialViewModelTest.photo(1)); repository.loads.get(0).success(PhotoSocialViewModelTest.photo(1));
        controller = new PhotoSocialController(activity.get(), model);
    }
    @After public void clear() {
        if (controller != null) controller.destroy();
        if (activity != null) activity.close();
        model.onCleared();
        for (Dialog dialog : new java.util.ArrayList<>(ShadowDialog.getShownDialogs())) dialog.dismiss();
    }
    @Test public void repostOpensVkComposerAndOnlyPositiveExplicitlyPublishesOnce() {
        controller.openRepost(); AlertDialog dialog = dialog();
        input(dialog.getWindow().getDecorView()).setText("Synthetic wall draft");
        assertEquals(0, repository.writes.size());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals(1, repository.writes.size()); assertEquals("repost", repository.action);
        assertEquals("Synthetic wall draft", repository.text);
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); assertEquals(1, repository.writes.size());
        repository.<Integer>write().success(5);
        assertFalse(dialog.isShowing()); assertEquals(5, model.state().getValue().photo.reposts);
        assertNull(model.state().getValue().event);
    }
    @Test public void disposingViewsRetainsDraftAndOpenDialogWithoutASecondSubmit() {
        controller.openRepost(); input(dialog().getWindow().getDecorView()).setText("Synthetic retained draft");
        controller.destroy(); controller = new PhotoSocialController(activity.get(), model);
        assertTrue(dialog().isShowing()); assertEquals("Synthetic retained draft", input(dialog().getWindow().getDecorView()).getText().toString());
        assertEquals(0, repository.writes.size());
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        assertFalse(model.state().getValue().repostOpen); assertEquals(0, repository.writes.size());
    }
    @Test public void commentsAreReadableWhenPostingDeniedAndEmptyComposerCannotSend() {
        controller.openComments(); AlertDialog comments = dialog();
        assertEquals(1, repository.reads.size()); assertFalse(comments.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        comments.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); assertEquals(0, repository.writes.size());
        input(comments.getWindow().getDecorView()).setText("Synthetic comment");
        assertTrue(comments.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        comments.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); assertEquals("comment", repository.action);
        assertEquals(1, repository.writes.size()); assertFalse(comments.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        repository.write().error(new PhotoSocialError(PhotoSocialError.Kind.ACCESS));
        assertEquals("Synthetic comment", input(comments.getWindow().getDecorView()).getText().toString());
        model.closeComments();
        model.select(PhotoSocialViewModelTest.photo(2));
        repository.loads.get(1).success(new ViewerPhoto("synthetic", 2, 41, "", 0, 0, 0, false, false, false, false));
        controller.openComments();
        assertTrue(dialog().isShowing()); assertFalse(input(dialog().getWindow().getDecorView()).isEnabled());
        assertFalse(dialog().getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
    }
    private static AlertDialog dialog() { return (AlertDialog) ShadowDialog.getLatestDialog(); }
    private static EditText input(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            EditText found = input(((ViewGroup) view).getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
    public static class Host extends AppCompatActivity {
        @Override protected void onCreate(Bundle state) { setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light); super.onCreate(state); }
    }
}
