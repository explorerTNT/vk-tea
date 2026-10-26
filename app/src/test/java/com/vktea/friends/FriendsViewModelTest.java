package com.vktea.friends;

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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Held main-thread callbacks, no session, images, VK request or device I/O. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class FriendsViewModelTest {
    private final FakeRepository repository = new FakeRepository();
    private final ViewModelStore store = new ViewModelStore();
    private final FriendsViewModel model = provider().get(FriendsViewModel.class);
    @After public void clear() { store.clear(); }

    @Test public void filtersAreLocalIntersectionWithYoNormalizationAndFirstnameSort() {
        model.setQuery(" ЕГОР "); model.setOnlineOnly(true);
        assertEquals(0, repository.requests.size());
        model.ensureLoaded(); model.ensureLoaded(); model.refresh();
        assertEquals(1, repository.requests.size());
        repository.requests.get(0).success(Arrays.asList(friend(3, "Яна", "Вторая", true),
                friend(2, "Ёгор", "Первый", false), friend(1, "Егор", "Первый", true)));
        assertEquals(3, state().totalCount); assertEquals(2, state().onlineCount);
        assertEquals(1, state().visibleItems.size()); assertEquals(1, state().visibleItems.get(0).id);
        model.setOnlineOnly(false);
        assertEquals(2, state().visibleItems.size());
        assertEquals(1, state().visibleItems.get(0).id); assertEquals(2, state().visibleItems.get(1).id);
        model.setQuery("");
        assertEquals(3, state().visibleItems.get(2).id);
        assertEquals(3, state().items.get(0).id);
        assertEquals(1, repository.requests.size());
        try { state().items.clear(); fail("Complete list must be immutable"); } catch (UnsupportedOperationException expected) { }
        try { state().visibleItems.clear(); fail("Filtered list must be immutable"); } catch (UnsupportedOperationException expected) { }
    }

    @Test public void refreshFailureKeepsRowsAndFiltersUntilExplicitRetrySucceeds() {
        model.ensureLoaded(); repository.requests.get(0).success(Collections.singletonList(friend(1, "Synthetic", "Friend", true)));
        model.setQuery("synthetic"); model.setOnlineOnly(true);
        model.refresh(); assertTrue(state().refreshing && state().loaded && state().loading);
        assertEquals(1, state().visibleItems.size());
        repository.requests.get(1).error(new FriendsError(FriendsError.Kind.NETWORK));
        assertTrue(state().loaded); assertFalse(state().loading || state().refreshing);
        assertEquals(1, state().items.size()); assertEquals("synthetic", state().query); assertTrue(state().onlineOnly);
        model.ensureLoaded(); assertSame(model, provider().get(FriendsViewModel.class));
        assertEquals(2, repository.requests.size());
        model.retry(); model.retry(); assertEquals(3, repository.requests.size());
        repository.requests.get(2).success(Collections.emptyList());
        assertTrue(state().loaded); assertNull(state().error); assertEquals(0, state().totalCount);
    }

    @Test public void firstFailureWaitsForManualRetryAcrossRetainedProviderAndFilterChanges() {
        assertEquals(0, repository.requests.size());
        model.ensureLoaded(); repository.requests.get(0).error(new FriendsError(FriendsError.Kind.RATE_LIMITED, 6, 0));
        model.ensureLoaded(); provider().get(FriendsViewModel.class).ensureLoaded(); model.setQuery("Synthetic");
        assertEquals(1, repository.requests.size());
        assertFalse(state().loaded); assertEquals(FriendsError.Kind.RATE_LIMITED, state().error.kind);
        model.retry(); repository.requests.get(1).success(Collections.emptyList());
        assertTrue(state().loaded); assertNull(state().error);
    }

    @Test public void authenticationFailureClearsPreviousAccountsSnapshotWhileNetworkFailureRetainsIt() {
        model.ensureLoaded(); repository.requests.get(0).success(Collections.singletonList(friend(1, "Synthetic", "Friend", true)));
        model.refresh(); repository.requests.get(1).error(new FriendsError(FriendsError.Kind.NETWORK));
        assertTrue(state().loaded); assertEquals(1, state().items.size());
        model.retry(); repository.requests.get(2).error(new FriendsError(FriendsError.Kind.AUTH));
        assertFalse(state().loaded); assertEquals(0, state().totalCount); assertEquals(0, state().onlineCount);
        assertTrue(state().items.isEmpty() && state().visibleItems.isEmpty());
        model.ensureLoaded(); assertEquals(3, repository.requests.size());
        model.retry(); repository.requests.get(3).success(Collections.singletonList(friend(2, "Other", "Account", false)));
        assertEquals(2, state().items.get(0).id);
    }

    @Test public void clearedOrDuplicateOldResultsCannotReplaceCurrentRows() {
        model.ensureLoaded(); Pending old = repository.requests.get(0);
        old.success(Collections.singletonList(friend(1, "Old", "Friend", false)));
        old.success(Collections.singletonList(friend(9, "Duplicate", "Old", false)));
        assertEquals(1, state().items.get(0).id);
        model.refresh(); Pending current = repository.requests.get(1);
        old.error(new FriendsError(FriendsError.Kind.ACCESS));
        assertTrue(state().loading); assertNull(state().error);
        store.clear(); assertTrue(current.canceled && repository.closed);
        current.success(Collections.singletonList(friend(99, "Late", "Friend", true)));
        assertEquals(1, state().items.get(0).id);
    }

    @Test public void synchronousResultAndReentrantRefreshKeepTheNewRequestCancellable() {
        AtomicBoolean requestedAgain = new AtomicBoolean();
        androidx.lifecycle.Observer<FriendsViewModel.State> observer = value -> {
            if (value.loaded && !value.loading && requestedAgain.compareAndSet(false, true)) model.refresh();
        };
        model.state().observeForever(observer);
        repository.synchronous = Collections.singletonList(friend(1, "Synthetic", "Friend", true));
        model.ensureLoaded();
        assertEquals(2, repository.requests.size());
        assertTrue(state().loading);
        model.state().removeObserver(observer);
        store.clear();
        assertFalse(repository.requests.get(0).canceled);
        assertTrue(repository.requests.get(1).canceled);
    }

    @Test public void retainedListQueryAndOnlineModeDoNotCauseAnotherLoad() {
        model.ensureLoaded(); repository.requests.get(0).success(Arrays.asList(friend(1, "First", "One", true), friend(2, "Second", "Two", false)));
        model.setQuery("First"); model.setOnlineOnly(true);
        FriendsViewModel restored = provider().get(FriendsViewModel.class);
        restored.ensureLoaded();
        assertSame(model, restored); assertEquals("First", restored.getCurrentState().query);
        assertTrue(restored.getCurrentState().onlineOnly); assertEquals(1, restored.getCurrentState().visibleItems.size());
        assertEquals(1, repository.requests.size());
    }

    private FriendsViewModel.State state() { return model.getCurrentState(); }
    private ViewModelProvider provider() {
        return new ViewModelProvider(store, new ViewModelProvider.Factory() {
            @Override public <T extends ViewModel> T create(Class<T> type) { return type.cast(new FriendsViewModel(repository)); }
        });
    }
    private static Friend friend(long id, String first, String last, boolean online) { return new Friend(id, first, last, "", online, false, 0, 0, ""); }
    private static final class Pending implements FriendsRepository.Request {
        final FriendsRepository.Callback<List<Friend>> callback;
        boolean canceled;
        Pending(FriendsRepository.Callback<List<Friend>> callback) { this.callback = callback; }
        void success(List<Friend> rows) { callback.success(rows); }
        void error(FriendsError error) { callback.error(error); }
        @Override public void cancel() { canceled = true; }
    }
    private static final class FakeRepository implements FriendsRepository {
        final List<Pending> requests = new ArrayList<>();
        List<Friend> synchronous;
        boolean closed;
        @Override public Request load(Callback<List<Friend>> callback) {
            Pending request = new Pending(callback); requests.add(request);
            List<Friend> immediate = synchronous; synchronous = null;
            if (immediate != null) request.success(immediate);
            return request;
        }
        @Override public void close() { closed = true; }
    }
}
