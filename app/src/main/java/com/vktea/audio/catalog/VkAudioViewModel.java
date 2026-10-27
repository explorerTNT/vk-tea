package com.vktea.audio.catalog;

import android.content.Context;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/** Retained catalog state only. No player, queue or autoplay side effects. */
public final class VkAudioViewModel extends ViewModel {
    public static final int PAGE_SIZE = 200;

    public static final class State {
        public final List<VkAudioTrack> items;
        public final int totalCount;
        public final int nextOffset;
        public final boolean loading;
        public final boolean refreshing;
        public final boolean hasMore;
        public final boolean loaded;
        public final VkAudioError error;

        private State(List<VkAudioTrack> items, int totalCount, int nextOffset, boolean loading,
                      boolean refreshing, boolean hasMore, boolean loaded, VkAudioError error) {
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.totalCount = totalCount;
            this.nextOffset = nextOffset;
            this.loading = loading;
            this.refreshing = refreshing;
            this.hasMore = hasMore;
            this.loaded = loaded;
            this.error = error;
        }
    }

    /** One-time result retained until an active screen handles it. */
    public static final class PlaylistMutationEvent {
        public final VkAudioError error;
        private boolean consumed;

        private PlaylistMutationEvent(VkAudioError error) { this.error = error; }
        public boolean consume() {
            if (consumed) return false;
            consumed = true;
            return true;
        }
    }

    public static final class Factory implements ViewModelProvider.Factory {
        private final Context application;

        public Factory(Context context) {
            application = context.getApplicationContext();
        }

        @NonNull
        @Override
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            if (!modelClass.isAssignableFrom(VkAudioViewModel.class)) {
                throw new IllegalArgumentException("Unsupported ViewModel");
            }
            return modelClass.cast(new VkAudioViewModel(VkAudioRepository.create(application)));
        }
    }

    private final VkAudioRepository repository;
    private final MutableLiveData<State> state = new MutableLiveData<>(
            new State(Collections.emptyList(), 0, 0, false, false, true, false, null));
    private final MutableLiveData<PlaylistMutationEvent> playlistMutationEvent = new MutableLiveData<>();
    private VkAudioRepository.Request request;
    private boolean cleared;
    private long generation;
    private boolean failedRefresh;
    private String query = "";
    private VkAudioPlaylist playlist;
    private boolean playlistMutationPending;

    public void showLibrary() { switchSource("", null); }
    public void search(String value) { switchSource(value == null ? "" : value.trim(), null); }
    public void showPlaylist(VkAudioPlaylist value) { if (value != null) switchSource("", value); }
    public boolean isSearch() { return !query.isEmpty(); }
    public VkAudioPlaylist getPlaylist() { return playlist; }
    public String getQuery() { return query; }

    private void switchSource(String value, VkAudioPlaylist selected) {
        requireMain();
        if (cleared) return;
        generation++;
        if (request != null) request.cancel();
        request = null;
        query = value;
        playlist = selected;
        state.setValue(new State(Collections.emptyList(), 0, 0, false, false, true, false, null));
        load(true);
    }

    public void getPlaylists(VkAudioRepository.Callback<List<VkAudioPlaylist>> callback) {
        repository.getPlaylists(0, 1000, callback);
    }
    public void createPlaylist(String title, VkAudioRepository.Callback<Boolean> callback) {
        repository.createPlaylist(title, callback);
    }
    public boolean isPlaylistMutationPending() { return playlistMutationPending; }
    public LiveData<PlaylistMutationEvent> getPlaylistMutationEvent() { return playlistMutationEvent; }

    public void renamePlaylist(VkAudioPlaylist selected, String title) {
        requireMain();
        if (selected == null || title == null || title.trim().isEmpty() || title.length() > 255) {
            publishPlaylistMutation(new VkAudioError(VkAudioError.Kind.INVALID_REQUEST));
            return;
        }
        if (!beginPlaylistMutation()) return;
        repository.renamePlaylist(selected, title, playlistMutationCallback(selected, title, false));
    }
    public void deletePlaylist(VkAudioPlaylist selected) {
        requireMain();
        if (selected == null) {
            publishPlaylistMutation(new VkAudioError(VkAudioError.Kind.INVALID_REQUEST));
            return;
        }
        if (!beginPlaylistMutation()) return;
        repository.deletePlaylist(selected, playlistMutationCallback(selected, null, true));
    }

    private boolean beginPlaylistMutation() {
        requireMain();
        if (cleared) return false;
        if (playlistMutationPending) {
            publishPlaylistMutation(new VkAudioError(VkAudioError.Kind.INVALID_REQUEST));
            return false;
        }
        playlistMutationPending = true;
        return true;
    }

    private void publishPlaylistMutation(VkAudioError error) {
        if (!cleared) playlistMutationEvent.setValue(new PlaylistMutationEvent(error));
    }

    private VkAudioRepository.Callback<Boolean> playlistMutationCallback(VkAudioPlaylist selected, String title,
            boolean delete) {
        return new VkAudioRepository.Callback<Boolean>() {
            @Override public void onSuccess(Boolean result) {
                if (cleared) return;
                playlistMutationPending = false;
                if (!Boolean.TRUE.equals(result)) {
                    publishPlaylistMutation(new VkAudioError(VkAudioError.Kind.MALFORMED_RESPONSE));
                    return;
                }
                if (playlist != null && playlist.ownerId == selected.ownerId && playlist.id == selected.id) {
                    if (delete) showLibrary();
                    else {
                        VkAudioPlaylist current = playlist;
                        playlist = new VkAudioPlaylist(current.ownerId, current.id, title.trim(), current.accessKey,
                                current.description, current.canEdit, current.canDelete, current.noDiscover);
                        state.setValue(getCurrentState());
                    }
                }
                publishPlaylistMutation(null);
            }
            @Override public void onError(VkAudioError error) {
                if (cleared) return;
                playlistMutationPending = false;
                publishPlaylistMutation(error);
            }
        };
    }

    public void changeLibrary(VkAudioTrack track, boolean add, VkAudioRepository.Callback<Boolean> callback) {
        repository.changeLibrary(track, add, callback);
    }
    public void changePlaylist(VkAudioPlaylist selected, VkAudioTrack track, boolean add, VkAudioRepository.Callback<Boolean> callback) {
        repository.changePlaylist(selected, track, add, callback);
    }

    public VkAudioViewModel(VkAudioRepository repository) {
        this.repository = repository;
    }

    public LiveData<State> getState() {
        return state;
    }

    public State getCurrentState() {
        return state.getValue();
    }

    public void ensureLoaded() {
        requireMain();
        State current = getCurrentState();
        // An error waits for an explicit retry, including across rotation.
        if (!current.loaded && !current.loading && current.error == null) load(true);
    }

    public void refresh() {
        requireMain();
        if (!cleared && !getCurrentState().loading) load(true);
    }

    public void loadNext() {
        requireMain();
        State current = getCurrentState();
        if (!cleared && current.loaded && !current.loading && current.hasMore && current.error == null) load(false);
    }

    public void retry() {
        requireMain();
        State current = getCurrentState();
        if (!cleared && !current.loading && current.error != null) load(failedRefresh);
    }

    private void load(boolean refresh) {
        if (cleared) return;
        State previous = getCurrentState();
        int offset = refresh ? 0 : previous.nextOffset;
        long requestedGeneration = ++generation;
        failedRefresh = refresh;
        state.setValue(new State(previous.items, previous.totalCount, previous.nextOffset,
                true, refresh && previous.loaded, previous.hasMore, previous.loaded, null));
        VkAudioRepository.Callback<VkAudioPage> callback = new VkAudioRepository.Callback<VkAudioPage>() {
            @Override
            public void onSuccess(VkAudioPage page) {
                if (cleared || requestedGeneration != generation) return;
                request = null;
                LinkedHashMap<String, VkAudioTrack> unique = new LinkedHashMap<>();
                if (!refresh) for (VkAudioTrack row : previous.items) unique.put(row.mediaId(), row);
                for (VkAudioTrack row : page.items) unique.put(row.mediaId(), row);
                int nextOffset = (int) Math.min(Integer.MAX_VALUE, (long) offset + page.consumedCount);
                state.setValue(new State(new ArrayList<>(unique.values()), page.totalCount, nextOffset,
                        false, false, page.consumedCount > 0 && nextOffset < page.totalCount, true, null));
            }

            @Override
            public void onError(VkAudioError error) {
                if (cleared || requestedGeneration != generation) return;
                request = null;
                state.setValue(new State(previous.items, previous.totalCount, previous.nextOffset,
                        false, false, previous.hasMore, previous.loaded, error));
            }
        };
        VkAudioRepository.Request started = !query.isEmpty() ? repository.search(query, offset, PAGE_SIZE, callback)
                : playlist != null ? repository.getPlaylistAudio(playlist, offset, PAGE_SIZE, callback)
                : repository.getMyAudio(offset, PAGE_SIZE, callback);
        if (!cleared && requestedGeneration == generation && getCurrentState().loading) request = started;
    }

    @Override
    protected void onCleared() {
        cleared = true;
        generation++;
        if (request != null) request.cancel();
        repository.close();
        super.onCleared();
    }

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Audio catalog state must be changed on the main thread");
        }
    }
}
