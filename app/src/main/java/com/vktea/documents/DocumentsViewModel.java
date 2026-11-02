package com.vktea.documents;

import android.content.Context;
import android.os.Handler;
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

/** Retains one query across rotation; old requests cannot replace newer results. */
public final class DocumentsViewModel extends ViewModel {
    public static final class State {
        public final List<DocumentItem> items;
        public final String query;
        public final boolean loading, loaded, hasMore;
        public final DocumentsRepository.Error error;
        State(DocumentsViewModel model) {
            items = model.items; query = model.query; loading = model.loading; loaded = model.loaded;
            hasMore = model.hasMore; error = model.error;
        }
    }
    public static final class Factory implements ViewModelProvider.Factory {
        private final Context context;
        public Factory(Context context) { this.context = context.getApplicationContext(); }
        @NonNull @Override public <T extends ViewModel> T create(@NonNull Class<T> type) {
            if (!type.isAssignableFrom(DocumentsViewModel.class)) throw new IllegalArgumentException("Unsupported ViewModel");
            return type.cast(new DocumentsViewModel(DocumentsRepository.create(context)));
        }
    }
    private final DocumentsRepository repository;
    private final MutableLiveData<State> state = new MutableLiveData<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean searchScheduled;
    private final Runnable search = () -> { searchScheduled = false; loadNext(); };
    private List<DocumentItem> items = Collections.emptyList();
    private DocumentsRepository.Request request;
    private DocumentsRepository.Error error;
    private String query = "";
    private int offset;
    private boolean loading, loaded, hasMore = true, cleared;
    private long generation;

    public DocumentsViewModel(DocumentsRepository repository) { this.repository = repository; publish(); }
    public LiveData<State> state() { return state; }
    public void ensureLoaded() { if (!loaded && !loading && error == null && !searchScheduled) loadNext(); }
    public void setQuery(String value) {
        String next = value == null ? "" : value.trim();
        if (next.length() > 200) next = next.substring(0, 200);
        if (cleared || next.equals(query)) return;
        query = next; reset(); publish(); searchScheduled = true; main.postDelayed(search, 350);
    }
    public void refresh() { if (cleared) return; reset(); publish(); loadNext(); }
    public void retry() { if (!cleared && error != null && !loading) { error = null; loadNext(); } }
    public void loadNext() {
        if (cleared || loading || !hasMore || error != null) return;
        main.removeCallbacks(search); searchScheduled = false;
        long current = ++generation;
        loading = true; error = null; publish();
        DocumentsRepository.Request started = repository.load(query, offset, new DocumentsRepository.Callback() {
            @Override public void success(DocumentsRepository.Page page) {
                if (!valid(current)) return;
                request = null; loading = false; loaded = true;
                LinkedHashMap<String, DocumentItem> unique = new LinkedHashMap<>();
                for (DocumentItem item : items) unique.put(item.ownerId + "_" + item.id, item);
                for (DocumentItem item : page.items) if (item != null) unique.putIfAbsent(item.ownerId + "_" + item.id, item);
                items = Collections.unmodifiableList(new ArrayList<>(unique.values()));
                offset += page.consumed;
                hasMore = page.consumed > 0 && offset < page.total;
                publish();
            }
            @Override public void error(DocumentsRepository.Error failure) {
                if (!valid(current)) return;
                request = null; loading = false; error = failure;
                if (failure.kind == DocumentsRepository.Error.Kind.AUTH) { items = Collections.emptyList(); loaded = false; offset = 0; }
                publish();
            }
        });
        if (valid(current)) request = started;
    }
    private boolean valid(long expected) { return !cleared && loading && generation == expected; }
    private void reset() {
        main.removeCallbacks(search); searchScheduled = false; generation++; if (request != null) request.cancel(); request = null;
        items = Collections.emptyList(); offset = 0; loading = false; loaded = false; hasMore = true; error = null;
    }
    private void publish() { if (!cleared) state.setValue(new State(this)); }
    @Override protected void onCleared() { reset(); cleared = true; repository.close(); super.onCleared(); }
}
