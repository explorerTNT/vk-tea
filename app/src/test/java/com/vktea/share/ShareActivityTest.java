package com.vktea.share;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.vktea.R;
import com.vktea.feed.PhotoShareRepository;
import com.vktea.feed.PhotoSocialError;
import com.vktea.feed.ViewerPhoto;

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
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Every lookup/write is held in memory; images are inert and no VK account is accessed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class ShareActivityTest {
    private ActivityController<OfflineShare> controller;
    private FakeRepository repository;
    @Before public void setup() { OfflineShare.repository = repository = new FakeRepository(); }
    @After public void close() { if (controller != null) controller.close(); OfflineShare.repository = null; }

    @Test public void missingIdentityClosesWithoutLoadingOrSending() {
        OfflineShare activity = open(new ShareAttachment("photo", 0, 0, "", false));
        assertTrue(activity.isFinishing());
        assertEquals(0, repository.lookups()); assertEquals(0, repository.writes.size());
    }

    @Test public void compactBottomPanelPreloadsRecentsAndOwnWallStillNeedsFinalConfirmation() {
        OfflineShare activity = open(attachment());
        assertEquals(0, repository.lookups());
        assertEquals(1, repository.recentCalls);
        assertTrue(dialog().isShowing());
        assertEquals(4, ((LinearLayout) view(activity, R.id.share_actions)).getChildCount());
        assertEquals(View.VISIBLE, view(activity, R.id.share_header_search).getVisibility());
        assertEquals(View.VISIBLE, view(activity, R.id.share_destinations).getVisibility());
        assertEquals(View.GONE, view(activity, R.id.share_footer).getVisibility());
        assertTrue(view(activity, R.id.share_friends).isEnabled());
        assertTrue(view(activity, R.id.share_dialog).isEnabled());
        view(activity, R.id.share_own_wall).performClick();
        assertEquals(0, repository.lookups()); assertEquals(0, repository.writes.size());
        assertEquals(View.VISIBLE, view(activity, R.id.share_confirmation_panel).getVisibility());
        ((EditText) view(activity, R.id.share_draft)).setText("Synthetic wall comment");
        view(activity, R.id.share_confirm).performClick();
        view(activity, R.id.share_confirm).performClick();
        assertEquals(1, repository.writes.size());
        assertEquals(0, repository.groups.get(0).longValue());
        assertEquals("Synthetic wall comment", repository.drafts.get(0));
        assertFalse(view(activity, R.id.share_confirm).isEnabled());
        repository.writes.get(0).success(100L);
        assertTrue(activity.isFinishing());
    }

    @Test public void communitySelectionIsReadOnlyUntilPublishAndUsesTheChosenGroup() {
        OfflineShare activity = open(attachment());
        view(activity, R.id.share_community).performClick();
        assertEquals(1, repository.communityCalls); assertEquals(0, repository.writes.size());
        repository.communities.success(Collections.singletonList(new PhotoShareRepository.Community(71, "Synthetic editor community", "")));
        LinearLayout rows = view(activity, R.id.share_targets);
        assertEquals(1, rows.getChildCount());
        rows.getChildAt(0).performClick();
        assertTrue(text(activity, R.id.share_destination).contains("Synthetic editor community"));
        assertEquals(0, repository.writes.size());
        view(activity, R.id.share_confirm).performClick();
        assertEquals(71, repository.groups.get(0).longValue());
        assertEquals("wall-42_17", repository.attachments.get(0).object());
    }

    @Test public void communityHeaderSearchFiltersCurrentDestinationWithoutLoadingDialogs() {
        OfflineShare activity = open(attachment());
        view(activity, R.id.share_community).performClick();
        repository.communities.success(Collections.singletonList(new PhotoShareRepository.Community(71, "Synthetic community", "")));
        view(activity, R.id.share_header_search).performClick();
        ((EditText) view(activity, R.id.share_query)).setText("Synthetic");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));
        assertEquals(0, repository.dialogCalls);
        assertEquals(1, repository.communityCalls);
        assertEquals(View.VISIBLE, view(activity, R.id.share_targets_panel).getVisibility());
        assertEquals(1, ((LinearLayout) view(activity, R.id.share_targets)).getChildCount());
        assertEquals(activity.getString(R.string.share_communities_title), text(activity, R.id.share_targets_heading));
        assertEquals(0, repository.writes.size());
    }

    @Test public void recentAvatarOnlyOpensConfirmationAndBackRestoresCachedCompactRow() {
        OfflineShare activity = open(attachment());
        repository.recents.success(Collections.singletonList(new PhotoShareRepository.Recipient(83, "Synthetic recent", "")));
        LinearLayout recent = view(activity, R.id.share_recent_targets);
        assertEquals(1, recent.getChildCount());
        recent.getChildAt(0).performClick();
        assertEquals(0, repository.dialogCalls);
        assertEquals(0, repository.writes.size());
        assertEquals(View.VISIBLE, view(activity, R.id.share_confirmation_panel).getVisibility());
        activity.getOnBackPressedDispatcher().onBackPressed();
        assertEquals(View.VISIBLE, view(activity, R.id.share_destinations).getVisibility());
        assertEquals(1, ((LinearLayout) view(activity, R.id.share_recent_targets)).getChildCount());
        controller.recreate();
        assertEquals(1, repository.recentCalls);
        assertEquals(1, ((LinearLayout) view(controller.get(), R.id.share_recent_targets)).getChildCount());
    }

    @Test public void copyActionUsesCanonicalVkLinkWithoutSendingAndFinishesCarrier() {
        OfflineShare activity = open(attachment());
        view(activity, R.id.share_copy).performClick();
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        assertEquals("https://vk.com/wall-42_17", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        assertEquals(0, repository.writes.size());
        assertTrue(activity.isFinishing());
    }

    @Test public void outsideDismissClosesIdleCarrierWithoutSending() {
        OfflineShare activity = open(attachment());
        dialog().cancel();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(activity.isFinishing());
        assertEquals(0, repository.writes.size());
    }

    @Test public void friendWithoutConversationCanBeChosenEvenWhenWallRepostIsDenied() {
        OfflineShare activity = open(ShareAttachment.post(-42, 17, "synthetic-key", false));
        assertFalse(view(activity, R.id.share_own_wall).isEnabled());
        assertFalse(view(activity, R.id.share_community).isEnabled());
        view(activity, R.id.share_more).performClick();
        assertEquals(View.VISIBLE, view(activity, R.id.share_more_panel).getVisibility());
        view(activity, R.id.share_friends).performClick();
        assertEquals(1, repository.friendCalls); assertEquals(0, repository.dialogCalls);
        repository.recipients.success(Collections.singletonList(new PhotoShareRepository.Recipient(83, "Synthetic friend", "")));
        ((LinearLayout) view(activity, R.id.share_targets)).getChildAt(0).performClick();
        assertEquals(0, repository.writes.size());
        view(activity, R.id.share_confirm).performClick();
        assertEquals(83, repository.peers.get(0).longValue());
        assertTrue(repository.ids.get(0) != 0);
        assertEquals(0, repository.groups.size());
    }

    @Test public void dialogSearchIsAReadAndChoosingAResultDoesNotSend() {
        OfflineShare activity = open(attachment());
        view(activity, R.id.share_more).performClick();
        view(activity, R.id.share_dialog).performClick();
        assertEquals(1, repository.dialogCalls);
        ((EditText) view(activity, R.id.share_query)).setText("Synthetic query");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));
        assertEquals(2, repository.dialogCalls);
        assertEquals("Synthetic query", repository.query);
        repository.recipients.success(Collections.singletonList(new PhotoShareRepository.Recipient(2_000_000_123L, "Synthetic chat", "")));
        ((LinearLayout) view(activity, R.id.share_targets)).getChildAt(0).performClick();
        assertEquals(0, repository.writes.size());
        assertEquals(View.GONE, view(activity, R.id.share_targets_panel).getVisibility());
        assertEquals(View.VISIBLE, view(activity, R.id.share_footer).getVisibility());
    }

    @Test public void rotationRetainsDraftAndPendingSendWithoutAnotherLookupOrWrite() {
        OfflineShare activity = chooseDialog();
        ((EditText) view(activity, R.id.share_draft)).setText("Synthetic retained draft");
        controller.recreate(); activity = controller.get();
        assertEquals("Synthetic retained draft", text(activity, R.id.share_draft));
        assertEquals(1, repository.dialogCalls);
        view(activity, R.id.share_confirm).performClick();
        controller.recreate(); activity = controller.get();
        assertEquals(1, repository.writes.size());
        assertFalse(view(activity, R.id.share_draft).isEnabled());
        view(activity, R.id.share_confirm).performClick();
        activity.getOnBackPressedDispatcher().onBackPressed();
        assertEquals(1, repository.writes.size());
        assertFalse(activity.isFinishing());
        repository.writes.get(0).success(100L);
        assertTrue(activity.isFinishing());
    }

    @Test public void pausedSuccessfulSendFinishesOnRecreationInsteadOfReopeningChooser() {
        OfflineShare activity = chooseDialog();
        view(activity, R.id.share_confirm).performClick();
        controller.pause().stop();
        repository.writes.get(0).success(100L);
        controller.recreate();
        if (!controller.get().getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
            controller.start().resume();
        assertTrue(controller.get().isFinishing());
        assertEquals(1, repository.dialogCalls);
        assertEquals(1, repository.writes.size());
    }

    @Test public void failedDmCanOnlyRetryExplicitlyWithTheSameFrozenPayloadAndRandomId() {
        OfflineShare activity = chooseDialog();
        ((EditText) view(activity, R.id.share_draft)).setText("Synthetic exact message");
        view(activity, R.id.share_confirm).performClick();
        repository.writes.get(0).error(new PhotoSocialError(PhotoSocialError.Kind.NETWORK));
        assertEquals(1, repository.writes.size());
        assertFalse(view(activity, R.id.share_draft).isEnabled());
        assertTrue(view(activity, R.id.share_confirm).isEnabled());
        assertEquals(activity.getString(R.string.share_unknown_send), text(activity, R.id.share_confirm_status));
        view(activity, R.id.share_confirm).performClick();
        assertEquals(2, repository.writes.size());
        assertEquals(repository.ids.get(0), repository.ids.get(1));
        assertEquals(repository.drafts.get(0), repository.drafts.get(1));
        assertEquals(repository.peers.get(0), repository.peers.get(1));
    }

    @Test public void uncertainWallFailureCannotBeBlindlyResubmittedAndBackReturnsToChoices() {
        OfflineShare activity = open(attachment());
        view(activity, R.id.share_own_wall).performClick();
        view(activity, R.id.share_confirm).performClick();
        repository.writes.get(0).error(new PhotoSocialError(PhotoSocialError.Kind.NETWORK));
        assertFalse(view(activity, R.id.share_confirm).isEnabled());
        view(activity, R.id.share_confirm).performClick();
        assertEquals(1, repository.writes.size());
        assertEquals(activity.getString(R.string.share_unknown_publish), text(activity, R.id.share_confirm_status));
        activity.getOnBackPressedDispatcher().onBackPressed();
        assertEquals(View.VISIBLE, view(activity, R.id.share_destinations).getVisibility());
        activity.getOnBackPressedDispatcher().onBackPressed();
        assertTrue(activity.isFinishing());
    }

    @Test public void lookupFailureHasManualReadRetryAndNeverPublishes() {
        OfflineShare activity = open(attachment());
        view(activity, R.id.share_community).performClick();
        repository.communities.error(new PhotoSocialError(PhotoSocialError.Kind.RATE_LIMITED));
        assertEquals(View.VISIBLE, view(activity, R.id.share_retry).getVisibility());
        assertEquals(activity.getString(R.string.share_error_rate), text(activity, R.id.share_status));
        view(activity, R.id.share_retry).performClick();
        assertEquals(2, repository.communityCalls);
        assertEquals(0, repository.writes.size());
    }

    @Test @Config(qualifiers = "w320dp-h640dp")
    public void compactPanelUsesScrollableViewportAndTextFieldsAvoidFullscreenEditor() {
        android.content.res.Resources resources = RuntimeEnvironment.getApplication().getResources();
        android.content.res.Configuration configuration = new android.content.res.Configuration(resources.getConfiguration());
        configuration.fontScale = 1.5f;
        resources.updateConfiguration(configuration, resources.getDisplayMetrics());
        OfflineShare activity = chooseDialog();
        SharePanelScrollView viewport = view(activity, R.id.share_viewport);
        ((EditText) view(activity, R.id.share_draft)).setText("Synthetic first line\nSecond line\nThird line\nFourth line\nFifth line\nSixth line");
        viewport.setMaximumHeight(100);
        viewport.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.AT_MOST));
        viewport.layout(0, 0, viewport.getMeasuredWidth(), viewport.getMeasuredHeight());
        assertTrue(viewport.getHeight() <= 100);
        assertTrue(viewport.canScrollVertically(1));
        assertEquals(View.VISIBLE, view(activity, R.id.share_footer).getVisibility());
        assertTrue(view(activity, R.id.share_confirm).isEnabled());
        for (int id : new int[]{R.id.share_query, R.id.share_draft}) {
            int options = ((EditText) view(activity, id)).getImeOptions();
            assertTrue((options & EditorInfo.IME_FLAG_NO_FULLSCREEN) != 0);
            assertTrue((options & EditorInfo.IME_FLAG_NO_EXTRACT_UI) != 0);
        }
    }

    @Test @Config(qualifiers = "w320dp-h320dp-mdpi")
    public void focusedComposerFitsFinalButtonIn140PixelUsableWindow() {
        OfflineShare activity = chooseDialog();
        View root = view(activity, R.id.share_root);
        root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST));
        root.layout(0, 0, 320, root.getMeasuredHeight());
        android.view.Display display = ShadowDisplay.getDefaultDisplay();
        int oldHeight = display.getHeight();
        try {
            shadowOf(display).setHeight(140);
            view(activity, R.id.share_draft).requestFocus();
            root.getViewTreeObserver().dispatchOnGlobalLayout();
            // Isolate the usable area after the production visible-frame budget was applied.
            // An edge-to-edge window may reserve additional obscured pixels below this area.
            root.setPadding(0, 0, 0, 0);
            root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(140, View.MeasureSpec.AT_MOST));
            root.layout(0, 0, 320, root.getMeasuredHeight());
            View footer = view(activity, R.id.share_footer);
            View button = view(activity, R.id.share_confirm);
            assertTrue("Footer must fit above IME", footer.getBottom() <= 140);
            assertTrue("The full confirm button must remain in the usable area", footer.getTop() + button.getBottom() <= 140);
            assertTrue(button.getHeight() >= 48);
            assertTrue("The viewport may use less than the former 48px minimum", view(activity, R.id.share_viewport).getHeight() < 48);
        } finally { shadowOf(display).setHeight(oldHeight); }
    }

    private OfflineShare chooseDialog() {
        OfflineShare activity = open(attachment());
        view(activity, R.id.share_more).performClick();
        view(activity, R.id.share_dialog).performClick();
        repository.recipients.success(Collections.singletonList(new PhotoShareRepository.Recipient(83, "Synthetic recipient", "")));
        ((LinearLayout) view(activity, R.id.share_targets)).getChildAt(0).performClick();
        return activity;
    }
    private OfflineShare open(ShareAttachment attachment) {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), OfflineShare.class).putExtra(ShareActivity.EXTRA_ATTACHMENT, attachment);
        controller = Robolectric.buildActivity(OfflineShare.class, intent).setup().visible();
        shadowOf(Looper.getMainLooper()).idle();
        return controller.get();
    }
    private static ShareAttachment attachment() { return ShareAttachment.post(-42, 17, "synthetic-key", true); }
    private static String text(OfflineShare activity, int id) { return ((TextView) view(activity, id)).getText().toString(); }
    private static BottomSheetDialog dialog() { return (BottomSheetDialog) ShadowDialog.getLatestDialog(); }
    private static <T extends View> T view(OfflineShare activity, int id) { return dialog().findViewById(id); }

    public static class OfflineShare extends ShareActivity {
        static FakeRepository repository;
        @Override protected ShareViewModel createShareViewModel() {
            return new ViewModelProvider(this, new ViewModelProvider.Factory() {
                @Override public <T extends ViewModel> T create(Class<T> type) {
                    return type.cast(new ShareViewModel(getApplication(), repository));
                }
            }).get(ShareViewModel.class);
        }
        @Override protected void bindAvatar(ImageView view, String url, boolean group) { view.setImageDrawable(new ColorDrawable(0xff778899)); }
    }
    private static final class FakeRepository implements PhotoShareRepository {
        int dialogCalls, friendCalls, communityCalls, recentCalls;
        String query;
        Callback<List<Recipient>> recipients;
        Callback<List<Recipient>> recents;
        Callback<List<Community>> communities;
        final List<Callback<Long>> writes = new ArrayList<>();
        final List<ShareAttachment> attachments = new ArrayList<>();
        final List<Long> groups = new ArrayList<>(), peers = new ArrayList<>();
        final List<Integer> ids = new ArrayList<>();
        final List<String> drafts = new ArrayList<>();
        int lookups() { return dialogCalls + friendCalls + communityCalls; }
        @Override public Request recipients(String query, Callback<List<Recipient>> callback) {
            if (recentCalls == 0) { recentCalls++; recents = callback; return () -> { }; }
            dialogCalls++; this.query = query; recipients = callback; return () -> { };
        }
        @Override public Request friends(String query, Callback<List<Recipient>> callback) {
            friendCalls++; this.query = query; recipients = callback; return () -> { };
        }
        @Override public Request communities(Callback<List<Community>> callback) {
            communityCalls++; communities = callback; return () -> { };
        }
        @Override public Request send(ShareAttachment attachment, Recipient target, int id, String draft, Callback<Long> callback) {
            attachments.add(attachment); peers.add(target.peerId); ids.add(id); drafts.add(draft); writes.add(callback); return () -> { };
        }
        @Override public Request repost(ShareAttachment attachment, long group, String draft, Callback<Long> callback) {
            attachments.add(attachment); groups.add(group); drafts.add(draft); writes.add(callback); return () -> { };
        }
        @Override public Request send(ViewerPhoto photo, Recipient target, int id, String draft, Callback<Long> callback) {
            throw new AssertionError("Generic UI must use the neutral attachment contract");
        }
        @Override public void close() { }
    }
}
