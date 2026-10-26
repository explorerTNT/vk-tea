package com.vktea.feed;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.os.Looper;
import android.view.View;
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

import java.util.Collections;

/** Synthetic photo IDs, inert image binders and held callbacks keep every test offline. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class PhotoDiscussionActivityTest {
    private ActivityController<OfflineDiscussion> controller;
    private FakeRepo repository;

    @Before public void prepare() { OfflineDiscussion.repository = repository = new FakeRepo(); }
    @After public void destroy() {
        if (controller != null) controller.close();
        OfflineDiscussion.repository = null;
    }

    @Test public void missingPhotoClosesWithoutAnyRequests() {
        open(null);
        assertTrue(controller.get().isFinishing());
        assertEquals(0, repository.loads + repository.owners + repository.reads + repository.writes);
    }

    @Test public void photoEntityHeaderAndCommentsAreInlineAndNeverSubmitOnOpening() {
        OfflineDiscussion screen = open(photo());
        assertEquals(1, repository.loads);
        assertEquals(1, repository.owners);
        assertEquals(0, repository.reads);
        repository.owner.success(new PhotoSocialRepository.Owner("Synthetic author", "file:///avatar"));
        repository.metadata.success(metadata(true));
        assertEquals(1, repository.reads);
        repository.comments.success(comments());
        assertEquals("Synthetic author", text(screen, R.id.discussion_author));
        assertEquals("Synthetic photo caption", text(screen, R.id.discussion_caption));
        assertEquals(View.VISIBLE, screen.findViewById(R.id.discussion_date).getVisibility());
        assertEquals("Последние 1 из 9 комментариев", text(screen, R.id.discussion_comments_title));
        assertTrue(((TextView) ((LinearLayout) screen.findViewById(R.id.discussion_comments)).getChildAt(0))
                .getText().toString().contains("Synthetic comment"));
        assertEquals(17, screen.rendered.id);
        assertEquals(-91, repository.lastPhoto.ownerId);
        assertEquals(0, repository.writes);
    }

    @Test public void rotationRetainsReadAndDraftAndDoesNotResubmitPendingWrite() {
        OfflineDiscussion screen = open(photo());
        repository.metadata.success(metadata(true));
        repository.comments.success(comments());
        ((EditText) screen.findViewById(R.id.discussion_input)).setText("  Synthetic draft  ");
        controller.recreate();
        screen = controller.get();
        assertEquals(1, repository.loads);
        assertEquals(1, repository.owners);
        assertEquals(1, repository.reads);
        assertEquals("  Synthetic draft  ", text(screen, R.id.discussion_input));
        screen.findViewById(R.id.discussion_send).performClick();
        screen.findViewById(R.id.discussion_send).performClick();
        assertEquals(1, repository.writes);
        assertEquals("Synthetic draft", repository.sent);
        assertFalse(screen.findViewById(R.id.discussion_send).isEnabled());
        controller.recreate();
        screen = controller.get();
        assertEquals(1, repository.writes);
        assertFalse(screen.findViewById(R.id.discussion_input).isEnabled());
        repository.write.success(101L);
        assertEquals("", text(screen, R.id.discussion_input));
        assertEquals(2, repository.reads);
        controller.recreate();
        assertEquals(1, repository.writes);
        assertEquals(2, repository.reads);
    }

    @Test public void failedCommentRetainsDraftAndRetryReadsWithoutRepeatingWrite() {
        OfflineDiscussion screen = open(photo());
        repository.metadata.success(metadata(true));
        repository.comments.success(comments());
        ((EditText) screen.findViewById(R.id.discussion_input)).setText("Synthetic retained draft");
        screen.findViewById(R.id.discussion_send).performClick();
        repository.write.error(new PhotoSocialError(PhotoSocialError.Kind.NETWORK));
        assertEquals("Synthetic retained draft", text(screen, R.id.discussion_input));
        assertEquals(View.VISIBLE, screen.findViewById(R.id.discussion_retry).getVisibility());
        assertEquals(screen.getString(R.string.photo_social_error_network), text(screen, R.id.discussion_status));
        screen.findViewById(R.id.discussion_retry).performClick();
        assertEquals(2, repository.reads);
        assertEquals(1, repository.writes);
        assertEquals(1, ((LinearLayout) screen.findViewById(R.id.discussion_comments)).getChildCount());
    }

    @Test public void metadataFailureHasExplicitRetryAndDeniedComposerStillReadsComments() {
        OfflineDiscussion screen = open(photo());
        repository.metadata.error(new PhotoSocialError(PhotoSocialError.Kind.ACCESS));
        assertEquals(0, repository.reads);
        screen.findViewById(R.id.discussion_retry).performClick();
        assertEquals(2, repository.loads);
        repository.metadata.success(metadata(false));
        assertEquals(1, repository.reads);
        repository.comments.success(comments());
        assertFalse(screen.findViewById(R.id.discussion_input).isEnabled());
        assertFalse(screen.findViewById(R.id.discussion_send).isEnabled());
        assertEquals(1, ((LinearLayout) screen.findViewById(R.id.discussion_comments)).getChildCount());
        assertEquals(0, repository.writes);
    }

    @Test public void urlOnlyPhotoIsVisibleWithoutInventedVkRequests() {
        OfflineDiscussion screen = open(ViewerPhoto.fromUrl("file:///synthetic"));
        assertEquals("file:///synthetic", screen.rendered.url);
        assertEquals(0, repository.loads + repository.owners + repository.reads + repository.writes);
        assertFalse(screen.findViewById(R.id.discussion_input).isEnabled());
        assertEquals(screen.getString(R.string.photo_discussion_unavailable), text(screen, R.id.discussion_status));
    }

    @Test public void knownOwnerNameWithoutAvatarStillLoadsAuthorPhotoOnceAcrossRotation() {
        ViewerPhoto source = photo();
        OfflineDiscussion screen = open(new ViewerPhoto(source.url, source.id, source.ownerId, source.accessKey,
                source.likes, source.comments, source.reposts, source.liked, source.canLike, source.canComment,
                source.canRepost, "Known synthetic author", "", source.date, source.text));
        assertEquals(1, repository.owners);
        repository.owner.success(new PhotoSocialRepository.Owner("", "file:///resolved-avatar"));
        assertEquals("Known synthetic author", text(screen, R.id.discussion_author));
        controller.recreate();
        assertEquals(1, repository.owners);
        assertEquals("file:///resolved-avatar", controller.get().boundAvatar);
    }

    @Test @Config(sdk = 35)
    public void imeAndCutoutLeaveComposerInsideUsableViewport() {
        OfflineDiscussion screen = open(photo());
        View root = screen.findViewById(R.id.discussion_root);
        ViewCompat.dispatchApplyWindowInsets(root, new WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 24, 0, 32))
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(12, 0, 16, 0))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 280)).build());
        root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 320, 700);
        assertEquals(12, root.getPaddingLeft());
        assertEquals(16, root.getPaddingRight());
        assertEquals(24, root.getPaddingTop());
        assertEquals(280, root.getPaddingBottom());
        View composer = screen.findViewById(R.id.discussion_composer);
        assertTrue(composer.getBottom() <= 420);
        assertTrue(composer.getHeight() >= 48);
    }

    private OfflineDiscussion open(ViewerPhoto photo) {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), OfflineDiscussion.class);
        if (photo != null) intent.putExtra(PhotoDiscussionActivity.EXTRA_PHOTO, photo);
        controller = Robolectric.buildActivity(OfflineDiscussion.class, intent).setup().visible();
        shadowOf(Looper.getMainLooper()).idle();
        return controller.get();
    }
    private static String text(OfflineDiscussion screen, int id) { return ((TextView) screen.findViewById(id)).getText().toString(); }
    private static ViewerPhoto photo() {
        return new ViewerPhoto("file:///synthetic", 17, -91, "synthetic-key", 2, 9, 0, false,
                true, true, true, "", "", 1700000000, "Synthetic photo caption");
    }
    private static ViewerPhoto metadata(boolean canComment) {
        // Old callers and partial server responses must not erase caption/date/author enrichment.
        return new ViewerPhoto("file:///synthetic", 17, -91, "synthetic-key", 2, 9, 0, false,
                true, canComment, true);
    }
    private static PhotoSocialRepository.Comments comments() {
        return new PhotoSocialRepository.Comments(9, Collections.singletonList(
                new PhotoSocialRepository.Comment(1, 42, "Synthetic commenter", "Synthetic comment", 1700000001)));
    }

    public static class OfflineDiscussion extends PhotoDiscussionActivity {
        static FakeRepo repository;
        ViewerPhoto rendered;
        String boundAvatar;
        @Override protected PhotoSocialViewModel createPhotoSocialViewModel() {
            return new ViewModelProvider(this, new ViewModelProvider.Factory() {
                @Override public <T extends ViewModel> T create(Class<T> type) {
                    return type.cast(new PhotoSocialViewModel(getApplication(), repository));
                }
            }).get(PhotoSocialViewModel.class);
        }
        @Override protected void bindOwnerAvatar(ImageView view, String url, boolean group) {
            boundAvatar = url;
            view.setImageDrawable(new ColorDrawable(0xff778899));
        }
        @Override protected void renderPhoto(LinearLayout container, ViewerPhoto photo) {
            rendered = photo;
            container.removeAllViews();
            View image = new View(this);
            container.addView(image, new LinearLayout.LayoutParams(-1, 160));
        }
    }

    private static final class FakeRepo implements PhotoSocialRepository {
        int loads, owners, reads, writes;
        String sent;
        ViewerPhoto lastPhoto;
        Callback<ViewerPhoto> metadata;
        Callback<Owner> owner;
        Callback<Comments> comments;
        Callback<Long> write;
        @Override public Request load(ViewerPhoto photo, Callback<ViewerPhoto> callback) {
            loads++; lastPhoto = photo; metadata = callback; return () -> { };
        }
        @Override public Request owner(ViewerPhoto photo, Callback<Owner> callback) {
            owners++; owner = callback; return () -> { };
        }
        @Override public Request comments(ViewerPhoto photo, Callback<Comments> callback) {
            reads++; lastPhoto = photo; comments = callback; return () -> { };
        }
        @Override public Request comment(ViewerPhoto photo, String text, Callback<Long> callback) {
            writes++; lastPhoto = photo; sent = text; write = callback; return () -> { };
        }
        @Override public Request like(ViewerPhoto photo, boolean liked, Callback<Integer> callback) { throw new AssertionError("No implicit like"); }
        @Override public Request repost(ViewerPhoto photo, String text, Callback<Integer> callback) { throw new AssertionError("No implicit repost"); }
        @Override public void close() { }
    }
}
