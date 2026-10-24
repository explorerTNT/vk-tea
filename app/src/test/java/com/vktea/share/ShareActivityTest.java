package com.vktea.share;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
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

    @Test public void initialFourChoicesAndOwnWallNeedSeparateFinalConfirmation() {
        OfflineShare activity = open(attachment());
        assertEquals(0, repository.lookups());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.share_destinations).getVisibility());
        assertEquals(View.GONE, activity.findViewById(R.id.share_footer).getVisibility());
        assertTrue(activity.findViewById(R.id.share_friends).isEnabled());
        assertTrue(activity.findViewById(R.id.share_dialog).isEnabled());
        activity.findViewById(R.id.share_own_wall).performClick();
        assertEquals(0, repository.lookups()); assertEquals(0, repository.writes.size());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.share_confirmation_panel).getVisibility());
        ((EditText) activity.findViewById(R.id.share_draft)).setText("Synthetic wall comment");
        activity.findViewById(R.id.share_confirm).performClick();
        activity.findViewById(R.id.share_confirm).performClick();
        assertEquals(1, repository.writes.size());
        assertEquals(0, repository.groups.get(0).longValue());
        assertEquals("Synthetic wall comment", repository.drafts.get(0));
        assertFalse(activity.findViewById(R.id.share_confirm).isEnabled());
        repository.writes.get(0).success(100L);
        assertTrue(activity.isFinishing());
    }

    @Test public void communitySelectionIsReadOnlyUntilPublishAndUsesTheChosenGroup() {
        OfflineShare activity = open(attachment());
        activity.findViewById(R.id.share_community).performClick();
        assertEquals(1, repository.communityCalls); assertEquals(0, repository.writes.size());
        repository.communities.success(Collections.singletonList(new PhotoShareRepository.Community(71, "Synthetic editor community", "")));
        LinearLayout rows = activity.findViewById(R.id.share_targets);
        assertEquals(1, rows.getChildCount());
        rows.getChildAt(0).performClick();
        assertTrue(text(activity, R.id.share_destination).contains("Synthetic editor community"));
        assertEquals(0, repository.writes.size());
        activity.findViewById(R.id.share_confirm).performClick();
        assertEquals(71, repository.groups.get(0).longValue());
        assertEquals("wall-42_17", repository.attachments.get(0).object());
    }

    @Test public void friendWithoutConversationCanBeChosenEvenWhenWallRepostIsDenied() {
        OfflineShare activity = open(ShareAttachment.post(-42, 17, "synthetic-key", false));
        assertFalse(activity.findViewById(R.id.share_own_wall).isEnabled());
        assertFalse(activity.findViewById(R.id.share_community).isEnabled());
        activity.findViewById(R.id.share_friends).performClick();
        assertEquals(1, repository.friendCalls); assertEquals(0, repository.dialogCalls);
        repository.recipients.success(Collections.singletonList(new PhotoShareRepository.Recipient(83, "Synthetic friend", "")));
        ((LinearLayout) activity.findViewById(R.id.share_targets)).getChildAt(0).performClick();
        assertEquals(0, repository.writes.size());
        activity.findViewById(R.id.share_confirm).performClick();
        assertEquals(83, repository.peers.get(0).longValue());
        assertTrue(repository.ids.get(0) != 0);
        assertEquals(0, repository.groups.size());
    }

    @Test public void dialogSearchIsAReadAndChoosingAResultDoesNotSend() {
        OfflineShare activity = open(attachment());
        activity.findViewById(R.id.share_dialog).performClick();
        assertEquals(1, repository.dialogCalls);
        ((EditText) activity.findViewById(R.id.share_query)).setText("Synthetic query");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));
        assertEquals(2, repository.dialogCalls);
        assertEquals("Synthetic query", repository.query);
        repository.recipients.success(Collections.singletonList(new PhotoShareRepository.Recipient(2_000_000_123L, "Synthetic chat", "")));
        ((LinearLayout) activity.findViewById(R.id.share_targets)).getChildAt(0).performClick();
        assertEquals(0, repository.writes.size());
        assertEquals(View.GONE, activity.findViewById(R.id.share_targets_panel).getVisibility());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.share_footer).getVisibility());
    }

    @Test public void rotationRetainsDraftAndPendingSendWithoutAnotherLookupOrWrite() {
        OfflineShare activity = chooseDialog();
        ((EditText) activity.findViewById(R.id.share_draft)).setText("Synthetic retained draft");
        controller.recreate(); activity = controller.get();
        assertEquals("Synthetic retained draft", text(activity, R.id.share_draft));
        assertEquals(1, repository.dialogCalls);
        activity.findViewById(R.id.share_confirm).performClick();
        controller.recreate(); activity = controller.get();
        assertEquals(1, repository.writes.size());
        assertFalse(activity.findViewById(R.id.share_draft).isEnabled());
        activity.findViewById(R.id.share_confirm).performClick();
        activity.getOnBackPressedDispatcher().onBackPressed();
        assertEquals(1, repository.writes.size());
        assertFalse(activity.isFinishing());
        repository.writes.get(0).success(100L);
        assertTrue(activity.isFinishing());
    }

    @Test public void pausedSuccessfulSendFinishesOnRecreationInsteadOfReopeningChooser() {
        OfflineShare activity = chooseDialog();
        activity.findViewById(R.id.share_confirm).performClick();
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
        ((EditText) activity.findViewById(R.id.share_draft)).setText("Synthetic exact message");
        activity.findViewById(R.id.share_confirm).performClick();
        repository.writes.get(0).error(new PhotoSocialError(PhotoSocialError.Kind.NETWORK));
        assertEquals(1, repository.writes.size());
        assertFalse(activity.findViewById(R.id.share_draft).isEnabled());
        assertTrue(activity.findViewById(R.id.share_confirm).isEnabled());
        assertEquals(activity.getString(R.string.share_unknown_send), text(activity, R.id.share_confirm_status));
        activity.findViewById(R.id.share_confirm).performClick();
        assertEquals(2, repository.writes.size());
        assertEquals(repository.ids.get(0), repository.ids.get(1));
        assertEquals(repository.drafts.get(0), repository.drafts.get(1));
        assertEquals(repository.peers.get(0), repository.peers.get(1));
    }

    @Test public void uncertainWallFailureCannotBeBlindlyResubmittedAndBackReturnsToChoices() {
        OfflineShare activity = open(attachment());
        activity.findViewById(R.id.share_own_wall).performClick();
        activity.findViewById(R.id.share_confirm).performClick();
        repository.writes.get(0).error(new PhotoSocialError(PhotoSocialError.Kind.NETWORK));
        assertFalse(activity.findViewById(R.id.share_confirm).isEnabled());
        activity.findViewById(R.id.share_confirm).performClick();
        assertEquals(1, repository.writes.size());
        assertEquals(activity.getString(R.string.share_unknown_publish), text(activity, R.id.share_confirm_status));
        activity.getOnBackPressedDispatcher().onBackPressed();
        assertEquals(View.VISIBLE, activity.findViewById(R.id.share_destinations).getVisibility());
        activity.getOnBackPressedDispatcher().onBackPressed();
        assertTrue(activity.isFinishing());
    }

    @Test public void lookupFailureHasManualReadRetryAndNeverPublishes() {
        OfflineShare activity = open(attachment());
        activity.findViewById(R.id.share_community).performClick();
        repository.communities.error(new PhotoSocialError(PhotoSocialError.Kind.RATE_LIMITED));
        assertEquals(View.VISIBLE, activity.findViewById(R.id.share_retry).getVisibility());
        assertEquals(activity.getString(R.string.share_error_rate), text(activity, R.id.share_status));
        activity.findViewById(R.id.share_retry).performClick();
        assertEquals(2, repository.communityCalls);
        assertEquals(0, repository.writes.size());
    }

    @Test @Config(sdk = 35, qualifiers = "w320dp-h640dp")
    public void narrowImeViewportKeepsFinalButtonVisibleAndTextFieldsAvoidFullscreenEditor() {
        android.content.res.Resources resources = RuntimeEnvironment.getApplication().getResources();
        android.content.res.Configuration configuration = new android.content.res.Configuration(resources.getConfiguration());
        configuration.fontScale = 1.5f;
        resources.updateConfiguration(configuration, resources.getDisplayMetrics());
        OfflineShare activity = chooseDialog();
        View root = activity.findViewById(R.id.share_root);
        ViewCompat.dispatchApplyWindowInsets(root, new WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(10, 24, 14, 32))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 280)).build());
        root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 320, 640);
        assertEquals(280, root.getPaddingBottom());
        assertEquals(10, root.getPaddingLeft()); assertEquals(14, root.getPaddingRight());
        View footer = activity.findViewById(R.id.share_footer);
        assertTrue(footer.getBottom() <= 360);
        assertTrue(activity.findViewById(R.id.share_confirm).getHeight() >= 48);
        for (int id : new int[]{R.id.share_query, R.id.share_draft}) {
            int options = ((EditText) activity.findViewById(id)).getImeOptions();
            assertTrue((options & EditorInfo.IME_FLAG_NO_FULLSCREEN) != 0);
            assertTrue((options & EditorInfo.IME_FLAG_NO_EXTRACT_UI) != 0);
        }
    }

    private OfflineShare chooseDialog() {
        OfflineShare activity = open(attachment());
        activity.findViewById(R.id.share_dialog).performClick();
        repository.recipients.success(Collections.singletonList(new PhotoShareRepository.Recipient(83, "Synthetic recipient", "")));
        ((LinearLayout) activity.findViewById(R.id.share_targets)).getChildAt(0).performClick();
        return activity;
    }
    private OfflineShare open(ShareAttachment attachment) {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), OfflineShare.class).putExtra(ShareActivity.EXTRA_ATTACHMENT, attachment);
        controller = Robolectric.buildActivity(OfflineShare.class, intent).setup().visible();
        shadowOf(Looper.getMainLooper()).idle();
        return controller.get();
    }
    private static ShareAttachment attachment() { return ShareAttachment.post(-42, 17, "synthetic-key", true); }
    private static String text(OfflineShare activity, int id) { return ((TextView) activity.findViewById(id)).getText().toString(); }

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
        int dialogCalls, friendCalls, communityCalls;
        String query;
        Callback<List<Recipient>> recipients;
        Callback<List<Community>> communities;
        final List<Callback<Long>> writes = new ArrayList<>();
        final List<ShareAttachment> attachments = new ArrayList<>();
        final List<Long> groups = new ArrayList<>(), peers = new ArrayList<>();
        final List<Integer> ids = new ArrayList<>();
        final List<String> drafts = new ArrayList<>();
        int lookups() { return dialogCalls + friendCalls + communityCalls; }
        @Override public Request recipients(String query, Callback<List<Recipient>> callback) {
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
