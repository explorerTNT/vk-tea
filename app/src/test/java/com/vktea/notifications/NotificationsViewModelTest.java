package com.vktea.notifications;

import static org.junit.Assert.*;

import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStore;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class NotificationsViewModelTest {
    private final FakeRepository repository = new FakeRepository();
    private final ViewModelStore store = new ViewModelStore();
    private final NotificationsViewModel model = new ViewModelProvider(store, new ViewModelProvider.Factory() {
        @Override public <T extends ViewModel> T create(Class<T> type) {
            return type.cast(new NotificationsViewModel(repository));
        }
    }).get(NotificationsViewModel.class);

    @After public void close() { store.clear(); }

    @Test public void pagingFailureRetainsItemsAndRetryUsesSameCursor() {
        model.ensureLoaded(); model.ensureLoaded();
        assertEquals(1, repository.requests.size());
        assertEquals("", repository.requests.get(0).cursor);
        repository.requests.get(0).success(page("first", "cursor-1"));
        model.loadMore(); model.loadMore();
        assertEquals(2, repository.requests.size());
        assertEquals("cursor-1", repository.requests.get(1).cursor);
        assertTrue(state().loadingMore);
        repository.requests.get(1).error(NotificationsError.Kind.NETWORK);
        assertEquals("first", state().items.get(0).text);
        assertFalse(state().loading);
        model.ensureLoaded(); model.loadMore();
        assertEquals(2, repository.requests.size());
        model.retry(); assertEquals("cursor-1", repository.requests.get(2).cursor);
        repository.requests.get(2).success(page("second", "cursor-1"));
        assertEquals(2, state().items.size());
        assertEquals("first", state().items.get(0).text);
        assertEquals("second", state().items.get(1).text);
        assertEquals("", state().nextFrom); // A repeated cursor cannot create an infinite paging loop.
        model.loadMore(); assertEquals(3, repository.requests.size());
        try { state().items.clear(); fail("Published rows must be immutable"); }
        catch (UnsupportedOperationException expected) { }
    }

    @Test public void failedRefreshRetainsRowsButAuthenticationFailureClearsThem() {
        model.ensureLoaded(); repository.requests.get(0).success(page("old account", "next"));
        model.refresh(); assertTrue(state().refreshing);
        repository.requests.get(1).error(NotificationsError.Kind.NETWORK);
        assertEquals("old account", state().items.get(0).text);
        model.retry(); assertEquals("", repository.requests.get(2).cursor);
        repository.requests.get(2).error(NotificationsError.Kind.AUTH);
        assertFalse(state().loaded); assertTrue(state().items.isEmpty());
        assertEquals("", state().nextFrom);
        model.ensureLoaded(); assertEquals(3, repository.requests.size());
        model.retry(); repository.requests.get(3).success(page("new account", ""));
        assertEquals("new account", state().items.get(0).text);
    }

    @Test public void staleCallbacksCannotReplaceNewerStateAndClearCancelsRequest() {
        model.ensureLoaded(); Pending old = repository.requests.get(0);
        old.success(page("first", ""));
        model.refresh(); Pending current = repository.requests.get(1);
        old.success(page("stale", "")); old.error(NotificationsError.Kind.AUTH);
        assertTrue(state().loading); assertEquals("first", state().items.get(0).text);
        store.clear(); assertTrue(current.canceled); assertTrue(repository.closed);
        current.success(page("late", ""));
        assertEquals("first", state().items.get(0).text);
    }

    private NotificationsViewModel.State state() { return model.getCurrentState(); }
    private static EventPage page(String text, String cursor) {
        NotificationEvent event = new NotificationEvent("comment_post", 1, "Author", "", text, "", "");
        return new EventPage(Collections.singletonList(event), cursor, 0);
    }
    private static final class Pending implements NotificationsRepository.Request {
        final String cursor; final NotificationsRepository.Callback<EventPage> callback; boolean canceled;
        Pending(String cursor, NotificationsRepository.Callback<EventPage> callback) { this.cursor = cursor; this.callback = callback; }
        void success(EventPage page) { callback.success(page); }
        void error(NotificationsError.Kind kind) { callback.error(new NotificationsError(kind)); }
        @Override public void cancel() { canceled = true; }
    }
    private static final class FakeRepository implements NotificationsRepository {
        final List<Pending> requests = new ArrayList<>(); boolean closed;
        @Override public Request load(String cursor, Callback<EventPage> callback) {
            Pending pending = new Pending(cursor, callback); requests.add(pending); return pending;
        }
        @Override public void close() { closed = true; }
    }
}
