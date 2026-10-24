package com.vktea.feed;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Inert image requests and synthetic drawable dimensions; no transport or viewer is started. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class PostPhotosRendererTest {
    private final Context context = RuntimeEnvironment.getApplication();
    private final LinearLayout container = new LinearLayout(context);
    private final FakeImages images = new FakeImages();

    @Test public void placeholderUsesAvailableParentWidthThenUpdatesToRealLandscapeAspect() {
        container.setPadding(17, 0, 23, 0);
        PostPhotosRenderer.render(container, Collections.singletonList("synthetic-photo"), images);
        measure(container, 320);
        PostPhotoView photo = photo(0);
        assertEquals(280, photo.getWidth());
        assertEquals(158, photo.getHeight());
        images.complete(0, 1600, 900);
        measure(container, 320);
        assertEquals(158, photo.getHeight());
        images.complete(0, 1600, 400);
        measure(container, 320);
        assertEquals(70, photo.getHeight());
        assertEquals(ImageView.ScaleType.FIT_CENTER, photo.getScaleType());
    }

    @Test public void landscapePortraitAndSquareRetainDifferentHeightsAtNarrowAndWideWidths() {
        for (int width : new int[]{240, 320, 640, 1000}) {
            for (int[] source : new int[][]{{1600, 900}, {700, 1400}, {1000, 1000}}) {
                PostPhotosRenderer.render(container, Collections.singletonList("synthetic-photo"), images);
                images.complete(images.pending.size() - 1, source[0], source[1]);
                measure(container, width);
                assertEquals(width, photo(0).getWidth());
                assertEquals(Math.round(width * (float) source[1] / source[0]), photo(0).getHeight());
                assertEntireDrawableFits(photo(0), source[0], source[1]);
            }
        }
    }

    @Test public void tallPhotosFillWidthWithoutSideSpaceOrCropping() {
        container.setPadding(17, 0, 23, 0);
        for (int[] source : new int[][]{{600, 1250}, {600, 1800}, {600, 4800}}) {
            PostPhotosRenderer.render(container, Collections.singletonList("synthetic-tall"), images);
            images.complete(images.pending.size() - 1, source[0], source[1]);
            for (int parentWidth : new int[]{240, 320, 640, 1000}) {
                measure(container, parentWidth);
                PostPhotoView photo = photo(0);
                int width = parentWidth - 40;
                assertEquals(width, photo.getWidth());
                assertEquals(Math.round(width * (double) source[1] / source[0]), photo.getHeight());
                assertEntireDrawableFits(photo, source[0], source[1]);
                RectF actual = new RectF(0, 0, source[0], source[1]);
                photo.getImageMatrix().mapRect(actual);
                assertEquals("left edge fills the available width", 0, actual.left, .5f);
                assertEquals("right edge fills the available width", width, actual.right, .5f);
            }
        }
    }

    @Test public void everyPhotoStacksAtFullWidthAndKeepsItsOwnAspect() {
        PostPhotosRenderer.render(container, Arrays.asList("landscape", "portrait", "square"), images);
        images.complete(0, 1600, 900);
        images.complete(1, 700, 1400);
        images.complete(2, 500, 500);
        measure(container, 320);
        assertEquals(LinearLayout.VERTICAL, container.getOrientation());
        assertEquals(3, container.getChildCount());
        assertEquals(180, photo(0).getHeight());
        assertEquals(640, photo(1).getHeight());
        assertEquals(320, photo(2).getHeight());
        for (int i = 0; i < 3; i++) assertEquals(320, photo(i).getWidth());
        assertTrue(photo(1).getTop() > photo(0).getBottom());
        assertTrue(photo(2).getTop() > photo(1).getBottom());
    }

    @Test public void replacingClearsNestedRequestsAndIgnoresDetachedDrawableCallback() {
        ImageView legacyImage = new ImageView(context);
        LinearLayout legacyRow = new LinearLayout(context);
        legacyRow.addView(legacyImage);
        container.addView(legacyRow);
        PostPhotosRenderer.render(container, Collections.singletonList("old-photo"), images);
        assertTrue(images.cleared.contains(legacyImage));
        measure(container, 320);
        PostPhotoView old = photo(0);
        int previousHeight = old.getHeight();
        List<Integer> widthRequests = new ArrayList<>();
        old.loadAtMeasuredWidth(widthRequests::add);
        PostPhotosRenderer.render(container, Collections.singletonList("new-photo"), images);
        assertTrue(images.cleared.contains(old));
        assertNull(old.binding);
        assertFalse(old.performClick());
        images.complete(0, 100, 1000);
        old.measure(exact(320), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        old.layout(0, 0, 320, old.getMeasuredHeight());
        assertEquals(previousHeight, old.getHeight());
        assertEquals(Collections.singletonList(320), widthRequests);
        measure(container, 320);
        assertEquals(180, photo(0).getHeight());
        images.complete(1, 100, 200);
        measure(container, 320);
        assertEquals(640, photo(0).getHeight());
    }

    @Test public void imageRequestsFollowWidthChangesButAspectRelayoutDoesNotRepeatRequest() {
        PostPhotosRenderer.render(container, Collections.singletonList("synthetic-photo"), images);
        List<Integer> requestedWidths = new ArrayList<>();
        photo(0).loadAtMeasuredWidth(requestedWidths::add);
        measure(container, 320);
        images.complete(0, 700, 1400);
        measure(container, 320);
        measure(container, 640);
        assertEquals(Arrays.asList(320, 640), requestedWidths);
    }

    @Test public void eachClickOpensViewerWithOriginalAllUrlsAndItsIndex() {
        ArrayList<String> urls = new ArrayList<>(Arrays.asList("photo-a", "photo-b", "photo-c"));
        PostPhotosRenderer.render(container, urls, images);
        urls.clear();
        for (int i = 0; i < 3; i++) {
            assertTrue(photo(i).performClick());
            Intent intent = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
            assertNotNull(intent);
            assertEquals(PhotoViewerActivity.class.getName(), intent.getComponent().getClassName());
            assertEquals(i, intent.getIntExtra("index", -1));
            assertEquals(Arrays.asList("photo-a", "photo-b", "photo-c"), intent.getStringArrayListExtra("photos"));
        }
    }

    @Test public void clearingLibraryRemovesOldPhotosAndHidesEmptyContainer() {
        PostPhotosRenderer.render(container, Arrays.asList("a", "b"), images);
        PostPhotoView old = photo(0);
        PostPhotosRenderer.render(container, null, images);
        assertEquals(0, container.getChildCount());
        assertEquals(View.GONE, container.getVisibility());
        assertTrue(images.cleared.contains(old));
        PostPhotosRenderer.render(container, Arrays.asList(null, "", " ", "valid"), images);
        assertEquals(1, container.getChildCount());
        assertEquals(View.VISIBLE, container.getVisibility());
    }

    private PostPhotoView photo(int index) { return (PostPhotoView) container.getChildAt(index); }
    private static int exact(int size) { return View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY); }
    private static void measure(View view, int width) {
        view.measure(exact(width), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, width, view.getMeasuredHeight());
    }
    private static void assertEntireDrawableFits(PostPhotoView photo, int width, int height) {
        RectF actual = new RectF(0, 0, width, height);
        photo.getImageMatrix().mapRect(actual);
        assertTrue(actual.left >= -.01f && actual.top >= -.01f);
        assertTrue(actual.right <= photo.getWidth() + .01f && actual.bottom <= photo.getHeight() + .01f);
        assertEquals((double) width / height, actual.width() / actual.height(), .001);
    }
    private static final class Pending {
        final PostPhotoView photo;
        final PostPhotosRenderer.SizeReady callback;
        Pending(PostPhotoView photo, PostPhotosRenderer.SizeReady callback) { this.photo = photo; this.callback = callback; }
    }
    private static final class FakeImages implements PostPhotosRenderer.Images {
        final List<Pending> pending = new ArrayList<>();
        final List<ImageView> cleared = new ArrayList<>();
        @Override public void load(PostPhotoView photo, String url, PostPhotosRenderer.SizeReady ready) {
            pending.add(new Pending(photo, ready));
        }
        @Override public void clear(ImageView photo) { cleared.add(photo); photo.setImageDrawable(null); }
        void complete(int index, int width, int height) {
            Pending request = pending.get(index);
            if (request.photo.binding != null) request.photo.setImageDrawable(new SizedDrawable(width, height));
            request.callback.onSize(width, height);
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
