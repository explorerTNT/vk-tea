package com.vktea.feed;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.viewpager2.widget.ViewPager2;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.github.chrisbanes.photoview.PhotoView;
import com.vktea.R;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Tests use inert image requests and action seams; no session, VK API or exports are touched. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class PhotoViewerActivityTest {
    private ActivityController<OfflineViewer> controller;

    @After public void destroy() { if (controller != null) controller.close(); }

    @Test public void emptyInputClosesSafely() {
        OfflineViewer viewer = open(0);
        assertTrue(viewer.isFinishing());
    }

    @Test public void invalidEntriesPreserveRequestedPhotoAndClampOutOfRangeIndex() {
        OfflineViewer viewer = open(3, "a", null, " ", "b", "c");
        assertEquals("2 из 3", counter(viewer));
        assertEquals(Arrays.asList("a", "b", "c"), viewer.sources);
        viewer.onSavePhoto(viewer.sources.get(1));
        assertEquals("b", viewer.saved);
        controller.close();
        controller = null;
        viewer = open(999, "a", "b");
        assertEquals("2 из 2", counter(viewer));
    }

    @Test public void navigationAndActionAlwaysUseCurrentPhoto() {
        OfflineViewer viewer = open(0, "a", "b", "c");
        assertEquals(View.GONE, viewer.findViewById(R.id.viewer_previous).getVisibility());
        viewer.findViewById(R.id.viewer_next).performClick();
        settle(viewer);
        assertEquals("2 из 3", counter(viewer));
        viewer.copyPhotoLink();
        ClipboardManager clipboard = (ClipboardManager) viewer.getSystemService(Context.CLIPBOARD_SERVICE);
        assertEquals("b", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        viewer.findViewById(R.id.viewer_next).performClick();
        settle(viewer);
        assertEquals(View.GONE, viewer.findViewById(R.id.viewer_next).getVisibility());
    }

    @Test public void chromeToggleKeepsViewerOpenAndRestoresAfterRecreation() {
        OfflineViewer viewer = open(1, "a", "b", "c");
        viewer.callback.onToggleChrome();
        assertEquals(View.GONE, viewer.findViewById(R.id.viewer_top).getVisibility());
        assertFalse(viewer.isFinishing());
        controller.recreate();
        viewer = controller.get();
        settle(viewer);
        assertEquals("2 из 3", counter(viewer));
        assertEquals(View.GONE, viewer.findViewById(R.id.viewer_bottom).getVisibility());
        viewer.callback.onToggleChrome();
        assertEquals(View.VISIBLE, viewer.findViewById(R.id.viewer_top).getVisibility());
        viewer.findViewById(R.id.viewer_back).performClick();
        assertTrue(viewer.isFinishing());
    }

    @Test public void zoomLocksPagingAndUrlOnlyInputHasNoInventedSocialActions() {
        OfflineViewer viewer = open(0, "a", "b");
        ViewPager2 pager = viewer.findViewById(R.id.photo_pager);
        viewer.callback.onZoomChanged(0, true);
        assertFalse(pager.isUserInputEnabled());
        viewer.callback.onZoomChanged(0, false);
        assertTrue(pager.isUserInputEnabled());
        viewer.callback.onLoadStateChanged(0, PhotoPagerAdapter.LoadState.ERROR);
        assertFalse(viewer.findViewById(R.id.viewer_like).isEnabled());
        assertFalse(viewer.findViewById(R.id.viewer_comments).isEnabled());
        assertFalse(viewer.findViewById(R.id.viewer_repost).isEnabled());
        viewer.callback.onLoadStateChanged(1, PhotoPagerAdapter.LoadState.READY);
        assertFalse(viewer.findViewById(R.id.viewer_like).isEnabled());
        viewer.callback.onLoadStateChanged(0, PhotoPagerAdapter.LoadState.READY);
        assertFalse(viewer.findViewById(R.id.viewer_like).isEnabled());
        assertEquals(0, viewer.repo.loads);
    }

    @Test public void vkFooterTargetsSelectedPhotoAndUsesVkLink() {
        ArrayList<ViewerPhoto> photos = new ArrayList<>();
        photos.add(photo("a", 101));
        photos.add(photo("b", 102));
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), OfflineViewer.class)
                .putParcelableArrayListExtra(ViewerPhoto.EXTRA_PHOTOS, photos).putExtra("index", 1);
        controller = Robolectric.buildActivity(OfflineViewer.class, intent).setup().visible();
        OfflineViewer viewer = controller.get();
        settle(viewer);
        assertEquals("2 из 2", counter(viewer));
        assertTrue(viewer.findViewById(R.id.viewer_like).isEnabled());
        assertTrue(viewer.findViewById(R.id.viewer_comments).isEnabled());
        assertTrue(viewer.findViewById(R.id.viewer_repost).isEnabled());
        viewer.findViewById(R.id.viewer_like).performClick();
        settle(viewer);
        assertEquals(102, viewer.repo.lastPhoto.id);
        assertEquals(1, viewer.repo.likes);
        assertEquals("8", ((TextView) viewer.findViewById(R.id.viewer_like_count)).getText().toString());
        viewer.copyPhotoLink();
        ClipboardManager clipboard = (ClipboardManager) viewer.getSystemService(Context.CLIPBOARD_SERVICE);
        assertEquals("https://vk.com/photo-51_102", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        assertNull(shadowOf(viewer).getNextStartedActivity());
        viewer.findViewById(R.id.viewer_comments).performClick();
        Intent discussion = shadowOf(viewer).getNextStartedActivity();
        assertEquals(PhotoDiscussionActivity.class.getName(), discussion.getComponent().getClassName());
        ViewerPhoto selected = discussion.getParcelableExtra("photo");
        assertEquals(102, selected.id);
        assertEquals(8, selected.likes);
        viewer.findViewById(R.id.viewer_repost).performClick();
        settle(viewer);
        Intent sharing = shadowOf(viewer).getNextStartedActivity();
        assertEquals(com.vktea.share.ShareActivity.class.getName(), sharing.getComponent().getClassName());
        com.vktea.share.ShareAttachment attachment = sharing.getParcelableExtra(com.vktea.share.ShareActivity.EXTRA_ATTACHMENT);
        assertEquals(102, attachment.id);
        assertEquals("photo", attachment.type);
        assertNull(shadowOf(viewer).getNextStartedActivity());
    }

    private static ViewerPhoto photo(String url, long id) {
        return new ViewerPhoto(url, id, -51, "synthetic", 7, 3, 2, false, true, true, true);
    }

    private OfflineViewer open(int index, String... sources) {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), OfflineViewer.class)
                .putStringArrayListExtra("photos", new ArrayList<>(Arrays.asList(sources))).putExtra("index", index);
        controller = Robolectric.buildActivity(OfflineViewer.class, intent).setup().visible();
        OfflineViewer viewer = controller.get();
        if (!viewer.isFinishing()) settle(viewer);
        return viewer;
    }

    private static void settle(OfflineViewer viewer) {
        View root = viewer.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 400, 800);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static String counter(OfflineViewer viewer) {
        return ((TextView) viewer.findViewById(R.id.viewer_counter)).getText().toString();
    }

    public static class OfflineViewer extends PhotoViewerActivity {
        PhotoPagerAdapter.Callback callback;
        List<String> sources;
        String saved;
        FakeRepo repo = new FakeRepo();
        @Override protected PhotoSocialViewModel createPhotoSocialViewModel() {
            return new ViewModelProvider(this, new ViewModelProvider.Factory() {
                @Override public <T extends ViewModel> T create(Class<T> modelClass) {
                    return modelClass.cast(new PhotoSocialViewModel(getApplication(), repo));
                }
            }).get(PhotoSocialViewModel.class);
        }
        @Override protected PhotoPagerAdapter createPhotoAdapter(List<String> sources, PhotoPagerAdapter.Callback callback) {
            this.callback = callback;
            this.sources = new ArrayList<>(sources);
            return new PhotoPagerAdapter(sources, callback, new PhotoPagerAdapter.Images() {
                @Override public void load(PhotoView target, String source, int width, int height, PhotoPagerAdapter.Result result) {
                    target.setImageDrawable(new ColorDrawable(0xff778899));
                    result.ready();
                }
                @Override public void clear(PhotoView target) { target.setImageDrawable(null); }
            });
        }
        @Override protected void onSavePhoto(String source) { saved = source; }
    }

    static final class FakeRepo implements PhotoSocialRepository {
        int loads, likes;
        ViewerPhoto lastPhoto;
        @Override public Request load(ViewerPhoto photo, Callback<ViewerPhoto> callback) {
            loads++;
            new Handler(Looper.getMainLooper()).post(() -> callback.success(photo));
            return () -> { };
        }
        @Override public Request like(ViewerPhoto photo, boolean liked, Callback<Integer> callback) {
            likes++;
            lastPhoto = photo;
            new Handler(Looper.getMainLooper()).post(() -> callback.success(liked ? 8 : 7));
            return () -> { };
        }
        @Override public Request comments(ViewerPhoto photo, Callback<Comments> callback) { throw new AssertionError("No read without click"); }
        @Override public Request comment(ViewerPhoto photo, String text, Callback<Long> callback) { throw new AssertionError("No send without click"); }
        @Override public Request repost(ViewerPhoto photo, String text, Callback<Integer> callback) { throw new AssertionError("No publication without click"); }
        @Override public void close() { }
    }
}
