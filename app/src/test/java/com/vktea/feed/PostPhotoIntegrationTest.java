package com.vktea.feed;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
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
                + "\"from_id\":42,\"attachments\":[{\"type\":\"photo\",\"photo\":{\"id\":17,\"owner_id\":-91,"
                + "\"access_key\":\"synthetic-wall-key\",\"likes\":{\"count\":7},\"sizes\":["
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
        java.util.ArrayList<ViewerPhoto> metadata = opened.getParcelableArrayListExtra(ViewerPhoto.EXTRA_PHOTOS);
        assertEquals(17, metadata.get(0).id);
        assertEquals(-91, metadata.get(0).ownerId);
        assertEquals("synthetic-wall-key", metadata.get(0).accessKey);
        assertEquals(7, metadata.get(0).likes);
        assertUnrestrictedPostBody(holder.itemView);
        adapter.onViewRecycled(holder);
        assertEquals(0, holder.photoContainer.getChildCount());
    }

    @Test public void feedAndRepostUseSameRendererAndClearBothWhenHolderIsRebound() {
        ActivityController<WallHost> host = Robolectric.buildActivity(WallHost.class).setup();
        controller = host;
        FeedAdapter adapter = new FeedAdapter(null);
        Post populated = post();
        populated.repostPhotos.add("file:///synthetic-repost.png");
        populated.repostPhotoDetails.add(new ViewerPhoto("file:///synthetic-repost.png", 601, -22,
                "synthetic-repost-key", 0, 0, 0, false, false, false, false));
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
        java.util.ArrayList<ViewerPhoto> metadata = opened.getParcelableArrayListExtra(ViewerPhoto.EXTRA_PHOTOS);
        assertEquals(502, metadata.get(1).id);
        holder.repostPhotos.getChildAt(0).performClick();
        Intent repost = shadowOf(host.get()).getNextStartedActivity();
        java.util.ArrayList<ViewerPhoto> repostMetadata = repost.getParcelableArrayListExtra(ViewerPhoto.EXTRA_PHOTOS);
        assertEquals(601, repostMetadata.get(0).id);
        assertEquals(-22, repostMetadata.get(0).ownerId);
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
        assertUnrestrictedPostBody(holder.itemView);
        holder.photoContainer.getChildAt(1).performClick();
        Intent opened = shadowOf(detail.get()).getNextStartedActivity();
        java.util.ArrayList<ViewerPhoto> metadata = opened.getParcelableArrayListExtra(ViewerPhoto.EXTRA_PHOTOS);
        assertEquals(502, metadata.get(1).id);
        holder.reposts.performClick();
        Intent sharing = shadowOf(detail.get()).getNextStartedActivity();
        assertEquals(com.vktea.share.ShareActivity.class.getName(), sharing.getComponent().getClassName());
        com.vktea.share.ShareAttachment shared = sharing.getParcelableExtra(com.vktea.share.ShareActivity.EXTRA_ATTACHMENT);
        assertEquals("wall42_9", shared.object());
        adapter.onViewRecycled(holder);
        assertEquals(0, holder.photoContainer.getChildCount());
        assertEquals(0, holder.repostPhotos.getChildCount());
    }

    @Test public void feedRepostOpensChooserForWallAndPhotoAndDisablesUnknownIdentity() {
        ActivityController<WallHost> host = Robolectric.buildActivity(WallHost.class).setup();
        controller = host;
        FeedAdapter adapter = new FeedAdapter(null);
        Post wall = post();
        wall.accessKey = "synthetic-wall-access";
        Post photo = new Post(-91, "Synthetic", null, "", 0, Collections.emptyList(), 0, 0, 0, 0,
                false, Post.TYPE_PHOTO, -91, 55);
        photo.photoDetails.add(new ViewerPhoto("file:///qa.png", 55, -91, "synthetic-photo-access", 0, 0, 0,
                false, false, false, false));
        Post unknown = new Post(42, "Synthetic", null, "", 0, Collections.emptyList(), 0, 0, 0, 0,
                false, Post.TYPE_POST, 42, 0);
        adapter.addAll(Arrays.asList(wall, photo, unknown));
        RecyclerView parent = new RecyclerView(host.get());
        parent.setLayoutManager(new LinearLayoutManager(host.get()));
        FeedAdapter.PostViewHolder holder = adapter.onCreateViewHolder(parent, 0);
        adapter.onBindViewHolder(holder, 0);
        holder.reposts.performClick();
        Intent opened = shadowOf(host.get()).getNextStartedActivity();
        assertEquals(com.vktea.share.ShareActivity.class.getName(), opened.getComponent().getClassName());
        com.vktea.share.ShareAttachment shared = opened.getParcelableExtra(com.vktea.share.ShareActivity.EXTRA_ATTACHMENT);
        assertEquals("wall42_9", shared.object());
        assertEquals("synthetic-wall-access", shared.accessKey);
        adapter.onBindViewHolder(holder, 1);
        holder.reposts.performClick();
        shared = shadowOf(host.get()).getNextStartedActivity().getParcelableExtra(com.vktea.share.ShareActivity.EXTRA_ATTACHMENT);
        assertEquals("photo-91_55", shared.object());
        assertEquals("synthetic-photo-access", shared.accessKey);
        adapter.onBindViewHolder(holder, 2);
        assertFalse(holder.reposts.isEnabled());
        assertNull(shadowOf(host.get()).getNextStartedActivity());
    }

    private static void assertUnrestrictedPostBody(View item) {
        stopPhotoLoads(item);
        ViewGroup photos = item.findViewById(R.id.post_photos);
        ((PostPhotoView) photos.getChildAt(0)).setSourceSize(100, 800);
        item.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        item.layout(0, 0, 320, item.getMeasuredHeight());
        FeedPostContent content = item.findViewById(R.id.post_content);
        assertTrue(content.getHeight() >= 2560);
        assertEquals(content.getNaturalContentHeight(), content.getHeight());
        assertEquals(View.GONE, item.findViewById(R.id.post_more).getVisibility());
        assertEquals(View.GONE, item.findViewById(R.id.post_collapse_top).getVisibility());
    }

    private static void stopPhotoLoads(View view) {
        if (view instanceof PostPhotoView) ((PostPhotoView) view).loadAtMeasuredWidth(null);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) stopPhotoLoads(group.getChildAt(i));
        }
    }

    private static Post post() {
        Post post = new Post(42, "Synthetic", null, "Synthetic post", 100,
                Arrays.asList("file:///synthetic-portrait.png", "file:///synthetic-wide.png"),
                0, 0, 0, 0, false, Post.TYPE_POST, 42, 9);
        post.photoDetails.add(new ViewerPhoto(post.photoUrls.get(0), 501, -11, "portrait-key", 0, 0, 0,
                false, false, false, false));
        post.photoDetails.add(new ViewerPhoto(post.photoUrls.get(1), 502, -12, "wide-key", 0, 0, 0,
                false, false, false, false));
        return post;
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
