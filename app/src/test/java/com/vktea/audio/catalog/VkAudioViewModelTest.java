package com.vktea.audio.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class VkAudioViewModelTest {
    private final ViewModelStore store = new ViewModelStore();
    private FakeRepository repository;
    private VkAudioViewModel model;

    @Before
    public void setup() {
        repository = new FakeRepository();
        model = provider().get(VkAudioViewModel.class);
    }

    @After
    public void clear() {
        store.clear();
    }

    @Test
    public void retainedProviderAndRepeatedEnsureLoadedKeepOneRequestAndOneResult() {
        model.ensureLoaded();
        model.ensureLoaded();
        VkAudioViewModel recreated = provider().get(VkAudioViewModel.class);
        recreated.ensureLoaded();
        assertSame(model, recreated);
        assertEquals(1, repository.pending.size());
        assertTrue(model.getCurrentState().loading);

        repository.last().success(new VkAudioPage(1, Collections.singletonList(track(1)), 1));
        recreated.ensureLoaded();
        assertEquals(1, repository.pending.size());
        assertTrue(model.getCurrentState().loaded);
        assertFalse(model.getCurrentState().hasMore);
    }

    @Test
    public void pagingUsesRawConsumedOffsetAndDeduplicatesStableIdsWithoutMutatingOldSnapshot() {
        model.ensureLoaded();
        repository.last().success(new VkAudioPage(5, Arrays.asList(track(1), track(2)), 3));
        VkAudioViewModel.State first = model.getCurrentState();
        model.loadNext();
        assertEquals(3, repository.last().offset);
        repository.last().success(new VkAudioPage(5, Arrays.asList(track(2), track(3)), 2));

        VkAudioViewModel.State second = model.getCurrentState();
        assertEquals(3, second.items.size());
        assertEquals("vk-audio:41_3", second.items.get(2).mediaId());
        assertEquals(5, second.nextOffset);
        assertFalse(second.hasMore);
        assertEquals(2, first.items.size());
        assertNotSame(first.items, second.items);
        model.loadNext();
        assertEquals(2, repository.pending.size());
    }

    @Test
    public void refreshKeepsVisibleRowsUntilSuccessAndExplicitRetryRepeatsFailedOffset() {
        model.ensureLoaded();
        repository.last().success(new VkAudioPage(4, Collections.singletonList(track(1)), 2));
        model.loadNext();
        repository.last().error(new VkAudioError(VkAudioError.Kind.NETWORK));
        model.ensureLoaded();
        model.loadNext();
        assertEquals(2, repository.pending.size());
        assertEquals(1, model.getCurrentState().items.size());
        model.retry();
        assertEquals(2, repository.last().offset);
        repository.last().success(new VkAudioPage(4, Collections.singletonList(track(2)), 2));

        model.refresh();
        assertTrue(model.getCurrentState().refreshing);
        assertEquals(2, model.getCurrentState().items.size());
        assertEquals(0, repository.last().offset);
        repository.last().error(new VkAudioError(VkAudioError.Kind.RATE_LIMITED, 6, 0));
        assertEquals(2, model.getCurrentState().items.size());
        model.retry();
        assertEquals(0, repository.last().offset);
        repository.last().success(new VkAudioPage(1, Collections.singletonList(track(7)), 1));
        assertEquals(1, model.getCurrentState().items.size());
        assertEquals(7, model.getCurrentState().items.get(0).id);
    }

    @Test
    public void initialErrorDoesNotAutomaticallyRetryAfterRecreation() {
        model.ensureLoaded();
        repository.last().error(new VkAudioError(VkAudioError.Kind.AUTH_REQUIRED, 5, 0));
        provider().get(VkAudioViewModel.class).ensureLoaded();
        assertEquals(1, repository.pending.size());
        assertFalse(model.getCurrentState().loaded);
        model.retry();
        assertEquals(2, repository.pending.size());
        assertEquals(0, repository.last().offset);
    }

    @Test
    public void emptyPageStopsPagingDespiteStaleServerTotalAndClearDiscardsLateResponses() {
        model.ensureLoaded();
        repository.last().success(new VkAudioPage(30, Collections.emptyList(), 0));
        assertTrue(model.getCurrentState().loaded);
        assertFalse(model.getCurrentState().hasMore);
        model.refresh();
        FakeRequest request = repository.last();
        VkAudioViewModel.State beforeClear = model.getCurrentState();
        store.clear();
        assertTrue(request.canceled);
        assertTrue(repository.closed);
        request.success(new VkAudioPage(1, Collections.singletonList(track(8)), 1));
        assertSame(beforeClear, model.getCurrentState());
    }

    private ViewModelProvider provider() {
        return new ViewModelProvider(store, new ViewModelProvider.Factory() {
            @Override
            public <T extends ViewModel> T create(Class<T> type) {
                return type.cast(new VkAudioViewModel(repository));
            }
        });
    }

    private static VkAudioTrack track(long id) {
        return new VkAudioTrack(id, 41, "Synthetic artist", "Synthetic title", 180, "", "", true);
    }

    @Test
    public void switchingSourcesCancelsOldPageAndIgnoresLateResult() {
        model.ensureLoaded();
        FakeRequest old = repository.last();
        model.search("remote");
        assertTrue(old.canceled);
        assertTrue(model.isSearch());
        assertEquals("remote", repository.query);
        old.success(new VkAudioPage(1, Collections.singletonList(track(1)), 1));
        assertTrue(model.getCurrentState().items.isEmpty());
        repository.last().success(new VkAudioPage(1, Collections.singletonList(track(2)), 1));
        assertEquals(2, model.getCurrentState().items.get(0).id);
        model.showPlaylist(new VkAudioPlaylist(41, 7, "playlist", ""));
        assertFalse(model.isSearch());
        assertEquals(7, repository.playlist.id);
        assertTrue(model.getCurrentState().items.isEmpty());
    }

    @Test
    public void playlistMutationsChangeRetainedStateOnlyAfterConfirmedSuccess() {
        VkAudioPlaylist selected = new VkAudioPlaylist(41, 7, "Before", "key", "Description", true, true);
        model.showPlaylist(selected);
        repository.last().success(new VkAudioPage(1, Collections.singletonList(track(1)), 1));
        List<Boolean> outcomes = new ArrayList<>();
        model.getPlaylistMutationEvent().observeForever(event -> {
            if (event != null && event.consume()) outcomes.add(event.error == null);
        });

        model.renamePlaylist(selected, "After");
        assertEquals("Before", model.getPlaylist().title);
        assertTrue(outcomes.isEmpty());
        repository.mutation.onError(new VkAudioError(VkAudioError.Kind.ACCESS_DENIED));
        assertEquals("Before", model.getPlaylist().title);
        model.renamePlaylist(selected, " After ");
        assertSame(model, provider().get(VkAudioViewModel.class));
        repository.mutation.onSuccess(true);
        assertEquals("After", model.getPlaylist().title);
        assertEquals(1, model.getCurrentState().items.size());
        assertEquals("key", model.getPlaylist().accessKey);
        assertEquals("Description", model.getPlaylist().description);
        assertEquals(Boolean.TRUE, model.getPlaylist().canEdit);
        assertEquals(Boolean.TRUE, model.getPlaylist().canDelete);

        model.deletePlaylist(model.getPlaylist());
        repository.mutation.onSuccess(false);
        assertEquals(7, model.getPlaylist().id);
        model.deletePlaylist(model.getPlaylist());
        repository.mutation.onSuccess(true);
        assertEquals(null, model.getPlaylist());
        assertTrue(model.getCurrentState().loading);
        assertEquals(Arrays.asList(false, true, false, true), outcomes);
    }

    @Test
    public void playlistRenameAcceptsAtMost255Characters() {
        VkAudioPlaylist selected = new VkAudioPlaylist(41, 7, "Before", "");
        List<Boolean> outcomes = new ArrayList<>();
        model.getPlaylistMutationEvent().observeForever(event -> {
            if (event != null && event.consume()) outcomes.add(event.error == null);
        });
        char[] title = new char[256];
        Arrays.fill(title, 'x');
        model.renamePlaylist(selected, new String(title));
        assertEquals(Collections.singletonList(false), outcomes);
        assertNull(repository.mutation);
        model.renamePlaylist(selected, new String(title, 0, 255));
        assertNotNull(repository.mutation);
        repository.mutation.onSuccess(true);
        assertEquals(Arrays.asList(false, true), outcomes);
    }

    @Test
    public void latePlaylistMutationDoesNotOverrideDifferentSourceAndDuplicateIsRejected() {
        VkAudioPlaylist first = new VkAudioPlaylist(41, 7, "First", "");
        VkAudioPlaylist other = new VkAudioPlaylist(41, 8, "Other", "");
        model.showPlaylist(first);
        List<Boolean> outcomes = new ArrayList<>();
        model.getPlaylistMutationEvent().observeForever(event -> {
            if (event != null && event.consume()) outcomes.add(event.error == null);
        });
        model.deletePlaylist(first);
        model.renamePlaylist(first, "New");
        assertEquals(Collections.singletonList(false), outcomes);
        model.showPlaylist(other);
        repository.mutation.onSuccess(true);
        assertEquals(8, model.getPlaylist().id);
        assertEquals(Arrays.asList(false, true), outcomes);
    }

    @Test
    public void mutationResultWaitsForRecreatedObserverAndIsConsumedOnlyOnce() {
        VkAudioPlaylist selected = new VkAudioPlaylist(41, 7, "Old", "");
        model.showPlaylist(selected);
        model.renamePlaylist(selected, "New");
        // Simulate completion while no screen is active.
        repository.mutation.onError(new VkAudioError(VkAudioError.Kind.NETWORK));
        VkAudioViewModel recreated = provider().get(VkAudioViewModel.class);
        List<VkAudioError.Kind> shown = new ArrayList<>();
        recreated.getPlaylistMutationEvent().observeForever(event -> {
            if (event != null && event.consume()) shown.add(event.error.kind);
        });
        assertEquals(Collections.singletonList(VkAudioError.Kind.NETWORK), shown);
        recreated.getPlaylistMutationEvent().observeForever(event -> {
            if (event != null && event.consume()) shown.add(event.error.kind);
        });
        assertEquals(Collections.singletonList(VkAudioError.Kind.NETWORK), shown);
    }

    @Test
    public void confirmedMutationUpdatesReopenedSamePlaylistWithoutLosingFreshMetadata() {
        VkAudioPlaylist original = new VkAudioPlaylist(41, 7, "Old", "key", "Old description", true, true, false);
        VkAudioPlaylist reopened = new VkAudioPlaylist(41, 7, "Old", "fresh", "Fresh description", true, true, true);
        model.showPlaylist(original);
        model.renamePlaylist(original, "New");
        model.showPlaylist(reopened);
        repository.mutation.onSuccess(true);
        assertEquals("New", model.getPlaylist().title);
        assertEquals("Fresh description", model.getPlaylist().description);
        assertEquals("fresh", model.getPlaylist().accessKey);
        assertEquals(Boolean.TRUE, model.getPlaylist().noDiscover);
        VkAudioPlaylist current = model.getPlaylist();
        model.deletePlaylist(current);
        model.showPlaylist(new VkAudioPlaylist(41, 7, "New", ""));
        repository.mutation.onSuccess(true);
        assertNull(model.getPlaylist());
    }

    private static final class FakeRepository implements VkAudioRepository {
        final List<FakeRequest> pending = new ArrayList<>();
        boolean closed;
        String query;
        VkAudioPlaylist playlist;
        Callback<Boolean> mutation;

        @Override public Request renamePlaylist(VkAudioPlaylist playlist, String title, Callback<Boolean> callback) {
            mutation = callback;
            return new FakeRequest(0, null);
        }
        @Override public Request deletePlaylist(VkAudioPlaylist playlist, Callback<Boolean> callback) {
            mutation = callback;
            return new FakeRequest(0, null);
        }

        @Override public Request search(String query, int offset, int count, Callback<VkAudioPage> callback) {
            this.query = query;
            return getMyAudio(offset, count, callback);
        }
        @Override public Request getPlaylistAudio(VkAudioPlaylist playlist, int offset, int count, Callback<VkAudioPage> callback) {
            this.playlist = playlist;
            return getMyAudio(offset, count, callback);
        }

        @Override
        public Request getMyAudio(int offset, int count, Callback<VkAudioPage> callback) {
            FakeRequest request = new FakeRequest(offset, callback);
            pending.add(request);
            return request;
        }

        @Override
        public Request getById(List<String> audios, Callback<List<VkAudioTrack>> callback) {
            throw new AssertionError("Catalog state must never resolve playback URLs");
        }

        @Override
        public void close() {
            closed = true;
        }

        FakeRequest last() {
            return pending.get(pending.size() - 1);
        }
    }

    private static final class FakeRequest implements VkAudioRepository.Request {
        final int offset;
        final VkAudioRepository.Callback<VkAudioPage> callback;
        boolean canceled;

        FakeRequest(int offset, VkAudioRepository.Callback<VkAudioPage> callback) {
            this.offset = offset;
            this.callback = callback;
        }

        void success(VkAudioPage page) { callback.onSuccess(page); }
        void error(VkAudioError error) { callback.onError(error); }
        @Override public void cancel() { canceled = true; }
        @Override public boolean isCanceled() { return canceled; }
    }
}
