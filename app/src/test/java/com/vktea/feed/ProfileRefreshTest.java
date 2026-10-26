package com.vktea.feed;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.vktea.R;
import com.vktea.api.VKApi;
import com.vktea.share.ShareActivity;
import com.vktea.share.ShareAttachment;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class ProfileRefreshTest {
    private ActivityController<Host> controller;
    private OfflineProfile profile;

    @Before public void open() {
        controller = Robolectric.buildActivity(Host.class).setup().visible();
        profile = controller.get().profile;
        layout();
    }
    @After public void close() { controller.close(); }

    @Test public void initialIndicatorWaitsForBothRequests() throws Exception {
        assertTrue(refresh().isRefreshing());
        assertEquals(1, profile.profiles.size());
        assertEquals(1, profile.walls.size());
        profile.walls.get(0).success(wall(9));
        assertTrue(refresh().isRefreshing());
        profile.profiles.get(0).success(owner());
        assertFalse(refresh().isRefreshing());
        assertEquals(2, adapter().getItemCount());
    }

    @Test public void refreshReloadsOnceAndReplacesOnlyOnSuccess() throws Exception {
        completeInitial();
        pull();
        assertEquals(2, profile.walls.size());
        assertEquals(2, profile.profiles.size());
        assertTrue(refresh().isRefreshing());
        assertEquals(2, adapter().getItemCount());
        pull();
        assertEquals(2, profile.walls.size());
        profile.profiles.get(1).success(owner());
        assertTrue(refresh().isRefreshing());
        profile.walls.get(1).success(wall());
        assertFalse(refresh().isRefreshing());
        assertEquals(1, adapter().getItemCount());
    }

    @Test public void networkAndMalformedResponsesKeepPostsAndAllowNextPull() throws Exception {
        completeInitial();
        pull();
        profile.profiles.get(1).error(new Exception());
        profile.walls.get(1).error(new Exception());
        assertFalse(refresh().isRefreshing());
        assertEquals(2, adapter().getItemCount());
        pull();
        profile.profiles.get(2).success(owner());
        profile.walls.get(2).success(new JSONObject("{}"));
        assertFalse(refresh().isRefreshing());
        assertEquals(2, adapter().getItemCount());
        pull();
        assertEquals(4, profile.walls.size());
    }

    @Test public void destroyedViewRejectsLateResponsesFromPreviousRequest() throws Exception {
        VKApi.Callback oldProfile = profile.profiles.get(0), oldWall = profile.walls.get(0);
        controller.get().getSupportFragmentManager().beginTransaction().detach(profile).commitNow();
        controller.get().getSupportFragmentManager().beginTransaction().attach(profile).commitNow();
        layout();
        assertEquals(2, profile.walls.size());
        oldProfile.success(owner()); oldWall.success(wall(9));
        assertEquals(0, adapter().getItemCount());
        assertTrue(refresh().isRefreshing());
        profile.profiles.get(1).success(owner()); profile.walls.get(1).success(wall(10));
        assertEquals(2, adapter().getItemCount());
        assertFalse(refresh().isRefreshing());
    }

    @Test public void wallRepostUsesWallOwnerAndAlwaysOpensChooser() throws Exception {
        completeInitial();
        RecyclerView list = profile.requireView().findViewById(R.id.profile_wall);
        ProfileFragment.PostHolder holder = (ProfileFragment.PostHolder) adapter().onCreateViewHolder(list, 1);
        adapter().onBindViewHolder(holder, 1);
        holder.reposts.performClick();
        android.content.Intent opened = shadowOf(controller.get()).getNextStartedActivity();
        assertEquals(ShareActivity.class.getName(), opened.getComponent().getClassName());
        ShareAttachment attachment = opened.getParcelableExtra(ShareActivity.EXTRA_ATTACHMENT);
        assertEquals("wall42_9", attachment.object());
        assertEquals("synthetic-access", attachment.accessKey);
        assertFalse(attachment.canRepost);
    }

    private void completeInitial() throws Exception {
        profile.profiles.get(0).success(owner()); profile.walls.get(0).success(wall(9)); layout();
    }
    private SwipeRefreshLayout refresh() { return profile.requireView().findViewById(R.id.profile_refresh); }
    private ProfileFragment.ProfileAdapter adapter() {
        RecyclerView list = profile.requireView().findViewById(R.id.profile_wall);
        return (ProfileFragment.ProfileAdapter) list.getAdapter();
    }
    private void layout() {
        View root = controller.get().getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 400, 800);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
    }
    private void pull() {
        // Android's gesture delivery is checked on the offline emulator; drive the same refresh callback here.
        refresh().setRefreshing(true);
        profile.refreshContent();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
    }
    private static JSONObject owner() throws Exception {
        return new JSONObject("{\"response\":[{\"id\":42,\"first_name\":\"QA\",\"last_name\":\"Profile\"}]}");
    }
    private static JSONObject wall(int... ids) throws Exception {
        org.json.JSONArray items = new org.json.JSONArray();
        for (int id : ids) items.put(new JSONObject().put("id", id).put("owner_id", 42).put("from_id", 99)
                .put("text", "Synthetic refreshed post " + id).put("access_key", "synthetic-access").put("can_repost", false));
        return new JSONObject().put("response", new JSONObject().put("items", items));
    }
    public static class Host extends AppCompatActivity {
        OfflineProfile profile;
        @Override protected void onCreate(Bundle state) {
            super.onCreate(state);
            FrameLayout frame = new FrameLayout(this); frame.setId(R.id.content); setContentView(frame);
            profile = new OfflineProfile(); Bundle args = new Bundle(); args.putLong("user_id", 42); profile.setArguments(args);
            getSupportFragmentManager().beginTransaction().replace(R.id.content, profile).commitNow();
        }
    }
    public static class OfflineProfile extends ProfileFragment {
        final List<VKApi.Callback> profiles = new ArrayList<>(), walls = new ArrayList<>();
        @Override protected void requestProfile(VKApi.Callback callback) { profiles.add(callback); }
        @Override protected void requestWall(VKApi.Callback callback) { walls.add(callback); }
    }
}
