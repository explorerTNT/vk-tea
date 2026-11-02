package com.vktea.groups;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.ComponentName;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModel;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.tabs.TabLayout;
import com.vktea.MainActivity;
import com.vktea.R;
import com.vktea.api.VKApi;
import com.vktea.audio.AudioPlaybackService;
import com.vktea.auth.Session;
import com.vktea.feed.ProfileFragment;
import com.vktea.menu.MenuEntry;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Actual host, list and community profile; all account/API data are synthetic. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = {GroupsNavigationTest.Api.class, GroupsNavigationTest.Factory.class,
        GroupsNavigationTest.NoLiveTransport.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class GroupsNavigationTest {
    private ActivityController<MainActivity> host;

    @Before public void setup() {
        Context app = RuntimeEnvironment.getApplication();
        Session.save(app, "synthetic-groups-navigation", "1");
        Api.calls.clear(); Api.modern = false; Factory.created.clear();
        shadowOf(RuntimeEnvironment.getApplication()).declareComponentUnbindable(new ComponentName(app, AudioPlaybackService.class));
    }
    @After public void close() {
        if (host != null) host.close();
        RuntimeEnvironment.getApplication().getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void menuOpensNativeGroupsAndRecreationRetainsTheSnapshot() {
        launch();
        assertTrue(current() instanceof GroupsFragment);
        assertEquals(2, list().getAdapter().getItemCount());
        assertEquals(activity().getString(R.string.section_groups), title());
        host.recreate(); settle();
        assertTrue(current() instanceof GroupsFragment);
        assertEquals(1, Factory.created.size());
        assertEquals(1, Factory.created.get(0).loads);
        assertEquals(2, list().getAdapter().getItemCount());
    }

    @Test public void toolbarSearchAndMenuStayInsideGroupsAndDoNotFetchOnFilters() {
        launch();
        activity().findViewById(R.id.toolbar_search).performClick();
        EditText query = current().requireView().findViewById(R.id.groups_search);
        assertTrue(query.hasFocus()); query.setText("yolka");
        assertEquals(1, list().getAdapter().getItemCount());
        current().requireView().findViewById(R.id.groups_clear_search).performClick();
        activity().findViewById(R.id.toolbar_title_btn).performClick();
        PopupMenu menu = ReflectionHelpers.getField(current(), "menu");
        assertNotNull(menu); assertTrue(menu.getMenu().performIdentifierAction(2, 0));
        assertEquals(1, ((TabLayout) current().requireView().findViewById(R.id.groups_tabs)).getSelectedTabPosition());
        assertEquals(1, list().getAdapter().getItemCount());
        assertEquals(1, Factory.created.get(0).loads);
        assertTrue(menu.getMenu().performIdentifierAction(3, 0));
        assertEquals(2, Factory.created.get(0).loads);
        assertNull(shadowOf(activity()).getNextStartedActivity());
    }

    @Test public void groupRowLoadsGroupMetadataAndNegativeWallThenRestoresCommunityAndListTitles() {
        launch(); openFirst();
        assertTrue(current() instanceof ProfileFragment);
        assertEquals(-42, current().requireArguments().getLong("user_id"));
        assertEquals(activity().getString(R.string.groups_profile_title), title());
        assertTrue(Api.calls.stream().anyMatch(value -> value.startsWith("groups.getById:group_id=42&")));
        assertTrue(Api.calls.stream().anyMatch(value -> value.startsWith("wall.get:owner_id=-42&")));
        assertFalse(Api.calls.stream().anyMatch(value -> value.startsWith("users.get:user_ids=-42")));
        assertGroupHeader();
        host.recreate(); settle();
        assertEquals(activity().getString(R.string.groups_profile_title), title());
        assertGroupHeader();
        activity().getSupportFragmentManager().popBackStackImmediate(); settle();
        assertTrue(current() instanceof GroupsFragment);
        assertEquals(activity().getString(R.string.section_groups), title());
        assertEquals(1, Factory.created.size());
        assertEquals(1, Factory.created.get(0).loads);
    }

    @Test public void modernGroupsEnvelopeAlsoBindsWithoutFriendActions() {
        Api.modern = true; launch(); openFirst(); assertGroupHeader();
        assertNull(shadowOf(activity()).getNextStartedActivity());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void assertGroupHeader() {
        RecyclerView wall = current().requireView().findViewById(R.id.profile_wall);
        assertEquals(2, wall.getAdapter().getItemCount());
        RecyclerView.Adapter adapter = wall.getAdapter();
        RecyclerView.ViewHolder holder = adapter.onCreateViewHolder(wall, adapter.getItemViewType(0));
        adapter.onBindViewHolder(holder, 0);
        assertEquals("Данные сообщества", ((TextView) holder.itemView.findViewById(R.id.profile_name)).getText().toString());
        assertEquals("Описание сообщества", ((TextView) holder.itemView.findViewById(R.id.profile_status)).getText().toString());
        assertEquals(View.GONE, holder.itemView.findViewById(R.id.profile_buttons_wrap).getVisibility());
        assertFalse(holder.itemView.findViewById(R.id.profile_btn_friend).performClick());
        assertFalse(holder.itemView.findViewById(R.id.profile_btn_message).performClick());
    }
    private void openFirst() {
        GroupsAdapter adapter = (GroupsAdapter) list().getAdapter();
        GroupsAdapter.Holder holder = adapter.onCreateViewHolder(list(), 0);
        adapter.onBindViewHolder(holder, 0); holder.itemView.performClick(); settle();
    }
    private void launch() {
        host = Robolectric.buildActivity(MainActivity.class).setup().visible(); settle();
        activity().onMenuClick(new MenuEntry(R.id.menu_groups, R.drawable.ic_menu_communities, R.string.section_groups)); settle();
    }
    private MainActivity activity() { return host.get(); }
    private Fragment current() { return activity().getSupportFragmentManager().findFragmentById(R.id.content); }
    private RecyclerView list() { return current().requireView().findViewById(R.id.groups_list); }
    private String title() { return ((TextView) activity().findViewById(R.id.toolbar_title)).getText().toString(); }
    private void settle() {
        activity().getSupportFragmentManager().executePendingTransactions();
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Implements(value = GroupsViewModel.Factory.class, isInAndroidSdk = false)
    public static class Factory {
        static final List<FakeRepository> created = new ArrayList<>();
        @Implementation protected <T extends ViewModel> T create(Class<T> type) {
            FakeRepository repo = new FakeRepository(); created.add(repo); return type.cast(new GroupsViewModel(repo));
        }
    }
    @Implements(value = VkGroupsRepository.class, isInAndroidSdk = false)
    public static class NoLiveTransport {
        @Implementation protected GroupsRepository.Request load(GroupsRepository.Callback<List<Group>> callback) {
            throw new AssertionError("Live groups transport forbidden in navigation tests");
        }
    }
    @Implements(value = VKApi.class, isInAndroidSdk = false)
    public static class Api {
        static final List<String> calls = new ArrayList<>(); static boolean modern;
        @Implementation protected static void get(Context context, String method, String params, VKApi.Callback callback) {
            calls.add(method + ":" + params);
            try {
                if (method.equals("groups.getById")) {
                    JSONObject group = new JSONObject("{id:42,name:'Данные сообщества',activity:'Тестовая тема',description:'Описание сообщества',members_count:17}");
                    org.json.JSONArray rows = new org.json.JSONArray().put(group);
                    callback.success(new JSONObject().put("response", modern ? new JSONObject().put("groups", rows) : rows));
                } else if (method.equals("wall.get")) callback.success(new JSONObject("{response:{items:[{id:7,owner_id:-42,from_id:-42,text:'Синтетическая запись'}],groups:[{id:42,name:'Данные сообщества'}]}}"));
                else if (method.equals("users.get") || method.equals("newsfeed.get") || method.equals("stories.get") || method.equals("newsfeed.getLists")
                        || method.equals("messages.getLongPollServer") || method.equals("account.getCounters"))
                    callback.error(new Exception("Synthetic offline host"));
                else throw new AssertionError("Unexpected account operation: " + method);
            } catch (org.json.JSONException failure) { throw new AssertionError(failure); }
        }
    }
    static final class FakeRepository implements GroupsRepository {
        int loads;
        @Override public Request load(Callback<List<Group>> callback) {
            loads++; callback.success(Arrays.asList(
                    new Group(42, "Ёлка", "", "yolka", "", "group", 0, true, 1, 17, false),
                    new Group(43, "Событие", "", "event", "", "event", 0, false, 0, -1, false)));
            return () -> {};
        }
        @Override public void close() { }
    }
}
