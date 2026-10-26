package com.vktea.feed;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.vktea.R;
import com.vktea.api.VKApi;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Actual attached RecyclerView holders; synthetic text only, no images, account or transport. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w320dp-h700dp-mdpi", shadows = FeedPostToggleAnchorTest.NoApi.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class FeedPostToggleAnchorTest {
    private ActivityController<Host> controller;

    @After public void close() { if (controller != null) controller.close(); }

    @Test public void collapseFromBottomAnchorsSamePostAtListTopAndLeavesNeighboursUnchanged() {
        controller = Robolectric.buildActivity(Host.class).setup().visible();
        Host host = controller.get();
        settle(host);
        host.manager.scrollToPositionWithOffset(1, 0);
        settle(host);
        FeedAdapter.PostViewHolder holder = holder(host);
        assertEquals(1, holder.getAbsoluteAdapterPosition());
        assertEquals("Показать полностью", holder.more.getText().toString());
        holder.more.performClick();
        settle(host);
        holder = holder(host);
        assertTrue(host.longPost.expanded);
        assertEquals("Свернуть", holder.more.getText().toString());
        assertEquals(holder.content.getNaturalContentHeight(), holder.content.getHeight());

        int distanceToBottom = holder.itemView.getBottom() - (host.list.getHeight() - host.list.getPaddingBottom());
        assertTrue("Fixture must require scrolling through the expanded post", distanceToBottom > host.list.getHeight());
        host.list.scrollBy(0, distanceToBottom);
        settle(host);
        holder = holder(host);
        assertTrue(holder.itemView.getTop() < host.list.getPaddingTop());
        assertEquals(View.VISIBLE, holder.more.getVisibility());
        assertTrue("Collapse action must be visible at the bottom of the expanded post", holder.more.getGlobalVisibleRect(new Rect()));

        holder.more.performClick();
        settle(host);
        holder = holder(host);
        assertFalse(host.longPost.expanded);
        assertEquals(host.list.getPaddingTop(), holder.itemView.getTop());
        assertEquals(holder.content.getHeightBudget(), holder.content.getHeight());
        assertEquals("Показать полностью", holder.more.getText().toString());
        assertEquals(View.VISIBLE, holder.more.getVisibility());
        assertFalse(host.first.expanded);
        assertFalse(host.last.expanded);
        assertEquals(3, host.adapter.getItemCount());
        assertNull(shadowOf(host).getNextStartedActivity());
    }

    @Test public void translatedStickyButtonReceivesRealTouchAndCollapsesItsOwnPost() {
        Host host = openExpanded();
        host.list.scrollBy(0, host.list.getHeight() + 200);
        settle(host);
        FeedAdapter.PostViewHolder holder = holder(host);
        assertTrue(holder.itemView.getTop() < -host.list.getHeight());
        assertEquals(View.VISIBLE, holder.collapseTop.getVisibility());
        assertTrue(holder.collapseTop.getTranslationY() > 0);
        assertEquals(host.list.getPaddingTop(), holder.itemView.getY() + holder.collapseTop.getY(), .5f);

        touchCenter(host.list, holder.collapseTop);
        settle(host);
        holder = holder(host);
        assertFalse(host.longPost.expanded);
        assertEquals(host.list.getPaddingTop(), holder.itemView.getTop());
        assertEquals(View.GONE, holder.collapseTop.getVisibility());
        assertEquals(0, holder.collapseTop.getTranslationY(), .01f);
        assertEquals("Показать полностью", holder.more.getText().toString());
        assertFalse(host.first.expanded);
        assertFalse(host.last.expanded);
        assertNull(shadowOf(host).getNextStartedActivity());
    }

    @Test public void stickyButtonStopsAtItsOwnContentBoundaryBeforeTheNextPost() {
        Host host = openExpanded();
        // Trailing short rows let this boundary reach the top without RecyclerView filling an end-of-list gap.
        List<Post> trailing = new ArrayList<>();
        for (int index = 0; index < 6; index++) trailing.add(Host.post(index + 4, "Synthetic trailing short post " + index));
        host.adapter.addAll(trailing);
        settle(host);
        FeedAdapter.PostViewHolder holder = holder(host);
        int desiredContentBottom = host.list.getPaddingTop() + holder.collapseTop.getHeight() / 2;
        int distance = Math.round(holder.itemView.getY() + holder.content.getBottom() - desiredContentBottom);
        host.list.scrollBy(0, distance);
        settle(host);
        holder = holder(host);
        View next = host.manager.findViewByPosition(2);
        assertNotNull(next);
        assertTrue(holder.collapseTop.getTranslationY() > 0);
        assertEquals(holder.content.getBottom(), holder.collapseTop.getY() + holder.collapseTop.getHeight(), .5f);
        float stickyTop = holder.itemView.getY() + holder.collapseTop.getY();
        float stickyBottom = stickyTop + holder.collapseTop.getHeight();
        assertTrue("The button must leave the sticky top as its own content ends", stickyTop < host.list.getPaddingTop());
        assertTrue("Translated button must never cover the next post", stickyBottom <= next.getY());
        assertTrue(host.longPost.expanded);
        assertFalse(host.last.expanded);
        assertNull(shadowOf(host).getNextStartedActivity());
    }

    @Test public void replacingAdapterClearsStickyTranslationBeforeTheHolderIsReused() {
        Host host = openExpanded();
        host.list.scrollBy(0, host.list.getHeight() + 200);
        settle(host);
        FeedAdapter.PostViewHolder old = holder(host);
        assertTrue(old.collapseTop.getTranslationY() > 0);
        Post replacementPost = Host.post(99, "Synthetic replacement short post");
        FeedAdapter replacement = new FeedAdapter(null);
        replacement.addAll(Collections.singletonList(replacementPost));
        host.list.setAdapter(replacement);
        settle(host);
        assertEquals(0, old.collapseTop.getTranslationY(), .01f);
        assertEquals(View.GONE, old.collapseTop.getVisibility());
        FeedAdapter.PostViewHolder fresh = (FeedAdapter.PostViewHolder) host.list.findViewHolderForAdapterPosition(0);
        assertNotNull(fresh);
        assertSame(replacementPost, fresh.boundPost);
        assertEquals(0, fresh.collapseTop.getTranslationY(), .01f);
        assertEquals(View.GONE, fresh.collapseTop.getVisibility());
        fresh.collapseTop.performClick();
        assertFalse(replacementPost.expanded);
        assertTrue("Recycling must preserve the original post's expansion state", host.longPost.expanded);
        assertNull(shadowOf(host).getNextStartedActivity());
    }

    private Host openExpanded() {
        controller = Robolectric.buildActivity(Host.class).setup().visible();
        Host host = controller.get();
        settle(host);
        host.manager.scrollToPositionWithOffset(1, 0);
        settle(host);
        holder(host).more.performClick();
        settle(host);
        assertTrue(host.longPost.expanded);
        return host;
    }
    private static void touchCenter(RecyclerView list, View target) {
        int[] targetOrigin = new int[2], listOrigin = new int[2];
        target.getLocationOnScreen(targetOrigin);
        list.getLocationOnScreen(listOrigin);
        float x = targetOrigin[0] - listOrigin[0] + target.getWidth() / 2f;
        float y = targetOrigin[1] - listOrigin[1] + target.getHeight() / 2f;
        long down = SystemClock.uptimeMillis();
        MotionEvent press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent release = MotionEvent.obtain(down, down + 100, MotionEvent.ACTION_UP, x, y, 0);
        try {
            assertTrue(list.dispatchTouchEvent(press));
            assertTrue(list.dispatchTouchEvent(release));
        } finally { press.recycle(); release.recycle(); }
    }

    private static FeedAdapter.PostViewHolder holder(Host host) {
        RecyclerView.ViewHolder holder = host.list.findViewHolderForAdapterPosition(1);
        assertNotNull("The same post must have an attached holder", holder);
        return (FeedAdapter.PostViewHolder) holder;
    }
    private static void settle(Host host) {
        View decor = host.getWindow().getDecorView();
        for (int pass = 0; pass < 2; pass++) {
            decor.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, 320, 700);
            shadowOf(Looper.getMainLooper()).idle();
        }
    }

    public static class Host extends AppCompatActivity {
        final Post first = post(1, "Synthetic first post");
        final Post longPost = post(2, longText());
        final Post last = post(3, "Synthetic last post");
        RecyclerView list;
        LinearLayoutManager manager;
        FeedAdapter adapter;
        @Override protected void onCreate(Bundle state) {
            setTheme(R.style.Theme_VKTea);
            super.onCreate(state);
            list = new RecyclerView(this);
            // This touch-scroll fixture must not let Robolectric's desktop keyboard focus re-anchor a recycled row.
            list.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            list.setPadding(0, 16, 0, 24);
            list.setItemAnimator(null);
            manager = new LinearLayoutManager(this);
            list.setLayoutManager(manager);
            adapter = new FeedAdapter(null);
            adapter.addAll(Arrays.asList(first, longPost, last));
            list.setAdapter(adapter);
            setContentView(list);
        }
        private static Post post(long id, String text) {
            return new Post(42, "Synthetic source", null, text, 0, Collections.emptyList(),
                    0, 0, 0, 0, false, Post.TYPE_POST, 42, id);
        }
        private static String longText() {
            StringBuilder text = new StringBuilder();
            for (int line = 0; line < 120; line++) text.append("Synthetic line ").append(line).append(". Local text.\n");
            return text.toString();
        }
    }

    @Implements(VKApi.class)
    public static class NoApi {
        @Implementation public static void get(Context context, String method, String params, VKApi.Callback callback) {
            throw new AssertionError("Expanding or collapsing a post must not request VK");
        }
    }
}
