package com.vktea.feed;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Looper;
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
import java.util.Collections;

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
