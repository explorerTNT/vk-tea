package com.vktea.feed;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.github.chrisbanes.photoview.PhotoView;
import com.vktea.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Real PhotoView geometry/gestures, with every image request replaced by a synthetic loader. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class PhotoPagerAdapterTest {
    private final FakeImages images = new FakeImages();
    private final Events events = new Events();
    private final PhotoPagerAdapter adapter = new PhotoPagerAdapter(Arrays.asList("photo-a", "photo-b"), events, images);

    @Test public void initialLoadWaitsForActualViewportAndUsesBoundedDecode() {
        PhotoPagerAdapter.Holder holder = create();
        adapter.onBindViewHolder(holder, 0);
        assertEquals(0, images.requests.size());
        assertEquals(View.VISIBLE, holder.loading.getVisibility());
        assertEquals(View.GONE, holder.error.getVisibility());
        measure(holder.itemView, 320, 640);
        assertEquals(1, images.requests.size());
        assertEquals(640, images.requests.get(0).width);
        assertEquals(1280, images.requests.get(0).height);
        images.ready(0, 1200, 800);
        assertEquals(View.GONE, holder.loading.getVisibility());
        assertEquals(PhotoPagerAdapter.LoadState.READY, holder.state);
        assertEquals(Arrays.asList("0:LOADING", "0:READY"), events.states);
        adapter.release();

        PhotoPagerAdapter.Holder large = create();
        adapter.onBindViewHolder(large, 1);
        measure(large.itemView, 3000, 7000);
        Request request = images.requests.get(1);
        assertEquals(4096, request.width);
        assertEquals(4096, request.height);
        assertTrue((long) request.width * request.height <= 4096L * 4096);
    }

    @Test public void wideAndPortraitPhotosFitWithoutCroppingOrLeavingFitScale() {
        for (int[] viewport : new int[][]{{280, 560}, {640, 280}}) {
            for (int[] source : new int[][]{{1600, 900}, {700, 1400}}) {
                PhotoPagerAdapter.Holder holder = bind(0, viewport[0], viewport[1]);
                images.ready(images.requests.size() - 1, source[0], source[1]);
                RectF image = holder.photo.getDisplayRect();
                assertNotNull(image);
                assertTrue(image.left >= -.01f && image.top >= -.01f);
                assertTrue(image.right <= holder.photo.getWidth() + .01f);
                assertTrue(image.bottom <= holder.photo.getHeight() + .01f);
                assertEquals((double) source[0] / source[1], image.width() / image.height(), .001);
                assertEquals(1f, holder.photo.getScale(), .001f);
                adapter.onViewRecycled(holder);
            }
        }
    }

    @Test public void failureOffersManualRetryAndIgnoresOldCallbackAfterRetry() {
        PhotoPagerAdapter.Holder holder = bind(0, 320, 640);
        Request old = images.requests.get(0);
        old.result.failed();
        assertEquals(View.GONE, holder.loading.getVisibility());
        assertEquals(View.VISIBLE, holder.error.getVisibility());
        assertEquals(PhotoPagerAdapter.LoadState.ERROR, holder.state);
        assertTrue(holder.retry.performClick());
        assertEquals(2, images.requests.size());
        assertEquals(View.VISIBLE, holder.loading.getVisibility());
        assertEquals(View.GONE, holder.error.getVisibility());
        old.result.ready();
        assertEquals(PhotoPagerAdapter.LoadState.LOADING, holder.state);
        images.ready(1, 1200, 800);
        assertEquals(PhotoPagerAdapter.LoadState.READY, holder.state);
        assertEquals(2, images.requests.size());
    }

    @Test public void zoomCallbackUsesUpdatedMatrixAndResetRestoresPagingState() {
        PhotoPagerAdapter.Holder holder = bind(0, 320, 640);
        images.ready(0, 1200, 800);
        holder.photo.setScale(2f, false);
        assertEquals(Collections.singletonList("0:true"), events.zoom);
        assertEquals(2f, holder.photo.getScale(), .001f);
        adapter.resetZoom(0);
        assertEquals(Arrays.asList("0:true", "0:false"), events.zoom);
        assertEquals(1f, holder.photo.getScale(), .001f);
        assertEquals(4f, holder.photo.getMaximumScale(), .001f);
    }

    @Test public void zoomedViewportResizeKeepsScaleAndNormalizedImageCenterUntilExplicitReset() {
        PhotoPagerAdapter.Holder holder = bind(0, 320, 560);
        images.ready(0, 700, 1400);
        holder.photo.setScale(2f, 110, 200, false);
        float[] before = normalizedCenter(holder.photo, 700, 1400);
        measure(holder.itemView, 320, 680);
        assertEquals(2f, holder.photo.getScale(), .001f);
        float[] hidden = normalizedCenter(holder.photo, 700, 1400);
        assertEquals(before[0], hidden[0], .002f);
        assertEquals(before[1], hidden[1], .002f);
        measure(holder.itemView, 320, 560);
        float[] shown = normalizedCenter(holder.photo, 700, 1400);
        assertEquals(2f, holder.photo.getScale(), .001f);
        assertEquals(before[0], shown[0], .002f);
        assertEquals(before[1], shown[1], .002f);
        assertEquals("resize must not briefly unlock paging", Collections.singletonList("0:true"), events.zoom);
        assertEquals(1, images.requests.size());
        adapter.resetZoom(0);
        assertEquals(1f, holder.photo.getScale(), .001f);
        assertEquals(Arrays.asList("0:true", "0:false"), events.zoom);
    }

    @Test public void fittedLandscapeAndPortraitRemainFittedThroughChromeResize() {
        for (int[] source : new int[][]{{1600, 900}, {700, 1400}}) {
            PhotoPagerAdapter.Holder holder = bind(0, 320, 560);
            images.ready(images.requests.size() - 1, source[0], source[1]);
            for (int height : new int[]{680, 560}) {
                measure(holder.itemView, 320, height);
                assertEquals(1f, holder.photo.getScale(), .001f);
                RectF image = holder.photo.getDisplayRect();
                assertTrue(image.left >= -.01f && image.top >= -.01f);
                assertTrue(image.right <= holder.photo.getWidth() + .01f);
                assertTrue(image.bottom <= holder.photo.getHeight() + .01f);
            }
            adapter.onViewRecycled(holder);
        }
    }

    @Test public void singleTapTogglesChromeOnceAndDoesNotRetryOrRemovePage() {
        PhotoPagerAdapter.Holder holder = bind(0, 320, 640);
        images.ready(0, 1200, 800);
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 160, 320, 0);
        holder.photo.dispatchTouchEvent(down);
        down.recycle();
        shadowOf(Looper.getMainLooper()).idleFor(40, TimeUnit.MILLISECONDS);
        MotionEvent up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, 160, 320, 0);
        holder.photo.dispatchTouchEvent(up);
        up.recycle();
        shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS);
        assertEquals(1, events.toggles);
        assertEquals(2, adapter.getItemCount());
        assertEquals(1, images.requests.size());
        assertEquals(PhotoPagerAdapter.LoadState.READY, holder.state);
    }

    @Test public void recyclingZoomedPageClearsImageAndDoesNotLeakScaleOrCallbacksToNextPhoto() {
        PhotoPagerAdapter.Holder holder = bind(0, 320, 640);
        images.ready(0, 1200, 800);
        holder.photo.setScale(2f, false);
        Request old = images.requests.get(0);
        adapter.onBindViewHolder(holder, 1);
        assertTrue(images.cleared.contains(holder.photo));
        assertEquals(1f, holder.photo.getScale(), .001f);
        assertEquals("photo-b", images.requests.get(1).url);
        old.result.failed();
        assertEquals(PhotoPagerAdapter.LoadState.LOADING, holder.state);
        images.ready(1, 700, 1400);
        assertEquals(PhotoPagerAdapter.LoadState.READY, holder.state);
        assertEquals(1f, holder.photo.getScale(), .001f);
        assertEquals(Arrays.asList("0:true", "0:false"), events.zoom);
        adapter.onViewRecycled(holder);
        assertEquals(-1, holder.position);
        old.result.ready();
        assertNull(holder.state);
    }

    @Test public void detachCancelsRequestAndAttachReloadsWhileChromeResizeKeepsCurrentImage() {
        PhotoPagerAdapter.Holder holder = bind(0, 320, 640);
        Request old = images.requests.get(0);
        adapter.onViewDetachedFromWindow(holder);
        old.result.failed();
        assertEquals(PhotoPagerAdapter.LoadState.LOADING, holder.state);
        adapter.onViewAttachedToWindow(holder);
        assertEquals(2, images.requests.size());
        images.ready(1, 1200, 800);
        measure(holder.itemView, 320, 760);
        assertEquals("hiding chrome must not create another image request", 2, images.requests.size());
        adapter.release();
        images.requests.get(1).result.failed();
        assertNull(holder.state);
    }

    @Test public void chromeSitsOutsidePhotoAndHidingItExpandsViewportWithAccessibleButtons() {
        for (float fontScale : new float[]{1f, 1.5f}) {
            View root = LayoutInflater.from(context(fontScale)).inflate(R.layout.activity_photo_viewer, null, false);
            measure(root, 280, 560);
            View top = root.findViewById(R.id.viewer_top), middle = root.findViewById(R.id.viewer_middle);
            View bottom = root.findViewById(R.id.viewer_bottom);
            assertTrue(top.getBottom() <= middle.getTop());
            assertTrue(middle.getBottom() <= bottom.getTop());
            for (int id : new int[]{R.id.viewer_back, R.id.viewer_more, R.id.viewer_previous,
                    R.id.viewer_next, R.id.viewer_save, R.id.viewer_share, R.id.viewer_reset}) {
                View control = root.findViewById(id);
                int minimum = Math.round(48 * root.getResources().getDisplayMetrics().density);
                assertTrue(control.getWidth() >= minimum && control.getHeight() >= minimum);
                assertNotNull(control.getContentDescription());
            }
            for (int id : new int[]{R.id.viewer_save, R.id.viewer_share, R.id.viewer_reset}) {
                TextView label = root.findViewById(id);
                assertNotNull(label.getLayout());
                assertTrue(label.getHeight() >= label.getCompoundPaddingTop() + label.getLayout().getHeight() + label.getCompoundPaddingBottom());
            }
            int priorHeight = middle.getHeight();
            top.setVisibility(View.GONE);
            bottom.setVisibility(View.GONE);
            measure(root, 280, 560);
            assertTrue(middle.getHeight() > priorHeight);
            assertEquals(root.getHeight(), middle.getHeight());
        }
    }

    private PhotoPagerAdapter.Holder create() { return adapter.onCreateViewHolder(new FrameLayout(context(1f)), 0); }
    private PhotoPagerAdapter.Holder bind(int position, int width, int height) {
        PhotoPagerAdapter.Holder holder = create();
        adapter.onBindViewHolder(holder, position);
        measure(holder.itemView, width, height);
        return holder;
    }
    private static Context context(float scale) {
        Configuration config = new Configuration(RuntimeEnvironment.getApplication().getResources().getConfiguration());
        config.fontScale = scale;
        return new ContextThemeWrapper(RuntimeEnvironment.getApplication().createConfigurationContext(config), R.style.Theme_VKTea);
    }
    private static void measure(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }
    private static float[] normalizedCenter(PhotoView photo, int imageWidth, int imageHeight) {
        Matrix inverse = new Matrix(), display = new Matrix();
        photo.getDisplayMatrix(display);
        assertTrue(display.invert(inverse));
        float[] point = {photo.getWidth() / 2f, photo.getHeight() / 2f};
        inverse.mapPoints(point);
        point[0] /= imageWidth;
        point[1] /= imageHeight;
        return point;
    }
    private static final class Events implements PhotoPagerAdapter.Callback {
        int toggles;
        final List<String> states = new ArrayList<>(), zoom = new ArrayList<>();
        @Override public void onToggleChrome() { toggles++; }
        @Override public void onZoomChanged(int position, boolean value) { zoom.add(position + ":" + value); }
        @Override public void onLoadStateChanged(int position, PhotoPagerAdapter.LoadState state) { states.add(position + ":" + state); }
    }
    private static final class Request {
        final PhotoView target;
        final String url;
        final int width, height;
        final PhotoPagerAdapter.Result result;
        Request(PhotoView target, String url, int width, int height, PhotoPagerAdapter.Result result) {
            this.target = target; this.url = url; this.width = width; this.height = height; this.result = result;
        }
    }
    private static final class FakeImages implements PhotoPagerAdapter.Images {
        final List<Request> requests = new ArrayList<>();
        final List<PhotoView> cleared = new ArrayList<>();
        @Override public void load(PhotoView target, String url, int width, int height, PhotoPagerAdapter.Result result) {
            requests.add(new Request(target, url, width, height, result));
        }
        @Override public void clear(PhotoView target) { cleared.add(target); target.setImageDrawable(null); }
        void ready(int index, int width, int height) {
            Request request = requests.get(index);
            request.target.setImageDrawable(new SizedDrawable(width, height));
            request.result.ready();
        }
    }
    private static final class SizedDrawable extends Drawable {
        final int width, height;
        SizedDrawable(int width, int height) { this.width = width; this.height = height; }
        @Override public int getIntrinsicWidth() { return width; }
        @Override public int getIntrinsicHeight() { return height; }
        @Override public void draw(Canvas canvas) { }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
