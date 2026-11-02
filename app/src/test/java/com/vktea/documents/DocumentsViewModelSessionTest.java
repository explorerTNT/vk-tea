package com.vktea.documents;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.content.Context;
import android.os.Looper;
import com.vktea.auth.Session;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class DocumentsViewModelSessionTest {
    private Context context;
    private FakeRepository repository;
    private DocumentsViewModel model;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
        Session.save(context, "synthetic-token-a", "101");
        shadowOf(Looper.getMainLooper()).idle();
        repository = new FakeRepository(); model = new DocumentsViewModel(repository, context);
    }
    @After public void close() {
        model.onCleared();
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
        shadowOf(Looper.getMainLooper()).idle();
    }
    private DocumentsViewModel.State state() { return model.state().getValue(); }
    private static DocumentItem item(long id) { return new DocumentItem(id, 101, "Synthetic", "pdf", 5, "https://vk.com/doc"); }

    @Test public void accountChangeClearsRowsSearchAndOffsetAndLoadsNewAccount() {
        model.setQuery("private search"); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));
        repository.callbacks.get(0).success(new DocumentsRepository.Page(Collections.singletonList(item(1)), 2, 1));
        model.loadNext(); assertEquals(1, repository.offsets.get(1).intValue());
        DocumentsRepository.Callback oldPage = repository.callbacks.get(1);
        Session.save(context, "synthetic-token-b", "202");
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(repository.canceled.get(1));
        assertTrue(state().items.isEmpty()); assertEquals("", state().query);
        assertEquals(3, repository.callbacks.size()); assertEquals("", repository.queries.get(2));
        assertEquals(0, repository.offsets.get(2).intValue());
        oldPage.success(new DocumentsRepository.Page(Collections.singletonList(item(2)), 2, 1));
        assertTrue(state().items.isEmpty());
        DocumentItem second = new DocumentItem(3, 202, "B", "pdf", 5, "https://vk.com/doc3");
        repository.callbacks.get(2).success(new DocumentsRepository.Page(Collections.singletonList(second), 1, 1));
        assertEquals(1, state().items.size()); assertEquals(202, state().items.get(0).ownerId);
    }
    @Test public void pagingChecksGenerationEvenBeforeListenerRuns() {
        model.ensureLoaded();
        repository.callbacks.get(0).success(new DocumentsRepository.Page(Collections.singletonList(item(1)), 3, 1));
        Session.save(context, "synthetic-token-b", "202");
        model.loadNext();
        assertTrue(state().items.isEmpty()); assertEquals(2, repository.callbacks.size());
        assertEquals(0, repository.offsets.get(1).intValue());
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(2, repository.callbacks.size());
    }
    @Test public void callbackChecksGenerationEvenBeforeSessionNotificationRuns() {
        model.ensureLoaded(); DocumentsRepository.Callback stale = repository.callbacks.get(0);
        Session.save(context, "synthetic-token-b", "202");
        stale.success(new DocumentsRepository.Page(Collections.singletonList(item(1)), 1, 1));
        assertTrue(state().items.isEmpty()); assertEquals(2, repository.callbacks.size());
        assertEquals(0, repository.offsets.get(1).intValue());
        shadowOf(Looper.getMainLooper()).idle(); assertEquals(2, repository.callbacks.size());
    }
    @Test public void logoutImmediatelyRemovesDocumentsAndCancelsOldPage() {
        model.ensureLoaded();
        repository.callbacks.get(0).success(new DocumentsRepository.Page(Collections.singletonList(item(1)), 2, 1));
        model.loadNext(); Session.clear(context); shadowOf(Looper.getMainLooper()).idle();
        assertTrue(state().items.isEmpty()); assertTrue(repository.canceled.get(1));
        assertEquals(2, repository.callbacks.size()); assertFalse(state().loading); assertNull(state().error);
        model.ensureLoaded(); model.loadNext(); model.refresh(); model.retry(); model.setQuery("must not load");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        assertEquals(2, repository.callbacks.size()); assertEquals("", state().query);
    }
    @Test public void oldCallbackBeforeLogoutNotificationDoesNotStartAnotherRequest() {
        model.ensureLoaded(); DocumentsRepository.Callback stale = repository.callbacks.get(0);
        Session.clear(context);
        stale.success(new DocumentsRepository.Page(Collections.singletonList(item(1)), 1, 1));
        assertTrue(state().items.isEmpty()); assertEquals(1, repository.callbacks.size());
        assertTrue(repository.canceled.get(0)); assertFalse(state().loading); assertNull(state().error);
        shadowOf(Looper.getMainLooper()).idle(); assertEquals(1, repository.callbacks.size());
    }
    @Test public void tokenRefreshForSameAccountKeepsQueryButReloadsFromFirstPage() {
        model.setQuery("report"); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));
        repository.callbacks.get(0).success(new DocumentsRepository.Page(Collections.singletonList(item(1)), 2, 1));
        model.loadNext();
        Session.save(context, "synthetic-refreshed-a", "101"); shadowOf(Looper.getMainLooper()).idle();
        assertTrue(repository.canceled.get(1)); assertTrue(state().items.isEmpty());
        assertEquals("report", state().query); assertEquals("report", repository.queries.get(2));
        assertEquals(0, repository.offsets.get(2).intValue());
    }
    @Test public void clearedModelDoesNotReactToLaterSessionChanges() {
        model.ensureLoaded(); model.onCleared();
        assertTrue(repository.canceled.get(0)); assertTrue(repository.closed);
        Session.save(context, "synthetic-token-b", "202");
        shadowOf(Looper.getMainLooper()).idle(); assertEquals(1, repository.callbacks.size());
    }
    private static final class FakeRepository implements DocumentsRepository {
        final List<Callback> callbacks = new ArrayList<>(); final List<String> queries = new ArrayList<>();
        final List<Integer> offsets = new ArrayList<>(); final List<Boolean> canceled = new ArrayList<>(); boolean closed;
        @Override public Request load(String query, int offset, Callback callback) {
            callbacks.add(callback); queries.add(query); offsets.add(offset); int index = canceled.size(); canceled.add(false);
            return () -> canceled.set(index, true);
        }
        @Override public void close() { closed = true; }
    }
}
