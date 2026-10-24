package com.vktea.feed;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.viewpager2.widget.ViewPager2;

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
        viewer.findViewById(R.id.viewer_save).performClick();
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
        viewer.findViewById(R.id.viewer_share).performClick();
        assertEquals("b", viewer.shared);
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

    @Test public void zoomLocksPagingUntilResetAndLoadErrorsDisableExports() {
        OfflineViewer viewer = open(0, "a", "b");
        ViewPager2 pager = viewer.findViewById(R.id.photo_pager);
        viewer.callback.onZoomChanged(0, true);
        assertFalse(pager.isUserInputEnabled());
        assertTrue(viewer.findViewById(R.id.viewer_reset).isEnabled());
        viewer.callback.onZoomChanged(0, false);
        assertTrue(pager.isUserInputEnabled());
        viewer.callback.onLoadStateChanged(0, PhotoPagerAdapter.LoadState.ERROR);
        assertFalse(viewer.findViewById(R.id.viewer_save).isEnabled());
        assertFalse(viewer.findViewById(R.id.viewer_share).isEnabled());
        viewer.callback.onLoadStateChanged(1, PhotoPagerAdapter.LoadState.READY);
        assertFalse(viewer.findViewById(R.id.viewer_save).isEnabled());
        viewer.callback.onLoadStateChanged(0, PhotoPagerAdapter.LoadState.READY);
        assertTrue(viewer.findViewById(R.id.viewer_save).isEnabled());
    }

    @Test public void shareUsesContentStreamAndTemporaryReadGrantWithoutRecipient() {
        Uri uri = Uri.parse("content://com.vktea.photo-files/shared/photo.png");
        Intent share = PhotoViewerActivity.shareIntent(uri, "image/png");
        assertEquals(Intent.ACTION_SEND, share.getAction());
        assertEquals("image/png", share.getType());
        assertEquals(uri, share.getParcelableExtra(Intent.EXTRA_STREAM));
        assertEquals(uri, share.getClipData().getItemAt(0).getUri());
        assertTrue((share.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        assertEquals(0, share.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        assertNull(share.getPackage());
        assertNull(share.getComponent());
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
        String saved, shared;
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
        @Override protected void onSharePhoto(String source) { shared = source; }
    }
}
