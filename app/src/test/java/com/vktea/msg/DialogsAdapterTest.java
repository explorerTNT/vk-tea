package com.vktea.msg;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.RecyclerView;

import com.vktea.R;

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

/** All URL binds are inert; no messages request or avatar transport is constructed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class DialogsAdapterTest {
    private final MessageAvatarsTest.FakeImages images = new MessageAvatarsTest.FakeImages();
    private final DialogsAdapter adapter = new DialogsAdapter(images);

    @Test public void photoRefreshDoesNotDependOnEmptyTitleAndSupportsNegativeCommunityId() {
        Dialog person = dialog(42, "Existing title", "old-photo");
        Dialog community = dialog(-99, "Existing community", "old-community");
        adapter.add(person);
        adapter.add(community);
        List<Integer> changed = new ArrayList<>();
        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override public void onItemRangeChanged(int start, int count) { changed.add(start); }
        });
        adapter.applyUser(42, "", "new-photo");
        adapter.applyUser(-99, "Updated community", "new-community");
        assertEquals("Existing title", person.title);
        assertEquals("new-photo", person.photo);
        assertEquals("Updated community", community.title);
        assertEquals("new-community", community.photo);
        assertEquals(Arrays.asList(0, 1), changed);
        adapter.applyUser(42, null, "new-photo");
        adapter.applyUser(123456, "Unrelated", "unrelated");
        assertEquals(2, changed.size());
    }

    @Test public void missingPhotoRefreshClearsOldPhotoWithoutErasingName() {
        Dialog dialog = dialog(42, "Known name", "old-photo");
        adapter.add(dialog);
        adapter.applyUser(42, " ", null);
        assertEquals("Known name", dialog.title);
        assertEquals("", dialog.photo);
    }

    @Test public void recycledHolderRebindsNoPhotoToVisiblePlaceholderAndCancelsPreviousRequest() {
        adapter.add(dialog(42, "First", "synthetic-first"));
        adapter.add(dialog(43, "Second", ""));
        DialogsAdapter.DialogViewHolder holder = holder();
        adapter.onBindViewHolder(holder, 0);
        assertTrue(holder.avatar.getDrawable() instanceof ColorDrawable);
        adapter.onBindViewHolder(holder, 1);
        assertEquals(Arrays.asList("clear", "load:synthetic-first", "clear"), images.operations);
        assertEquals("Second", holder.name.getText().toString());
        assertNotNull(holder.avatar.getDrawable());
        assertFalse(holder.avatar.getDrawable() instanceof ColorDrawable);
        adapter.onViewRecycled(holder);
        assertEquals("clear", images.operations.get(images.operations.size() - 1));
        assertFalse(holder.itemView.performClick());
    }

    @Test public void communityAndChatRowsUseGroupPlaceholder() {
        adapter.add(dialog(-50, "Community", "synthetic-group"));
        adapter.add(dialog(2000000001L, "Chat", "synthetic-chat"));
        DialogsAdapter.DialogViewHolder holder = holder();
        adapter.onBindViewHolder(holder, 0);
        assertEquals(R.drawable.message_avatar_group, images.lastPlaceholder);
        adapter.onBindViewHolder(holder, 1);
        assertEquals(R.drawable.message_avatar_group, images.lastPlaceholder);
    }

    @Test @Config(qualifiers = "xxxhdpi")
    public void avatarUsesActual56DpViewAtHighDensity() {
        adapter.add(dialog(42, "Person", "synthetic-person"));
        DialogsAdapter.DialogViewHolder holder = holder();
        adapter.onBindViewHolder(holder, 0);
        float density = holder.itemView.getResources().getDisplayMetrics().density;
        int width = Math.round(360 * density);
        holder.itemView.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        holder.itemView.layout(0, 0, width, holder.itemView.getMeasuredHeight());
        assertEquals(Math.round(56 * density), holder.avatar.getWidth());
        assertEquals(Math.round(56 * density), holder.avatar.getHeight());
        assertTrue(holder.avatar.getWidth() > 120);
    }

    @Test public void latestDialogDataIsPassedToChatAfterHolderIsRebound() {
        adapter.add(dialog(42, "First", "synthetic-first"));
        adapter.add(dialog(-99, "Community", "synthetic-community"));
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup()) {
            host.get().setTheme(R.style.Theme_VKTea);
            DialogsAdapter.DialogViewHolder holder = adapter.onCreateViewHolder(new FrameLayout(host.get()), 0);
            adapter.onBindViewHolder(holder, 0);
            adapter.onBindViewHolder(holder, 1);
            assertTrue(holder.itemView.performClick());
            Intent intent = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
            assertEquals(ChatActivity.class.getName(), intent.getComponent().getClassName());
            assertEquals(-99, intent.getLongExtra(ChatActivity.EXTRA_PEER_ID, 0));
            assertEquals("Community", intent.getStringExtra(ChatActivity.EXTRA_TITLE));
            assertEquals("synthetic-community", intent.getStringExtra(ChatActivity.EXTRA_PHOTO));
        }
    }

    private DialogsAdapter.DialogViewHolder holder() {
        Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_VKTea);
        return adapter.onCreateViewHolder(new FrameLayout(context), 0);
    }
    private static Dialog dialog(long id, String title, String photo) {
        return new Dialog(id, title, photo, "Synthetic excerpt", 1234, false);
    }
}
