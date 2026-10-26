package com.vktea.groups;

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

/** Held main-thread callbacks: no account, image loading, VK or device I/O. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class GroupsVMTest {
    private final FakeRepository repository = new FakeRepository();
    private final ViewModelStore store = new ViewModelStore();
    private final GroupsViewModel model = provider().get(GroupsViewModel.class);
    @After public void clear() { store.clear(); }

    @Test public void localNameAndScreenNameSearchIntersectManagementAndPreserveApiOrder() {
        model.setQuery(" ЕЖ "); model.setManagementOnly(true);
        assertEquals(0, repository.requests.size());
        model.ensureLoaded(); model.ensureLoaded(); model.refresh(); assertEquals(1, repository.requests.size());
        repository.requests.get(0).success(Arrays.asList(group(3, "Ёж третий", "third", 1),
                group(2, "Еж второй", "second", 0), group(1, "Synthetic first", "ЁЖ_first", 2)));
        assertEquals(3, state().totalCount); assertEquals(2, state().managementCount);
        assertEquals(2, state().visibleItems.size()); assertEquals(3, state().visibleItems.get(0).id);
        assertEquals(1, state().visibleItems.get(1).id);
        model.setManagementOnly(false); assertEquals(3, state().visibleItems.size());
        assertEquals(2, state().visibleItems.get(1).id);
        model.setQuery("SYNTHETIC"); assertEquals(1, state().visibleItems.get(0).id);
        assertEquals(1, repository.requests.size());
        try { state().items.clear(); fail("Complete list must be immutable"); } catch (UnsupportedOperationException expected) { }
        try { state().visibleItems.clear(); fail("Filtered list must be immutable"); } catch (UnsupportedOperationException expected) { }
    }

    @Test public void refreshFailureRetainsSnapshotAndFiltersAcrossRetainedProviderUntilManualRetry() {
        model.ensureLoaded(); repository.requests.get(0).success(Collections.singletonList(group(1, "Synthetic", "one", 1)));
        model.setQuery("synthetic"); model.setManagementOnly(true);
        model.refresh(); assertTrue(state().refreshing && state().loaded && state().loading);
        assertEquals(1, state().visibleItems.size());
        repository.requests.get(1).error(new GroupsError(GroupsError.Kind.NETWORK));
        assertTrue(state().loaded); assertFalse(state().loading || state().refreshing);
        assertEquals(1, state().items.size()); assertEquals("synthetic", state().query); assertTrue(state().managementOnly);
        GroupsViewModel restored = provider().get(GroupsViewModel.class); restored.ensureLoaded();
        assertSame(model, restored); assertEquals(2, repository.requests.size());
        model.retry(); model.retry(); assertEquals(3, repository.requests.size());
        repository.requests.get(2).success(Collections.emptyList());
        assertTrue(state().loaded); assertNull(state().error); assertEquals(0, state().totalCount);
    }

    @Test public void initialErrorWaitsForManualRetryAndFiltersDoNotFetch() {
        assertEquals(0, repository.requests.size());
        model.ensureLoaded(); repository.requests.get(0).error(new GroupsError(GroupsError.Kind.RATE_LIMITED, 6, 0));
        model.ensureLoaded(); provider().get(GroupsViewModel.class).ensureLoaded(); model.setQuery("Synthetic");
        model.setManagementOnly(true); assertEquals(1, repository.requests.size());
        assertFalse(state().loaded); assertEquals(GroupsError.Kind.RATE_LIMITED, state().error.kind);
        model.retry(); repository.requests.get(1).success(Collections.emptyList());
        assertTrue(state().loaded); assertNull(state().error);
    }

    @Test public void authFailureClearsPreviousAccountsSnapshotWhileNetworkFailureRetainsIt() {
        model.ensureLoaded(); repository.requests.get(0).success(Collections.singletonList(group(1, "Old account", "old", 1)));
        model.refresh(); repository.requests.get(1).error(new GroupsError(GroupsError.Kind.NETWORK));
        assertTrue(state().loaded); assertEquals(1, state().items.size());
        model.retry(); repository.requests.get(2).error(new GroupsError(GroupsError.Kind.AUTH));
        assertFalse(state().loaded); assertEquals(0, state().totalCount); assertEquals(0, state().managementCount);
        assertTrue(state().items.isEmpty() && state().visibleItems.isEmpty());
        model.ensureLoaded(); assertEquals(3, repository.requests.size());
        model.retry(); repository.requests.get(3).success(Collections.singletonList(group(2, "Other account", "other", 0)));
        assertEquals(2, state().items.get(0).id);
    }

    @Test public void clearedAndDuplicateOldResultsCannotReplaceCurrentRows() {
        model.ensureLoaded(); Pending old = repository.requests.get(0);
        old.success(Collections.singletonList(group(1, "Old", "old", 0)));
        old.success(Collections.singletonList(group(9, "Duplicate", "duplicate", 0)));
        assertEquals(1, state().items.get(0).id);
        model.refresh(); Pending current = repository.requests.get(1);
        old.error(new GroupsError(GroupsError.Kind.ACCESS)); assertTrue(state().loading); assertNull(state().error);
        store.clear(); assertTrue(current.canceled && repository.closed);
        current.success(Collections.singletonList(group(99, "Late", "late", 1)));
        assertEquals(1, state().items.get(0).id);
    }

    @Test public void synchronousResultAndReentrantRefreshKeepNewRequestCancellable() {
        AtomicBoolean requestedAgain = new AtomicBoolean();
        androidx.lifecycle.Observer<GroupsViewModel.State> observer = value -> {
            if (value.loaded && !value.loading && requestedAgain.compareAndSet(false, true)) model.refresh();
        };
        model.state().observeForever(observer);
        repository.synchronous = Collections.singletonList(group(1, "Synthetic", "synthetic", 1));
        model.ensureLoaded(); assertEquals(2, repository.requests.size()); assertTrue(state().loading);
        model.state().removeObserver(observer); store.clear();
        assertFalse(repository.requests.get(0).canceled); assertTrue(repository.requests.get(1).canceled);
    }

    private GroupsViewModel.State state() { return model.getCurrentState(); }
    private ViewModelProvider provider() {
        return new ViewModelProvider(store, new ViewModelProvider.Factory() {
            @Override public <T extends ViewModel> T create(Class<T> type) { return type.cast(new GroupsViewModel(repository)); }
        });
    }
    private static Group group(long id, String name, String screenName, int role) {
        return new Group(id, name, "", screenName, "", "group", 0, false, role, 0, false);
    }
    private static final class Pending implements GroupsRepository.Request {
        final GroupsRepository.Callback<List<Group>> callback;
        boolean canceled;
        Pending(GroupsRepository.Callback<List<Group>> callback) { this.callback = callback; }
        void success(List<Group> rows) { callback.success(rows); }
        void error(GroupsError error) { callback.error(error); }
        @Override public void cancel() { canceled = true; }
    }
    private static final class FakeRepository implements GroupsRepository {
        final List<Pending> requests = new ArrayList<>();
        List<Group> synchronous;
        boolean closed;
        @Override public Request load(Callback<List<Group>> callback) {
            Pending request = new Pending(callback); requests.add(request);
            List<Group> immediate = synchronous; synchronous = null;
            if (immediate != null) request.success(immediate);
            return request;
        }
        @Override public void close() { closed = true; }
    }
}
