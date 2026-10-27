package com.vktea.videos;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.vktea.auth.Session;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

public final class VideosViewModel extends ViewModel {
    static final int VIDEOS = 0, ALBUMS = 1, SEARCH = 2;
    static final class State {
        final List<VideoEntry> rows;
        final int mode, offset, total;
        final long album;
        final String albumTitle, query, error;
        final boolean loading, loaded;
        State(VideosViewModel m) {
            rows = Collections.unmodifiableList(new ArrayList<>(m.rows)); mode = m.mode; offset = m.offset; total = m.total;
            album = m.album; albumTitle = m.albumTitle; query = m.query; error = m.error; loading = m.loading; loaded = m.loaded;
        }
        boolean hasMore() { return loaded && offset < total; }
    }
    static final class Factory implements ViewModelProvider.Factory {
        private final Context context; private final long owner;
        Factory(Context context, long owner) { this.context = context.getApplicationContext(); this.owner = owner; }
        @NonNull @Override public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            if (!modelClass.isAssignableFrom(VideosViewModel.class)) throw new IllegalArgumentException();
            long resolved = owner;
            if (resolved == 0) {
                try { resolved = Long.parseLong(Session.getUserId(context)); } catch (Exception ignored) { resolved = 0; }
            }
            return modelClass.cast(new VideosViewModel(context, resolved));
        }
    }
    interface Callback { void complete(VideoEntry.Page page, String error); }
    interface Source {
        void load(long owner, long album, boolean albums, String query, int offset, Callback callback);
        void cancel();
    }
    private final Source repository;
    private final long owner;
    private final MutableLiveData<State> state = new MutableLiveData<>();
    private final List<VideoEntry> rows = new ArrayList<>();
    private int mode = VIDEOS, offset, total;
    private long album, generation;
    private String albumTitle = "", query = "", error;
    private boolean loading, loaded, cleared;
    VideosViewModel(Context context, long owner) { this(new VideoRepository(context), owner); }
    VideosViewModel(Source source, long owner) { repository = source; this.owner = owner; publish(); }
    LiveData<State> state() { return state; }
    State current() { return state.getValue(); }
    void ensureLoaded() { if (!loaded && !loading && error == null && (mode != SEARCH || !query.isEmpty())) load(); }
    void setMode(int next) { if (mode == next && album == 0) return; mode = next; album = 0; albumTitle = ""; reset(); ensureLoaded(); }
    void openAlbum(VideoEntry entry) { if (!entry.album) return; mode = ALBUMS; album = entry.id; albumTitle = entry.title; reset(); ensureLoaded(); }
    void backToAlbums() { if (album == 0) return; album = 0; albumTitle = ""; reset(); ensureLoaded(); }
    void search(String text) {
        String next = text == null ? "" : text.trim();
        if (next.length() > 256) next = next.substring(0, 256);
        mode = SEARCH; album = 0; albumTitle = ""; query = next; reset(); ensureLoaded();
    }
    void refresh() { reset(); ensureLoaded(); }
    void loadMore() { if (!loading && (error != null || !loaded || offset < total)) load(); }
    private void reset() { generation++; repository.cancel(); loading = false; loaded = false; rows.clear(); offset = total = 0; error = null; publish(); }
    private void load() {
        if (cleared || loading || mode == SEARCH && query.isEmpty()) return;
        if (owner == 0 && mode != SEARCH) { error = "auth"; publish(); return; }
        final long request = ++generation;
        final boolean albumList = mode == ALBUMS && album == 0;
        loading = true; error = null; publish();
        repository.load(owner, album, albumList, mode == SEARCH ? query : "", offset, (page, failure) -> {
            if (cleared || generation != request) return;
            loading = false;
            if (failure != null || page == null) { error = failure == null ? "generic" : failure; if ("auth".equals(error)) { rows.clear(); offset = total = 0; loaded = false; } publish(); return; }
            if (page.consumed == 0 && offset < page.total) { error = "generic"; publish(); return; }
            LinkedHashMap<String, VideoEntry> unique = new LinkedHashMap<>();
            for (VideoEntry row : rows) unique.put(row.ownerId + "_" + row.id, row);
            for (VideoEntry row : page.entries) unique.putIfAbsent(row.ownerId + "_" + row.id, row);
            rows.clear(); rows.addAll(unique.values()); offset += page.consumed; total = page.total; loaded = true; publish();
        });
    }
    private void publish() { if (!cleared) state.setValue(new State(this)); }
    @Override protected void onCleared() { cleared = true; generation++; repository.cancel(); super.onCleared(); }
}
