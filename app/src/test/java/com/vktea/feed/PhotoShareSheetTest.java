package com.vktea.feed;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.app.Dialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Looper;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.vktea.R;
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
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowDisplay;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class PhotoShareSheetTest {
    private final PhotoShareViewModelTest.FakeRepository repository = new PhotoShareViewModelTest.FakeRepository();
    private final AtomicInteger ownWall = new AtomicInteger();
    private ActivityController<Host> activity;
    private PhotoShareViewModel model;
    private PhotoShareSheet sheet;
    @Before public void setup() {
        activity = Robolectric.buildActivity(Host.class).setup();
        model = new PhotoShareViewModel(RuntimeEnvironment.getApplication(), repository);
        sheet = new PhotoShareSheet(activity.get(), model, ownWall::incrementAndGet);
    }
    @After public void clear() {
        sheet.destroy(); activity.close(); model.onCleared();
        for (Dialog dialog : new ArrayList<>(ShadowDialog.getShownDialogs())) dialog.dismiss();
        shadowOf(Looper.getMainLooper()).idle();
    }
    @Test public void openingSheetShowsRecipientAvatarsAndCopyUsesVkIdentityWithoutSending() {
        open(); Dialog shown = ShadowDialog.getLatestDialog();
        assertNotNull(shown.findViewById(R.id.photo_share_search_input));
        assertEquals(2, ((LinearLayout) shown.findViewById(R.id.photo_share_recipients)).getChildCount());
        assertFalse(shown.findViewById(R.id.photo_share_wall_action).isEnabled());
        shown.findViewById(R.id.photo_share_copy_action).performClick(); shadowOf(Looper.getMainLooper()).idle();
        ClipboardManager clipboard = (ClipboardManager) activity.get().getSystemService(Context.CLIPBOARD_SERVICE);
        assertEquals("https://vk.com/photo-41_12", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        assertEquals(0, repository.sends.size()); assertEquals(0, ownWall.get()); assertFalse(model.state().getValue().open);
    }
    @Test public void choosingRecipientOnlyOpensComposerAndExplicitSendRunsOnce() {
        open(); ((LinearLayout) ShadowDialog.getLatestDialog().findViewById(R.id.photo_share_recipients)).getChildAt(0).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        AlertDialog composer = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(composer.isShowing()); assertEquals(0, repository.sends.size());
        EditText input = composer.findViewById(R.id.photo_share_draft_input); input.setText("Synthetic draft");
        composer.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals(1, repository.sends.size()); assertEquals(7, repository.targets.get(0).peerId);
        assertEquals("Synthetic draft", repository.drafts.get(0));
        assertFalse(composer.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        composer.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); assertEquals(1, repository.sends.size());
    }
    @Test public void replacementControllerKeepsPendingConfirmationAndCannotReplaySend() {
        open(); model.selectRecipient(PhotoShareViewModelTest.recipient(7)); model.setDraft("Synthetic retained"); model.send();
        int id = repository.ids.get(0);
        sheet.destroy(); sheet = new PhotoShareSheet(activity.get(), model, ownWall::incrementAndGet);
        AlertDialog composer = (AlertDialog) ShadowDialog.getLatestDialog();
        assertEquals("Synthetic retained", ((EditText) composer.findViewById(R.id.photo_share_draft_input)).getText().toString());
        assertFalse(composer.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled()); assertEquals(1, repository.sends.size());
        repository.sends.get(0).error(new PhotoSocialError(PhotoSocialError.Kind.NETWORK));
        assertFalse(composer.findViewById(R.id.photo_share_draft_input).isEnabled());
        composer.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); assertEquals(2, repository.sends.size()); assertEquals(id, (int) repository.ids.get(1));
    }
    @Test public void ownWallRequiresPermissionAndUsesSeparateExplicitCallback() {
        ViewerPhoto photo = PhotoShareViewModelTest.photo();
        sheet.show(new ViewerPhoto(photo.url, photo.id, photo.ownerId, photo.accessKey, 0, 0, 0, false, true, true, true));
        View wall = ShadowDialog.getLatestDialog().findViewById(R.id.photo_share_wall_action);
        assertTrue(wall.isEnabled()); assertEquals(0, ownWall.get());
        wall.performClick(); shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, ownWall.get()); assertEquals(0, repository.sends.size()); assertFalse(model.state().getValue().open);
    }
    @Test @Config(qualifiers = "w320dp-h640dp")
    public void searchExpandsAboveImeAndActionsRemainReachableInAResizedViewport() {
        open(); BottomSheetDialog shown = (BottomSheetDialog) ShadowDialog.getLatestDialog();
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                shown.getWindow().getAttributes().softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
        EditText search = shown.findViewById(R.id.photo_share_search_input);
        assertFalse("Opening chooser must not open the keyboard", search.hasFocus());
        search.requestFocus(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT,
                shown.findViewById(com.google.android.material.R.id.design_bottom_sheet).getLayoutParams().height);
        assertEquals(BottomSheetBehavior.STATE_EXPANDED, shown.getBehavior().getState());
        NestedScrollView viewport = shown.findViewById(R.id.photo_share_viewport);
        measureViewport(viewport, 320, 180);
        assertTrue("Short IME viewport must allow reaching controls by scrolling", viewport.canScrollVertically(1));
        View copy = shown.findViewById(R.id.photo_share_copy_action);
        assertTrue(copy.requestRectangleOnScreen(new Rect(0, 0, copy.getWidth(), copy.getHeight()), true));
        assertTrue(viewport.getScrollY() > 0);
        copy.performClick(); assertFalse(model.state().getValue().open); assertEquals(0, repository.sends.size());
    }
    @Test @Config(qualifiers = "w640dp-h320dp")
    public void landscapeComposerScrollsLongDraftWithoutLosingTheExplicitSendButton() {
        open(); model.selectRecipient(PhotoShareViewModelTest.recipient(7));
        AlertDialog composer = (AlertDialog) ShadowDialog.getLatestDialog();
        EditText draft = composer.findViewById(R.id.photo_share_draft_input);
        draft.setText("Synthetic line one\nSynthetic line two\nSynthetic line three\nSynthetic line four\nSynthetic line five\nSynthetic line six");
        NestedScrollView viewport = composer.findViewById(R.id.photo_share_composer_viewport);
        measureViewport(viewport, 320, 100);
        assertTrue(viewport.canScrollVertically(1));
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                composer.getWindow().getAttributes().softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
        assertEquals(0, repository.sends.size());
        composer.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals(1, repository.sends.size()); assertTrue(repository.drafts.get(0).contains("Synthetic line six"));
    }
    @Test @Config(qualifiers = "w320dp-h640dp")
    public void nativeResizeDoesNotReceiveASecondImePaddingButActualOverlapDoes() {
        open(); BottomSheetDialog shown = (BottomSheetDialog) ShadowDialog.getLatestDialog();
        shown.findViewById(R.id.photo_share_search_input).requestFocus();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        NestedScrollView viewport = shown.findViewById(R.id.photo_share_viewport);
        View decor = shown.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 320, 640);
        measureViewport(viewport, 320, 180);
        int[] origin = new int[2]; viewport.getLocationOnScreen(origin);
        int viewportBottom = origin[1] + viewport.getHeight();
        int[] decorOrigin = new int[2]; decor.getLocationOnScreen(decorOrigin);
        assertTrue("Fixture must retain a full decor around a smaller resized viewport", decorOrigin[1] + decor.getHeight() > viewportBottom);
        // ShadowView's visible-frame implementation reads Display.getRectSize. Change that
        // app-visible area without traversing/resizing the existing full decor in between.
        android.view.Display display = ShadowDisplay.getDefaultDisplay();
        int oldHeight = display.getHeight();
        try {
            shadowOf(display).setHeight(viewportBottom);
            viewport.getViewTreeObserver().dispatchOnGlobalLayout();
            assertEquals("A viewport already above IME must not be padded again", 0, viewport.getPaddingBottom());
            shadowOf(display).setHeight(viewportBottom - 60);
            viewport.getViewTreeObserver().dispatchOnGlobalLayout();
            assertEquals("Overlay windows still reserve the actually obscured pixels", 60, viewport.getPaddingBottom());
        } finally { shadowOf(display).setHeight(oldHeight); }
        assertEquals(0, repository.sends.size());
    }
    private static void measureViewport(NestedScrollView view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }
    private void open() {
        sheet.show(PhotoShareViewModelTest.photo());
        repository.lookups.get(0).success(Arrays.asList(PhotoShareViewModelTest.recipient(7), PhotoShareViewModelTest.recipient(8)));
    }
    public static class Host extends AppCompatActivity {
        @Override protected void onCreate(Bundle state) { setTheme(com.google.android.material.R.style.Theme_MaterialComponents_Light_NoActionBar); super.onCreate(state); }
    }
}
