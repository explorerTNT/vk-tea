package com.vktea.msg;

import static org.junit.Assert.*;

import android.os.Bundle;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.vktea.R;
import com.vktea.api.VKApi;

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

import java.util.ArrayList;
import java.util.List;

/** The actual fragment binds synthetic responses; every network operation is replaced. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class DialogsFragmentTest {
    private ActivityController<Host> controller;
    private OfflineDialogs fragment;
    private DialogsAdapter adapter;

    @Before public void open() {
        controller = Robolectric.buildActivity(Host.class).setup();
        fragment = controller.get().fragment;
        RecyclerView list = fragment.requireView().findViewById(R.id.dialogs_list);
        adapter = (DialogsAdapter) list.getAdapter();
    }

    @After public void close() { controller.close(); }

    @Test public void hydratesPeopleAndCommunitiesSeparatelyEvenWithKnownTitles() throws Exception {
        fragment.dialogs.get(0).success(history());
        assertEquals("42", fragment.userIds.get(0));
        assertEquals("8", fragment.groupIds.get(0));
        assertEquals(3, adapter.items().size());
        assertEquals(2_000_000_007L, adapter.items().get(1).peerId);
        fragment.users.get(0).success(json("{\"response\":[{\"id\":42,\"first_name\":\"Synthetic\","
                + "\"last_name\":\"Peer\",\"photo_200\":\"\",\"photo_100\":\"file:///synthetic-user.png\"}]}"));
        fragment.groups.get(0).success(json("{\"response\":[{\"id\":8,\"name\":\"Synthetic community\","
                + "\"photo_200\":\"file:///synthetic-group.png\"}]}"));
        assertEquals("Synthetic Peer", adapter.items().get(0).title);
        assertEquals("file:///synthetic-user.png", adapter.items().get(0).photo);
        assertEquals("file:///synthetic-room.png", adapter.items().get(1).photo);
        assertEquals("file:///synthetic-group.png", adapter.items().get(2).photo);
    }

    @Test public void lateOldMetadataCannotOverwriteAvatarsAfterRefresh() throws Exception {
        fragment.dialogs.get(0).success(history());
        VKApi.Callback oldUsers = fragment.users.get(0);
        VKApi.Callback oldGroups = fragment.groups.get(0);
        fragment.load();
        fragment.dialogs.get(1).success(history());
        fragment.users.get(1).success(json("{\"response\":[{\"id\":42,\"photo_200\":\"file:///new-user.png\"}]}"));
        fragment.groups.get(1).success(json("{\"response\":{\"groups\":[{\"id\":8,"
                + "\"name\":\"New community\",\"photo_200\":\"file:///new-group.png\"}]}}"));
        oldUsers.success(json("{\"response\":[{\"id\":42,\"photo_200\":\"file:///old-user.png\"}]}"));
        oldGroups.success(json("{\"response\":[{\"id\":8,\"photo_200\":\"file:///old-group.png\"}]}"));
        assertEquals("file:///new-user.png", adapter.items().get(0).photo);
        assertEquals("file:///new-group.png", adapter.items().get(2).photo);
    }

    @Test public void lateHistoryDoesNotReplaceCurrentRowsOrFinishCurrentRefresh() throws Exception {
        VKApi.Callback oldHistory = fragment.dialogs.get(0);
        fragment.load();
        oldHistory.success(history());
        SwipeRefreshLayout refresh = fragment.requireView().findViewById(R.id.dialogs_refresh);
        assertTrue(refresh.isRefreshing());
        assertEquals(0, adapter.getItemCount());
        fragment.dialogs.get(1).success(history());
        assertFalse(refresh.isRefreshing());
        assertEquals(3, adapter.getItemCount());
    }

    @Test public void destroyingViewIgnoresHistoryAndAvatarCallbacks() throws Exception {
        fragment.dialogs.get(0).success(history());
        VKApi.Callback users = fragment.users.get(0);
        VKApi.Callback groups = fragment.groups.get(0);
        VKApi.Callback history = fragment.dialogs.get(0);
        controller.get().getSupportFragmentManager().beginTransaction().remove(fragment).commitNow();
        users.success(json("{\"response\":[{\"id\":42,\"photo_100\":\"file:///late.png\"}]}"));
        groups.error(new Exception("Synthetic transport detail"));
        history.success(json("{\"response\":{\"items\":[]}}"));
        assertEquals(3, adapter.getItemCount());
        assertEquals("", adapter.items().get(0).photo);
        assertEquals(1, fragment.users.size());
    }

    private static JSONObject history() throws Exception {
        return json("{\"response\":{\"items\":["
                + "{\"message\":{\"user_id\":42,\"title\":\"Already known name\"}},"
                + "{\"message\":{\"chat_id\":7,\"user_id\":7001,\"title\":\"Synthetic room\","
                + "\"photo_100\":\"file:///synthetic-room.png\"}},"
                + "{\"message\":{\"user_id\":-8,\"title\":\"Known community\"}}]}}");
    }
    private static JSONObject json(String value) throws Exception { return new JSONObject(value); }

    public static class Host extends AppCompatActivity {
        OfflineDialogs fragment;
        @Override protected void onCreate(Bundle state) {
            super.onCreate(state);
            FrameLayout content = new FrameLayout(this);
            content.setId(R.id.content);
            setContentView(content);
            fragment = new OfflineDialogs();
            getSupportFragmentManager().beginTransaction().replace(R.id.content, fragment).commitNow();
        }
    }

    public static class OfflineDialogs extends DialogsFragment {
        @Override protected void subscribeLiveUpdates() { }
        @Override protected void unsubscribeLiveUpdates() { }
        final List<VKApi.Callback> dialogs = new ArrayList<>();
        final List<VKApi.Callback> users = new ArrayList<>();
        final List<VKApi.Callback> groups = new ArrayList<>();
        final List<String> userIds = new ArrayList<>();
        final List<String> groupIds = new ArrayList<>();
        @Override protected void requestDialogs(VKApi.Callback callback) { dialogs.add(callback); }
        @Override protected void requestUsers(String ids, VKApi.Callback callback) { userIds.add(ids); users.add(callback); }
        @Override protected void requestGroups(String ids, VKApi.Callback callback) { groupIds.add(ids); groups.add(callback); }
    }
}
