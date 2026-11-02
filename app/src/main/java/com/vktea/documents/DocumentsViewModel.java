package com.vktea.documents;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
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
            return type.cast(new DocumentsViewModel(DocumentsRepository.create(context), context));
        }
    }
    private final DocumentsRepository repository;
    private final Context sessionContext;
    private final MutableLiveData<State> state = new MutableLiveData<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean searchScheduled;
    private final Runnable search = () -> { searchScheduled = false; loadNext(); };
    private final Runnable sessionChanged = () -> { if (synchronizeSession()) loadNext(); };
    private List<DocumentItem> items = Collections.emptyList();
    private DocumentsRepository.Request request;
    private DocumentsRepository.Error error;
    private String query = "";
    private int offset;
    private boolean loading, loaded, hasMore = true, cleared;
    private long generation;
    private long accountGeneration;
    private String accountUserId;

    public DocumentsViewModel(DocumentsRepository repository) { this(repository, null); }
    DocumentsViewModel(DocumentsRepository repository, Context context) {
        this.repository = repository;
        this.sessionContext = context == null ? null : context.getApplicationContext();
        if (sessionContext != null) {
            Session.Snapshot snapshot = Session.snapshot(sessionContext);
            accountGeneration = snapshot.generation;
            accountUserId = snapshot.userId;
            Session.addChangeListener(sessionChanged);
        }
        publish();
    }
    public LiveData<State> state() { return state; }
    public void ensureLoaded() {
        synchronizeSession();
        if (cleared || !hasActiveSession()) return;
        if (!loaded && !loading && error == null && !searchScheduled) loadNext();
    }
    public void setQuery(String value) {
        boolean changed = synchronizeSession();
        String next = value == null ? "" : value.trim();
        if (next.length() > 200) next = next.substring(0, 200);
        if (cleared || !hasActiveSession()) return;
        if (next.equals(query)) { if (changed) loadNext(); return; }
        query = next; reset(); publish(); searchScheduled = true; main.postDelayed(search, 350);
    }
    public void refresh() { synchronizeSession(); if (cleared || !hasActiveSession()) return; reset(); publish(); loadNext(); }
    public void retry() {
        boolean changed = synchronizeSession();
        if (cleared || !hasActiveSession()) return;
        if (changed) { loadNext(); return; }
        if (!cleared && error != null && !loading) { error = null; loadNext(); }
    }
    public void loadNext() {
        synchronizeSession();
        if (cleared || !hasActiveSession() || loading || !hasMore || error != null) return;
        main.removeCallbacks(search); searchScheduled = false;
        long current = ++generation;
        loading = true; error = null; publish();
        DocumentsRepository.Request started = repository.load(query, offset, new DocumentsRepository.Callback() {
            @Override public void success(DocumentsRepository.Page page) {
                if (synchronizeSession()) { loadNext(); return; }
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
                if (synchronizeSession()) { loadNext(); return; }
                if (!valid(current)) return;
                request = null; loading = false; error = failure;
                if (failure.kind == DocumentsRepository.Error.Kind.AUTH) { items = Collections.emptyList(); loaded = false; offset = 0; }
                publish();
            }
        });
        if (valid(current)) request = started;
    }
    private boolean valid(long expected) { return !cleared && loading && generation == expected; }
    /** The context-free constructor is used only by isolated repository tests. */
    private boolean hasActiveSession() {
        if (sessionContext == null) return true;
        String token = Session.snapshot(sessionContext).token;
        return token != null && !token.isEmpty();
    }
    /** Never retain old-account rows, offsets or search terms after a session transition. */
    private boolean synchronizeSession() {
        if (sessionContext == null || cleared) return false;
        Session.Snapshot current = Session.snapshot(sessionContext);
        if (accountGeneration == current.generation) return false;
        boolean sameAccount = accountUserId != null && !accountUserId.isEmpty()
                && accountUserId.equals(current.userId) && current.token != null && !current.token.isEmpty();
        accountGeneration = current.generation;
        accountUserId = current.userId;
        if (!sameAccount) query = "";
        reset(); publish();
        return true;
    }
    private void reset() {
        main.removeCallbacks(search); searchScheduled = false; generation++; if (request != null) request.cancel(); request = null;
        items = Collections.emptyList(); offset = 0; loading = false; loaded = false; hasMore = true; error = null;
    }
    private void publish() { if (!cleared) state.setValue(new State(this)); }
    @Override protected void onCleared() {
        if (sessionContext != null) Session.removeChangeListener(sessionChanged);
        reset(); cleared = true; repository.close(); super.onCleared();
    }
}
