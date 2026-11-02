package com.vktea.notifications;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Real fragment views with synthetic events and no account or network access. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w360dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class NotificationsFragmentTest {
    static FakeRepository repository;
    private ActivityController<Host> host;

    @Before public void setup() {
        repository = new FakeRepository();
        host = Robolectric.buildActivity(Host.class).setup().visible();
        host.get().getSupportFragmentManager().beginTransaction().add(R.id.content, new TestFragment()).commitNow();
        settle();
    }
    @After public void close() { host.close(); repository = null; }

    @Test public void errorRetryAndEmptyResponseHaveDistinctVisibleStates() {
        assertEquals(1, repository.requests.size());
        assertEquals(View.VISIBLE, view(R.id.notifications_loading).getVisibility());
        repository.requests.get(0).error(NotificationsError.Kind.ACCESS); settle();
        assertEquals(View.GONE, view(R.id.notifications_loading).getVisibility());
        assertEquals(View.VISIBLE, view(R.id.notifications_notice_row).getVisibility());
        assertEquals(host.get().getString(R.string.notifications_error_access), text(R.id.notifications_notice));
        assertEquals(host.get().getString(R.string.notifications_load_failed), text(R.id.notifications_empty));
        view(R.id.notifications_retry).performClick();
        assertEquals(2, repository.requests.size());
        repository.requests.get(1).success(new EventPage(Collections.emptyList(), "", 0)); settle();
        assertEquals(View.GONE, view(R.id.notifications_notice_row).getVisibility());
        assertEquals(host.get().getString(R.string.notifications_empty), text(R.id.notifications_empty));
        assertEquals(0, list().getAdapter().getItemCount());
    }

    @Test public void paginationAndFailedRefreshKeepRowsAndAllowManualRetry() {
        repository.requests.get(0).success(page("Комментарий", "next")); settle();
        assertEquals(1, list().getAdapter().getItemCount());
        assertEquals(View.VISIBLE, view(R.id.notifications_more).getVisibility());
        view(R.id.notifications_more).performClick();
        assertEquals("next", repository.requests.get(1).cursor);
        repository.requests.get(1).success(page("Упоминание", "")); settle();
        assertEquals(2, list().getAdapter().getItemCount());
        assertEquals(View.GONE, view(R.id.notifications_more).getVisibility());
        new ViewModelProvider(fragment()).get(NotificationsViewModel.class).refresh();
        assertTrue(((SwipeRefreshLayout) view(R.id.notifications_refresh)).isRefreshing());
        repository.requests.get(2).error(NotificationsError.Kind.NETWORK); settle();
        assertFalse(((SwipeRefreshLayout) view(R.id.notifications_refresh)).isRefreshing());
        assertEquals(2, list().getAdapter().getItemCount());
        assertEquals(View.VISIBLE, view(R.id.notifications_notice_row).getVisibility());
        view(R.id.notifications_retry).performClick();
        assertEquals("", repository.requests.get(3).cursor);
        repository.requests.get(3).success(page("Новый ответ", "")); settle();
        assertEquals(1, list().getAdapter().getItemCount());
        assertEquals(View.GONE, view(R.id.notifications_notice_row).getVisibility());
    }

    @Test public void recreationKeepsPendingLoadAndOnlyNewViewReceivesResults() {
        RecyclerView old = list(); host.recreate(); settle();
        assertNull(old.getAdapter());
        assertNotSame(old, list());
        assertEquals(1, repository.requests.size());
        repository.requests.get(0).success(page("Ответ", "")); settle();
        assertEquals(1, list().getAdapter().getItemCount());
        assertEquals(View.GONE, view(R.id.notifications_loading).getVisibility());
    }

    @Test public void recycledRowsDropPriorContentAndOnlySafeVkTargetsOpen() {
        NotificationsAdapter adapter = (NotificationsAdapter) list().getAdapter();
        NotificationsAdapter.Holder holder = adapter.onCreateViewHolder(list(), 0);
        NotificationEvent safe = event("Первый", "Контекст", "https://vk.com/wall1_2");
        adapter.submit(Collections.singletonList(safe)); adapter.onBindViewHolder(holder, 0);
        assertEquals("Контекст", holder.parent.getText().toString());
        assertEquals(View.VISIBLE, holder.parent.getVisibility());
        holder.itemView.performClick();
        Intent opened = shadowOf(host.get()).getNextStartedActivity();
        assertNotNull(opened); assertEquals(Intent.ACTION_VIEW, opened.getAction());
        assertEquals("https://vk.com/wall1_2", opened.getDataString());
        NotificationEvent empty = event("Второй", "", "");
        adapter.submit(Collections.singletonList(empty)); adapter.onBindViewHolder(holder, 0);
        assertEquals("Второй", holder.text.getText().toString());
        assertEquals(View.GONE, holder.parent.getVisibility());
        assertFalse(holder.itemView.performClick());
        fragment().openEvent(event("Внешняя ссылка", "", "https://vk.com.evil.invalid/path"));
        fragment().openEvent(event("Небезопасная ссылка", "", "http://vk.com/wall1_2"));
        assertNull(shadowOf(host.get()).getNextStartedActivity());
    }

    private TestFragment fragment() { return (TestFragment) host.get().getSupportFragmentManager().findFragmentById(R.id.content); }
    private View view(int id) { return fragment().requireView().findViewById(id); }
    private RecyclerView list() { return (RecyclerView) view(R.id.notifications_list); }
    private String text(int id) { return ((TextView) view(id)).getText().toString(); }
    private void settle() { shadowOf(Looper.getMainLooper()).idle(); }
    private static NotificationEvent event(String text, String parent, String url) {
        return new NotificationEvent("comment_post", 1, "Автор", "", text, parent, url);
    }
    private static EventPage page(String text, String cursor) {
        return new EventPage(Collections.singletonList(event(text, "", "")), cursor, 0);
    }
    public static class Host extends AppCompatActivity {
        @Override protected void onCreate(Bundle state) {
            setTheme(R.style.Theme_VKTea); super.onCreate(state);
            FrameLayout content = new FrameLayout(this); content.setId(R.id.content); setContentView(content);
        }
    }
    public static class TestFragment extends NotificationsFragment {
        @Override protected ViewModelProvider.Factory createModelFactory() {
            return new ViewModelProvider.Factory() {
                @NonNull @Override public <T extends ViewModel> T create(@NonNull Class<T> type) {
                    return type.cast(new NotificationsViewModel(repository));
                }
            };
        }
    }
    static final class FakeRepository implements NotificationsRepository {
        final List<Pending> requests = new ArrayList<>();
        @Override public Request load(String cursor, Callback<EventPage> callback) {
            Pending pending = new Pending(cursor, callback); requests.add(pending); return pending;
        }
        @Override public void close() { }
    }
    static final class Pending implements NotificationsRepository.Request {
        final String cursor; final NotificationsRepository.Callback<EventPage> callback;
        Pending(String cursor, NotificationsRepository.Callback<EventPage> callback) { this.cursor = cursor; this.callback = callback; }
        void success(EventPage page) { callback.success(page); }
        void error(NotificationsError.Kind kind) { callback.error(new NotificationsError(kind)); }
        @Override public void cancel() { }
    }
}
