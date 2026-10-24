package com.vktea.friends;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Real fragment views with synthetic read-only callbacks, no session or network. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w360dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class FriendsFragmentTest {
    static FakeRepository repository;
    private ActivityController<Host> activity;

    @Before public void create() {
        repository = new FakeRepository();
        activity = Robolectric.buildActivity(Host.class).setup().visible();
        activity.get().getSupportFragmentManager().beginTransaction().add(R.id.content, new TestFragment()).commitNow();
        settle();
    }
    @After public void close() { activity.close(); repository = null; }

    @Test public void tabsUnicodeSearchAndClearFilterTheCompleteSnapshotWithoutAnotherRequest() {
        assertEquals(1, repository.calls);
        assertEquals(View.VISIBLE, view(R.id.friends_loading).getVisibility());
        repository.succeed(fixtures()); settle();
        assertEquals(3, rows());
        TabLayout tabs = (TabLayout) view(R.id.friends_tabs);
        assertEquals("Все (3)", tabs.getTabAt(0).getText().toString());
        assertEquals("Онлайн (2)", tabs.getTabAt(1).getText().toString());
        tabs.selectTab(tabs.getTabAt(1));
        assertEquals(2, rows());
        ((EditText) view(R.id.friends_search)).setText("елка");
        assertEquals(1, rows());
        assertEquals(33, list().getAdapter().getItemId(0));
        assertTrue(view(R.id.friends_clear_search).performClick());
        assertEquals(2, rows());
        tabs.selectTab(tabs.getTabAt(0));
        assertEquals(3, rows());
        assertEquals(1, repository.calls);
    }

    @Test public void refreshFailureKeepsRowsStopsSpinnerAndExplicitRetryReplacesSnapshot() {
        repository.succeed(fixtures()); settle();
        FriendsViewModel model = new ViewModelProvider(fragment()).get(FriendsViewModel.class);
        model.refresh(); model.refresh();
        assertEquals(2, repository.calls);
        assertEquals(3, rows());
        assertTrue(((SwipeRefreshLayout) view(R.id.friends_refresh)).isRefreshing());
        repository.fail(); settle();
        assertEquals(3, rows());
        assertFalse(((SwipeRefreshLayout) view(R.id.friends_refresh)).isRefreshing());
        assertEquals(View.VISIBLE, view(R.id.friends_notice_row).getVisibility());
        assertTrue(view(R.id.friends_retry).performClick());
        assertEquals(3, repository.calls);
        repository.succeed(Collections.singletonList(friend(44, "Новый", false))); settle();
        assertEquals(1, rows());
        assertEquals(44, list().getAdapter().getItemId(0));
        assertEquals(View.GONE, view(R.id.friends_notice_row).getVisibility());
    }

    @Test public void rotationRetainsSnapshotQueryAndOnlineTabWithoutRefetch() {
        repository.succeed(fixtures()); settle();
        ((TabLayout) view(R.id.friends_tabs)).selectTab(((TabLayout) view(R.id.friends_tabs)).getTabAt(1));
        ((EditText) view(R.id.friends_search)).setText("Елка");
        activity.recreate(); settle();
        assertEquals(1, repository.calls);
        assertEquals(1, rows());
        assertEquals("Елка", ((EditText) view(R.id.friends_search)).getText().toString());
        assertEquals(1, ((TabLayout) view(R.id.friends_tabs)).getSelectedTabPosition());
        assertFalse(repository.closed);
    }

    @Test public void recycledRowReplacesOnlineStateAvatarAndBothNavigationTargets() {
        long[] profile = {0}, chat = {0};
        FriendsAdapter adapter = new FriendsAdapter(new FriendsAdapter.Listener() {
            @Override public void openProfile(Friend friend) { profile[0] = friend.id; }
            @Override public void openChat(Friend friend) { chat[0] = friend.id; }
        });
        FriendsAdapter.Holder holder = adapter.onCreateViewHolder(new FrameLayout(activity.get()), 0);
        adapter.submit(Collections.singletonList(friend(11, "Онлайн", true)));
        adapter.onBindViewHolder(holder, 0);
        assertEquals(View.VISIBLE, holder.online.getVisibility());
        holder.message.performClick(); assertEquals(11, chat[0]);
        adapter.submit(Collections.singletonList(friend(22, "Другой", false)));
        adapter.onBindViewHolder(holder, 0);
        assertEquals(View.GONE, holder.online.getVisibility());
        assertNotNull(holder.avatar.getDrawable());
        assertEquals("Другой Тест", holder.name.getText().toString());
        holder.itemView.performClick(); holder.message.performClick();
        assertEquals(22, profile[0]); assertEquals(22, chat[0]);
        adapter.onViewRecycled(holder);
        assertFalse(holder.itemView.performClick()); assertFalse(holder.message.performClick());
    }

    private TestFragment fragment() { return (TestFragment) activity.get().getSupportFragmentManager().findFragmentById(R.id.content); }
    private View view(int id) { return fragment().requireView().findViewById(id); }
    private RecyclerView list() { return (RecyclerView) view(R.id.friends_list); }
    private int rows() { return list().getAdapter().getItemCount(); }
    private void settle() {
        for (int i = 0; i < 2; i++) {
            View decor = activity.get().getWindow().getDecorView();
            decor.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, 360, 800);
            shadowOf(Looper.getMainLooper()).idle();
        }
    }
    static List<Friend> fixtures() { return Arrays.asList(friend(11, "Егор", false), friend(22, "Анна", true), friend(33, "Ёлка", true)); }
    static Friend friend(long id, String name, boolean online) { return new Friend(id, name, "Тест", "", online, false, 0, 0, ""); }
    public static class Host extends AppCompatActivity {
        @Override protected void onCreate(Bundle state) {
            setTheme(R.style.Theme_VKTea); super.onCreate(state);
            FrameLayout content = new FrameLayout(this); content.setId(R.id.content); setContentView(content);
        }
    }
    public static class TestFragment extends FriendsFragment {
        @Override protected ViewModelProvider.Factory createModelFactory() {
            return new ViewModelProvider.Factory() {
                @NonNull @Override public <T extends ViewModel> T create(@NonNull Class<T> type) { return type.cast(new FriendsViewModel(repository)); }
            };
        }
    }
    static final class FakeRepository implements FriendsRepository {
        int calls; boolean closed;
        Callback<List<Friend>> pending;
        @Override public Request load(Callback<List<Friend>> callback) { calls++; pending = callback; return () -> {}; }
        void succeed(List<Friend> friends) { Callback<List<Friend>> callback = pending; pending = null; callback.success(friends); }
        void fail() { Callback<List<Friend>> callback = pending; pending = null; callback.error(new FriendsError(FriendsError.Kind.NETWORK)); }
        @Override public void close() { closed = true; }
    }
}
