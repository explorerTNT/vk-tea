package com.vktea.audio.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
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

    private static final class FakeRepository implements VkAudioRepository {
        final List<FakeRequest> pending = new ArrayList<>();
        boolean closed;
        String query;
        VkAudioPlaylist playlist;

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
