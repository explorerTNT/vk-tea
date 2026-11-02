package com.vktea.documents;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.os.Looper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class DocumentsViewModelTest {
    private final FakeRepository repository = new FakeRepository();
    private final DocumentsViewModel model = new DocumentsViewModel(repository);
    private DocumentsViewModel.State state() { return model.state().getValue(); }
    private static DocumentItem item(long id) { return new DocumentItem(id, 7, "file" + id, "pdf", 100, "https://vk.com/doc"); }

    @Test public void paginationUsesRawOffsetAndDeduplicatesDocuments() {
        model.ensureLoaded();
        assertTrue(state().loading);
        assertEquals(0, repository.offsets.get(0).intValue());
        repository.callbacks.get(0).success(new DocumentsRepository.Page(Arrays.asList(item(1), item(2)), 4, 2));
        assertTrue(state().loaded); assertTrue(state().hasMore);
        model.loadNext(); assertEquals(2, repository.offsets.get(1).intValue());
        repository.callbacks.get(1).success(new DocumentsRepository.Page(Arrays.asList(item(2), item(3)), 4, 2));
        assertEquals(3, state().items.size()); assertFalse(state().hasMore);
        model.loadNext(); assertEquals(2, repository.callbacks.size());
    }
    @Test public void queryCancelsOldRequestsAndDebouncesLatestSearch() {
        model.ensureLoaded();
        DocumentsRepository.Callback stale = repository.callbacks.get(0);
        model.setQuery("a"); model.setQuery("ab");
        assertTrue(repository.canceled.get(0));
        stale.success(new DocumentsRepository.Page(Collections.singletonList(item(1)), 1, 1));
        assertTrue(state().items.isEmpty());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));
        assertEquals(2, repository.callbacks.size()); assertEquals("ab", repository.queries.get(1));
        repository.callbacks.get(1).success(new DocumentsRepository.Page(Collections.singletonList(item(2)), 1, 1));
        assertEquals(2, state().items.get(0).id);
    }
    @Test public void paginationFailureWaitsForExplicitRetryAndKeepsRows() {
        model.ensureLoaded(); repository.callbacks.get(0).success(new DocumentsRepository.Page(Collections.singletonList(item(1)), 2, 1));
        model.loadNext(); repository.callbacks.get(1).error(new DocumentsRepository.Error(DocumentsRepository.Error.Kind.RATE_LIMIT));
        assertEquals(1, state().items.size()); assertNotNull(state().error);
        model.loadNext(); assertEquals(2, repository.callbacks.size());
        model.retry(); assertEquals(3, repository.callbacks.size()); assertEquals(1, repository.offsets.get(2).intValue());
    }
    @Test public void authFailureDoesNotLeavePreviousAccountDocumentsVisible() {
        model.ensureLoaded(); repository.callbacks.get(0).success(new DocumentsRepository.Page(Collections.singletonList(item(1)), 2, 1));
        model.loadNext(); repository.callbacks.get(1).error(new DocumentsRepository.Error(DocumentsRepository.Error.Kind.AUTH));
        assertTrue(state().items.isEmpty()); assertFalse(state().loaded);
    }
    private static final class FakeRepository implements DocumentsRepository {
        final List<Callback> callbacks = new ArrayList<>(); final List<String> queries = new ArrayList<>();
        final List<Integer> offsets = new ArrayList<>(); final List<Boolean> canceled = new ArrayList<>();
        @Override public Request load(String query, int offset, Callback callback) {
            callbacks.add(callback); queries.add(query); offsets.add(offset); int index = canceled.size(); canceled.add(false);
            return () -> canceled.set(index, true);
        }
        @Override public void close() { }
    }
}
