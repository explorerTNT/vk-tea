package com.vktea.groups;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.tabs.TabLayout;
import com.vktea.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.text.NumberFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Real directory views, retained state and recycled rows with inert read-only callbacks. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w360dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class GroupsFragmentTest {
    static FakeRepository repository;
    private ActivityController<Host> activity;

    @Before public void create() {
        repository = new FakeRepository();
        activity = Robolectric.buildActivity(Host.class).setup().visible();
        activity.get().getSupportFragmentManager().beginTransaction().add(R.id.content, new TestFragment()).commitNow();
        settle();
    }

    @After public void close() {
        activity.close();
        repository = null;
    }

    @Test public void tabsAndUnicodeOrScreenNameSearchFilterTheCompleteSnapshotWithoutRequests() {
        assertEquals(1, repository.calls);
        assertEquals(View.VISIBLE, view(R.id.groups_loading).getVisibility());
        repository.succeed(fixtures()); settle();
        assertEquals(3, rows());
        assertEquals(22, list().getAdapter().getItemId(0));
        assertEquals(11, list().getAdapter().getItemId(1));
        TabLayout tabs = (TabLayout) view(R.id.groups_tabs);
        assertEquals("Все (3)", tabs.getTabAt(0).getText().toString());
        assertEquals("Управление (2)", tabs.getTabAt(1).getText().toString());
        tabs.selectTab(tabs.getTabAt(1));
        assertEquals(2, rows());
        assertEquals(11, list().getAdapter().getItemId(0)); // Moderator level one is management.
        ((EditText) view(R.id.groups_search)).setText("ЕЛКА");
        assertEquals(1, rows());
        assertEquals(11, list().getAdapter().getItemId(0));
        assertTrue(view(R.id.groups_clear_search).performClick());
        assertEquals(2, rows());
        tabs.selectTab(tabs.getTabAt(0));
        ((EditText) view(R.id.groups_search)).setText("plain_channel");
        assertEquals(1, rows());
        assertEquals(22, list().getAdapter().getItemId(0));
        assertEquals(1, repository.calls);
    }

    @Test public void initialErrorOffersManualRetryAndKnownEmptySnapshotHasHonestCounters() {
        repository.fail(GroupsError.Kind.ACCESS); settle();
        assertEquals(View.GONE, view(R.id.groups_loading).getVisibility());
        assertEquals(View.VISIBLE, view(R.id.groups_notice_row).getVisibility());
        assertEquals(activity.get().getString(R.string.groups_error_access), text(R.id.groups_notice));
        assertEquals(activity.get().getString(R.string.groups_load_failed), text(R.id.groups_empty));
        assertEquals("Все", ((TabLayout) view(R.id.groups_tabs)).getTabAt(0).getText().toString());
        assertTrue(view(R.id.groups_retry).performClick());
        assertEquals(2, repository.calls);
        repository.succeed(Collections.emptyList()); settle();
        assertEquals(0, rows());
        assertEquals(View.GONE, view(R.id.groups_notice_row).getVisibility());
        assertEquals("Все (0)", ((TabLayout) view(R.id.groups_tabs)).getTabAt(0).getText().toString());
        assertEquals(activity.get().getString(R.string.groups_empty), text(R.id.groups_empty));
        TabLayout tabs = (TabLayout) view(R.id.groups_tabs);
        tabs.selectTab(tabs.getTabAt(1));
        assertEquals(activity.get().getString(R.string.groups_no_management), text(R.id.groups_empty));
        assertEquals(2, repository.calls);
    }

    @Test public void refreshFailureKeepsRowsStopsSpinnerAndExplicitRetryReplacesSnapshot() {
        repository.succeed(fixtures()); settle();
        GroupsViewModel model = new ViewModelProvider(fragment()).get(GroupsViewModel.class);
        model.refresh(); model.refresh();
        assertEquals(2, repository.calls);
        assertEquals(3, rows());
        assertTrue(((SwipeRefreshLayout) view(R.id.groups_refresh)).isRefreshing());
        repository.fail(GroupsError.Kind.NETWORK); settle();
        assertEquals(3, rows());
        assertFalse(((SwipeRefreshLayout) view(R.id.groups_refresh)).isRefreshing());
        assertEquals(View.VISIBLE, view(R.id.groups_notice_row).getVisibility());
        assertTrue(view(R.id.groups_retry).performClick());
        assertEquals(3, repository.calls);
        repository.succeed(Collections.singletonList(group(44, "Новая группа", "new", false, 0))); settle();
        assertEquals(1, rows());
        assertEquals(44, list().getAdapter().getItemId(0));
        assertEquals(View.GONE, view(R.id.groups_notice_row).getVisibility());
    }

    @Test public void rotationRetainsSnapshotQueryAndManagementTabWithoutRefetch() {
        repository.succeed(fixtures()); settle();
        TabLayout tabs = (TabLayout) view(R.id.groups_tabs);
        tabs.selectTab(tabs.getTabAt(1));
        ((EditText) view(R.id.groups_search)).setText("Елка");
        activity.recreate(); settle();
        assertEquals(1, repository.calls);
        assertEquals(1, rows());
        assertEquals(11, list().getAdapter().getItemId(0));
        assertEquals("Елка", ((EditText) view(R.id.groups_search)).getText().toString());
        assertEquals(1, ((TabLayout) view(R.id.groups_tabs)).getSelectedTabPosition());
        assertFalse(repository.closed);
    }

    @Test public void pendingLoadSurvivesRotationAndOnlyTheNewViewReceivesTheResult() {
        RecyclerView oldList = list();
        activity.recreate(); settle();
        assertEquals(1, repository.calls);
        assertNull(oldList.getAdapter());
        assertNotSame(oldList, list());
        repository.succeed(fixtures()); settle();
        assertEquals(3, rows());
        assertEquals(View.GONE, view(R.id.groups_loading).getVisibility());
        assertEquals(1, repository.calls);
    }

    @Test public void recycledRowReplacesActivityTypeCountsAndNavigationPayload() {
        Group[] opened = {null};
        GroupsAdapter adapter = new GroupsAdapter(group -> opened[0] = group);
        GroupsAdapter.Holder holder = adapter.onCreateViewHolder(new FrameLayout(activity.get()), 0);
        Group first = new Group(11, "Первое", "", "first", "Здоровье", "group", 0, true, 3, 1234, false);
        adapter.submit(Collections.singletonList(first));
        adapter.onBindViewHolder(holder, 0);
        assertEquals("Здоровье", holder.subtitle.getText().toString());
        assertEquals(View.VISIBLE, holder.members.getVisibility());
        assertEquals(activity.get().getString(R.string.groups_members_count, NumberFormat.getIntegerInstance().format(1234)),
                holder.members.getText().toString());
        Group second = new Group(22, "Другое", "", "other", "", "event", 0, false, 0, -1, false);
        adapter.submit(Collections.singletonList(second));
        adapter.onBindViewHolder(holder, 0);
        assertEquals("Другое", holder.name.getText().toString());
        assertEquals(activity.get().getString(R.string.groups_type_event), holder.subtitle.getText().toString());
        assertEquals(View.GONE, holder.members.getVisibility());
        assertEquals("", holder.members.getText().toString());
        assertNotNull(holder.avatar.getDrawable());
        holder.itemView.performClick();
        assertSame(second, opened[0]);
        Group knownZero = new Group(22, "Другое", "", "other", "", "page", 0, false, 0, 0, false);
        adapter.submit(Collections.singletonList(knownZero));
        adapter.onBindViewHolder(holder, 0);
        assertEquals(View.VISIBLE, holder.members.getVisibility());
        assertEquals(activity.get().getString(R.string.groups_members_count, "0"), holder.members.getText().toString());
        holder.itemView.performClick();
        assertSame(knownZero, opened[0]);
        adapter.onViewRecycled(holder);
        assertFalse(holder.itemView.performClick());
        assertNull(shadowOf(activity.get()).getNextStartedActivity());
    }

    private TestFragment fragment() { return (TestFragment) activity.get().getSupportFragmentManager().findFragmentById(R.id.content); }
    private View view(int id) { return fragment().requireView().findViewById(id); }
    private RecyclerView list() { return (RecyclerView) view(R.id.groups_list); }
    private int rows() { return list().getAdapter().getItemCount(); }
    private String text(int id) { return ((TextView) view(id)).getText().toString(); }
    private void settle() {
        for (int i = 0; i < 2; i++) {
            View decor = activity.get().getWindow().getDecorView();
            decor.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, 360, 800);
            shadowOf(Looper.getMainLooper()).idle();
        }
    }
    static List<Group> fixtures() {
        return Arrays.asList(group(22, "Обычное", "plain_channel", false, 0),
                group(11, "Ёлка", "elka", false, 1), group(33, "Администратор", "admin", true, 3));
    }
    static Group group(long id, String name, String screenName, boolean admin, int level) {
        return new Group(id, name, "", screenName, "", "group", 0, admin, level, -1, false);
    }
    public static class Host extends AppCompatActivity {
        @Override protected void onCreate(Bundle state) {
            setTheme(R.style.Theme_VKTea);
            super.onCreate(state);
            FrameLayout content = new FrameLayout(this); content.setId(R.id.content); setContentView(content);
        }
    }
    public static class TestFragment extends GroupsFragment {
        @Override protected ViewModelProvider.Factory createModelFactory() {
            return new ViewModelProvider.Factory() {
                @NonNull @Override public <T extends ViewModel> T create(@NonNull Class<T> type) {
                    return type.cast(new GroupsViewModel(repository));
                }
            };
        }
        @Override protected void openGroup(Group group) { }
    }
    static final class FakeRepository implements GroupsRepository {
        int calls;
        boolean closed;
        Callback<List<Group>> pending;
        @Override public Request load(Callback<List<Group>> callback) {
            calls++; pending = callback; return () -> { };
        }
        void succeed(List<Group> groups) {
            Callback<List<Group>> callback = pending; pending = null; callback.success(groups);
        }
        void fail(GroupsError.Kind kind) {
            Callback<List<Group>> callback = pending; pending = null; callback.error(new GroupsError(kind));
        }
        @Override public void close() { closed = true; }
    }
}
