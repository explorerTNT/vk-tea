package com.vktea.feed;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.vktea.R;

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

/** Synthetic drawable sizes and inert image callbacks test the real feed binder without network. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w320dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class FeedPostContentTest {
    private final Context context = new androidx.appcompat.view.ContextThemeWrapper(
            RuntimeEnvironment.getApplication(), R.style.Theme_VKTea);
    private final FeedAdapter adapter = new FeedAdapter(null);
    private final BudgetList list = new BudgetList(context);
    private final FakeImages images = new FakeImages();

    @Test public void shortPostRemainsNaturalAndOnlyOneMoreFollowsTheWholeBody() {
        FeedAdapter.PostViewHolder holder = bind(post("Synthetic short text"));
        measure(holder.itemView, 320);
        assertEquals(holder.content.getNaturalContentHeight(), holder.content.getHeight());
        assertEquals(View.GONE, holder.more.getVisibility());
        assertEquals(View.GONE, holder.collapseTop.getVisibility());
        assertEquals(Integer.MAX_VALUE, holder.text.getMaxLines());
        LinearLayout root = (LinearLayout) holder.itemView;
        assertEquals(root.indexOfChild(holder.content) + 1, root.indexOfChild(holder.more));
        assertSame(holder.content.getChildAt(0), holder.photoContainer.getParent());
        assertSame(holder.content.getChildAt(0), holder.repostBlock.getParent());
        assertTrue(holder.likes.getParent() != holder.content.getChildAt(0));
    }

    @Test public void tallSinglePhotoKeepsItsFullWidthAndAspectBehindTheClippedViewport() {
        list.setPadding(0, 20, 0, 40);
        FeedAdapter.PostViewHolder holder = bind(post(""));
        PostPhotosRenderer.render(holder.photoContainer, Collections.singletonList("synthetic-tall"), images);
        images.complete(0, 200, 1600);
        measure(holder.itemView, 320);
        PostPhotoView photo = (PostPhotoView) holder.photoContainer.getChildAt(0);
        assertEquals(324, holder.content.getHeightBudget());
        assertEquals(324, holder.content.getHeight());
        assertEquals(320, photo.getWidth());
        assertEquals(2560, photo.getHeight());
        assertTrue(holder.content.getNaturalContentHeight() >= 2560);
        assertEquals(View.VISIBLE, holder.more.getVisibility());
        assertEquals("Показать полностью", holder.more.getText().toString());
        assertFullWidthDrawable(photo, 200, 1600);
    }

    @Test public void mainRepostTextVideoAndCardsUseOneSharedBudgetAndExpandWithoutImageReload() {
        Post post = post("Synthetic caption\nSecond line\nThird line");
        post.repostText = "Synthetic repost text";
        FeedAdapter.PostViewHolder holder = bind(post);
        PostPhotosRenderer.render(holder.photoContainer, Arrays.asList("main-one", "main-two", "main-three"), images);
        PostPhotosRenderer.render(holder.repostPhotos, Arrays.asList("repost-one", "repost-two"), images);
        for (int i = 0; i < 5; i++) images.complete(i, 1600, 900);
        holder.videoBlock.setVisibility(View.VISIBLE);
        holder.videoBlock.setLayoutParams(new LinearLayout.LayoutParams(-1, 100));
        holder.cards.setVisibility(View.VISIBLE);
        holder.cards.addView(new View(context), new LinearLayout.LayoutParams(-1, 80));
        measure(holder.itemView, 320);
        assertEquals(360, holder.content.getHeight());
        assertTrue(holder.content.getNaturalContentHeight() > 1000);
        View firstMain = holder.photoContainer.getChildAt(0), firstRepost = holder.repostPhotos.getChildAt(0);
        int loads = images.loads;
        holder.more.performClick();
        assertEquals(View.VISIBLE, holder.collapseTop.getVisibility());
        assertEquals("Свернуть", holder.collapseTop.getText().toString());
        LinearLayout root = (LinearLayout) holder.itemView;
        assertEquals(root.indexOfChild(holder.collapseTop) + 1, root.indexOfChild(holder.content));
        measure(holder.itemView, 320);
        assertTrue(post.expanded);
        assertEquals(holder.content.getNaturalContentHeight(), holder.content.getHeight());
        assertEquals(View.VISIBLE, holder.more.getVisibility());
        assertEquals("Свернуть", holder.more.getText().toString());
        holder.collapseTop.performClick();
        measure(holder.itemView, 320);
        assertFalse(post.expanded);
        assertEquals(View.GONE, holder.collapseTop.getVisibility());
        assertEquals(holder.content.getHeightBudget(), holder.content.getHeight());
        assertEquals(View.VISIBLE, holder.more.getVisibility());
        assertEquals("Показать полностью", holder.more.getText().toString());
        holder.more.performClick();
        measure(holder.itemView, 320);
        assertTrue(post.expanded);
        assertEquals(holder.content.getNaturalContentHeight(), holder.content.getHeight());
        assertEquals("Свернуть", holder.more.getText().toString());
        assertSame(firstMain, holder.photoContainer.getChildAt(0));
        assertSame(firstRepost, holder.repostPhotos.getChildAt(0));
        assertEquals(loads, images.loads);
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
    }

    @Test public void asynchronousPhotoSizeShowsAndRemovesOverflowWithoutRebinding() {
        FeedAdapter.PostViewHolder holder = bind(post(""));
        PostPhotosRenderer.render(holder.photoContainer, Collections.singletonList("synthetic-late"), images);
        measure(holder.itemView, 320);
        assertEquals(View.GONE, holder.more.getVisibility());
        images.complete(0, 100, 1200);
        assertTrue(holder.content.isLayoutRequested());
        measure(holder.itemView, 320);
        assertEquals(View.VISIBLE, holder.more.getVisibility());
        images.complete(0, 1600, 400);
        measure(holder.itemView, 320);
        assertEquals(View.GONE, holder.more.getVisibility());
        assertEquals(holder.content.getNaturalContentHeight(), holder.content.getHeight());
        assertEquals(1, images.loads);
    }

    @Test public void expandedPostSurvivesRebindWhileRecycledHolderCannotExpandAnotherPost() {
        Post first = post(String.join("\n", Collections.nCopies(40, "Synthetic text line")));
        Post second = post("Short second post");
        adapter.addAll(Arrays.asList(first, second));
        FeedAdapter.PostViewHolder holder = holder();
        adapter.onBindViewHolder(holder, 0);
        measure(holder.itemView, 320);
        assertEquals(View.VISIBLE, holder.more.getVisibility());
        holder.more.performClick();
        measure(holder.itemView, 320);
        assertTrue(first.expanded);
        adapter.onBindViewHolder(holder, 1);
        measure(holder.itemView, 320);
        assertFalse(second.expanded);
        assertEquals(View.GONE, holder.collapseTop.getVisibility());
        assertEquals(View.GONE, holder.more.getVisibility());
        assertEquals(holder.content.getNaturalContentHeight(), holder.content.getHeight());
        adapter.onBindViewHolder(holder, 0);
        first.likes++;
        adapter.onBindViewHolder(holder, 0);
        measure(holder.itemView, 320);
        assertTrue(first.expanded);
        assertEquals(holder.content.getNaturalContentHeight(), holder.content.getHeight());
        assertEquals(View.VISIBLE, holder.collapseTop.getVisibility());
        assertEquals(View.VISIBLE, holder.more.getVisibility());
        assertEquals("Свернуть", holder.more.getText().toString());
        holder.more.performClick();
        adapter.onBindViewHolder(holder, 0);
        measure(holder.itemView, 320);
        assertFalse(first.expanded);
        assertEquals(View.GONE, holder.collapseTop.getVisibility());
        assertEquals(holder.content.getHeightBudget(), holder.content.getHeight());
        assertEquals("Показать полностью", holder.more.getText().toString());
        adapter.onViewRecycled(holder);
        assertNull(holder.boundPost);
        assertFalse(holder.more.performClick());
        assertFalse(holder.collapseTop.performClick());
        assertEquals(View.GONE, holder.more.getVisibility());
    }

    @Test public void detachedOldImageCallbackCannotChangeOverflowAfterRebind() {
        adapter.addAll(Arrays.asList(post(""), post("Short new post")));
        FeedAdapter.PostViewHolder holder = holder();
        adapter.onBindViewHolder(holder, 0);
        PostPhotosRenderer.render(holder.photoContainer, Collections.singletonList("synthetic-old"), images);
        measure(holder.itemView, 320);
        PostPhotoView old = (PostPhotoView) holder.photoContainer.getChildAt(0);
        adapter.onBindViewHolder(holder, 1);
        images.complete(0, 1, 10000);
        measure(holder.itemView, 320);
        assertNull(old.binding);
        assertEquals(0, holder.photoContainer.getChildCount());
        assertEquals(View.GONE, holder.more.getVisibility());
    }

    @Test public void widthAndParentViewportChangesRecomputeBudgetWithoutConstrainingPhoto() {
        FeedAdapter.PostViewHolder holder = bind(post(""));
        PostPhotosRenderer.render(holder.photoContainer, Collections.singletonList("synthetic-portrait"), images);
        images.complete(0, 100, 200);
        measure(holder.itemView, 320);
        assertEquals(360, holder.content.getHeightBudget());
        sizeList(640, 300);
        measure(holder.itemView, 640);
        assertEquals(180, holder.content.getHeightBudget());
        assertEquals(180, holder.content.getHeight());
        assertEquals(1280, holder.photoContainer.getChildAt(0).getHeight());
        assertEquals(640, holder.photoContainer.getChildAt(0).getWidth());
        sizeList(640, 2000);
        // This unattached fixture tests budget calculation; attached lists request this from their layout listener.
        holder.content.requestLayout();
        measure(holder.itemView, 640);
        assertEquals(480, holder.content.getHeightBudget());
        assertEquals(1, images.loads);
    }

    @Test public void sharedProfileAndDetailLayoutIsUnrestrictedUntilFeedExplicitlyEnablesFolding() {
        View item = LayoutInflater.from(context).inflate(R.layout.item_post, new FrameLayout(context), false);
        FeedPostContent viewport = item.findViewById(R.id.post_content);
        LinearLayout photos = item.findViewById(R.id.post_photos);
        PostPhotosRenderer.render(photos, Collections.singletonList("synthetic-unrestricted"), images);
        images.complete(0, 100, 800);
        measure(item, 320);
        assertTrue(viewport.getHeight() >= 2560);
        assertEquals(viewport.getNaturalContentHeight(), viewport.getHeight());
        assertEquals(View.GONE, item.findViewById(R.id.post_more).getVisibility());
        assertEquals(View.GONE, item.findViewById(R.id.post_collapse_top).getVisibility());
    }

    private FeedAdapter.PostViewHolder bind(Post post) {
        adapter.addAll(Collections.singletonList(post));
        FeedAdapter.PostViewHolder holder = holder();
        adapter.onBindViewHolder(holder, 0);
        return holder;
    }
    private FeedAdapter.PostViewHolder holder() {
        list.setLayoutManager(new LinearLayoutManager(context));
        sizeList(320, 600);
        FeedAdapter.PostViewHolder holder = adapter.onCreateViewHolder(list, 0);
        list.addView(holder.itemView);
        return holder;
    }
    private void sizeList(int width, int height) {
        list.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, width, height);
    }
    private static void measure(View view, int width) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, width, view.getMeasuredHeight());
    }
    private static Post post(String text) {
        return new Post(42, "Synthetic source", null, text, 0, Collections.emptyList(),
                0, 0, 0, 0, false, Post.TYPE_POST, 42, 17);
    }
    private static void assertFullWidthDrawable(PostPhotoView view, int width, int height) {
        assertEquals(ImageView.ScaleType.FIT_CENTER, view.getScaleType());
        RectF mapped = new RectF(0, 0, width, height);
        view.getImageMatrix().mapRect(mapped);
        assertEquals(0, mapped.left, .5f); assertEquals(view.getWidth(), mapped.right, .5f);
        assertEquals(0, mapped.top, .5f); assertEquals(view.getHeight(), mapped.bottom, .5f);
    }
    private static final class BudgetList extends RecyclerView {
        BudgetList(Context context) { super(context); }
        @Override protected void onMeasure(int width, int height) { setMeasuredDimension(MeasureSpec.getSize(width), MeasureSpec.getSize(height)); }
        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) { }
    }
    private static final class FakeImages implements PostPhotosRenderer.Images {
        final List<PostPhotosRenderer.SizeReady> pending = new ArrayList<>();
        final List<PostPhotoView> targets = new ArrayList<>();
        int loads;
        @Override public void load(PostPhotoView target, String url, PostPhotosRenderer.SizeReady ready) { loads++; targets.add(target); pending.add(ready); }
        @Override public void clear(ImageView target) { target.setImageDrawable(null); }
        void complete(int index, int width, int height) {
            targets.get(index).setImageDrawable(new SizedDrawable(width, height));
            pending.get(index).onSize(width, height);
        }
    }
    private static final class SizedDrawable extends Drawable {
        private final int width, height;
        SizedDrawable(int width, int height) { this.width = width; this.height = height; }
        @Override public int getIntrinsicWidth() { return width; }
        @Override public int getIntrinsicHeight() { return height; }
        @Override public void draw(Canvas canvas) { }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }
}
