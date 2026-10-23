package com.vktea.feed;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.os.Bundle;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.vktea.R;
import com.vktea.api.VKApi;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.Arrays;
import java.util.Collections;

/** Exercise the actual wall/feed/detail bind paths with synthetic responses and local file URIs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class PostPhotoIntegrationTest {
    private ActivityController<? extends AppCompatActivity> controller;

    @After public void close() {
        if (controller != null) controller.close();
        PostActivity.currentPost = null;
    }

    @Test public void profileWallSelectsLargePhotoAndUsesAspectRendererInsteadOfSquareRows() throws Exception {
        ActivityController<WallHost> host = Robolectric.buildActivity(WallHost.class).setup();
        controller = host;
        OfflineProfile profile = host.get().profile;
        profile.profileCallback.success(new JSONObject("{\"response\":[{\"id\":42,"
                + "\"first_name\":\"Synthetic\",\"last_name\":\"Profile\"}]}"));
        profile.wallCallback.success(new JSONObject("{\"response\":{\"items\":[{\"id\":9,"
                + "\"from_id\":42,\"attachments\":[{\"type\":\"photo\",\"photo\":{\"sizes\":["
                + "{\"type\":\"w\",\"width\":1600,\"height\":900,\"url\":\"file:///synthetic-wide.png\"},"
                + "{\"type\":\"s\",\"width\":75,\"height\":42,\"url\":\"file:///small-sentinel.png\"}]}}]}]}}"));
        RecyclerView list = profile.requireView().findViewById(R.id.profile_wall);
        ProfileFragment.ProfileAdapter adapter = (ProfileFragment.ProfileAdapter) list.getAdapter();
        assertEquals(2, adapter.getItemCount());
        ProfileFragment.PostHolder holder = (ProfileFragment.PostHolder)
                adapter.onCreateViewHolder(list, adapter.getItemViewType(1));
        adapter.onBindViewHolder(holder, 1);
        assertEquals(1, holder.photoContainer.getChildCount());
        assertTrue(holder.photoContainer.getChildAt(0) instanceof PostPhotoView);
        holder.photoContainer.getChildAt(0).performClick();
        Intent opened = shadowOf(host.get()).getNextStartedActivity();
        assertEquals(Collections.singletonList("file:///synthetic-wide.png"), opened.getStringArrayListExtra("photos"));
        adapter.onViewRecycled(holder);
        assertEquals(0, holder.photoContainer.getChildCount());
    }

    @Test public void feedAndRepostUseSameRendererAndClearBothWhenHolderIsRebound() {
        ActivityController<WallHost> host = Robolectric.buildActivity(WallHost.class).setup();
        controller = host;
        FeedAdapter adapter = new FeedAdapter(null);
        Post populated = post();
        populated.repostPhotos.add("file:///synthetic-repost.png");
        adapter.addAll(Arrays.asList(populated, new Post(42, "Synthetic", null, "", 0,
                Collections.emptyList(), 0, 0, 0, 0, false, Post.TYPE_POST, 42, 10)));
        RecyclerView parent = new RecyclerView(host.get());
        parent.setLayoutManager(new LinearLayoutManager(host.get()));
        FeedAdapter.PostViewHolder holder = adapter.onCreateViewHolder(parent, 0);
        adapter.onBindViewHolder(holder, 0);
        assertEquals(2, holder.photoContainer.getChildCount());
        assertEquals(1, holder.repostPhotos.getChildCount());
        assertTrue(holder.photoContainer.getChildAt(1) instanceof PostPhotoView);
        holder.photoContainer.getChildAt(1).performClick();
        Intent opened = shadowOf(host.get()).getNextStartedActivity();
        assertEquals(populated.photoUrls, opened.getStringArrayListExtra("photos"));
        assertEquals(1, opened.getIntExtra("index", -1));
        adapter.onBindViewHolder(holder, 1);
        assertEquals(0, holder.photoContainer.getChildCount());
        assertEquals(0, holder.repostPhotos.getChildCount());
        adapter.onBindViewHolder(holder, 0);
        adapter.onViewRecycled(holder);
        assertEquals(0, holder.photoContainer.getChildCount());
        assertEquals(0, holder.repostPhotos.getChildCount());
    }

    @Test @SuppressWarnings({"rawtypes", "unchecked"})
    public void openedPostUsesSameMainAndRepostPhotoViews() {
        PostActivity.currentPost = post();
        PostActivity.currentPost.repostPhotos.add("file:///synthetic-repost.png");
        ActivityController<OfflinePost> detail = Robolectric.buildActivity(OfflinePost.class).setup();
        controller = detail;
        RecyclerView list = detail.get().findViewById(R.id.post_list);
        RecyclerView.Adapter adapter = list.getAdapter();
        PostActivity.PostCardHolder holder = (PostActivity.PostCardHolder)
                adapter.onCreateViewHolder(list, adapter.getItemViewType(0));
        adapter.onBindViewHolder(holder, 0);
        assertEquals(1, detail.get().commentRequests);
        assertEquals(2, holder.photoContainer.getChildCount());
        assertEquals(1, holder.repostPhotos.getChildCount());
        assertTrue(holder.photoContainer.getChildAt(0) instanceof PostPhotoView);
        adapter.onViewRecycled(holder);
        assertEquals(0, holder.photoContainer.getChildCount());
        assertEquals(0, holder.repostPhotos.getChildCount());
    }

    private static Post post() {
        return new Post(42, "Synthetic", null, "Synthetic post", 100,
                Arrays.asList("file:///synthetic-portrait.png", "file:///synthetic-wide.png"),
                0, 0, 0, 0, false, Post.TYPE_POST, 42, 9);
    }

    public static class WallHost extends AppCompatActivity {
        OfflineProfile profile;
        @Override protected void onCreate(Bundle state) {
            super.onCreate(state);
            FrameLayout content = new FrameLayout(this);
            content.setId(R.id.content);
            setContentView(content);
            profile = new OfflineProfile();
            Bundle args = new Bundle();
            args.putLong("user_id", 42);
            profile.setArguments(args);
            getSupportFragmentManager().beginTransaction().replace(R.id.content, profile).commitNow();
        }
    }

    public static class OfflineProfile extends ProfileFragment {
        VKApi.Callback profileCallback;
        VKApi.Callback wallCallback;
        @Override protected void requestProfile(VKApi.Callback callback) { profileCallback = callback; }
        @Override protected void requestWall(VKApi.Callback callback) { wallCallback = callback; }
    }

    public static class OfflinePost extends PostActivity {
        int commentRequests;
        @Override protected void requestComments(VKApi.Callback callback) { commentRequests++; }
    }
}
