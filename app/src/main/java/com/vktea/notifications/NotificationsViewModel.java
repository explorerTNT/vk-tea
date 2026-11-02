package com.vktea.notifications;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.vktea.auth.Session;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Keeps the visible snapshot when refresh or a subsequent page fails. */
public final class NotificationsViewModel extends ViewModel {
    public static final class State {
        public final List<NotificationEvent> items;
        public final boolean loaded, loading, refreshing, loadingMore;
        public final String nextFrom;
        public final NotificationsError error;
        private State(NotificationsViewModel model) {
            items = model.items; loaded = model.loaded; loading = model.loading;
            loadingMore = model.loading && model.pageRequest;
            refreshing = model.loading && model.loaded && !model.pageRequest;
            nextFrom = model.nextFrom; error = model.error;
        }
    }
    public static final class Factory implements ViewModelProvider.Factory {
        private final Context context;
        public Factory(Context context) { this.context = context.getApplicationContext(); }
        @NonNull @Override public <T extends ViewModel> T create(@NonNull Class<T> type) {
            if (!type.isAssignableFrom(NotificationsViewModel.class)) throw new IllegalArgumentException("Unsupported ViewModel");
            return type.cast(new NotificationsViewModel(NotificationsRepository.create(context), Session::generation,
                    () -> { String token = Session.snapshot(context).token; return token != null && !token.isEmpty(); }));
        }
    }
    private final NotificationsRepository repository;
    private final MutableLiveData<State> state = new MutableLiveData<>();
    private List<NotificationEvent> items = Collections.emptyList();
    private String nextFrom = "";
    private boolean loaded, loading, pageRequest, failedPage, cleared;
    private NotificationsError error;
    private long generation;
    private final LongSupplier sessionGeneration;
    private final BooleanSupplier canLoad;
    private long currentSessionGeneration;
    private NotificationsRepository.Request request;
    private final Runnable sessionChangeListener = this::onSessionChanged;
    private void onSessionChanged() {
        synchronizeSession();
        if (!cleared && state.hasActiveObservers() && canLoad.getAsBoolean()) ensureLoaded();
    }

    public NotificationsViewModel(NotificationsRepository repository) { this(repository, Session::generation, () -> true); }
    NotificationsViewModel(NotificationsRepository repository, LongSupplier sessionGeneration, BooleanSupplier canLoad) {
        this.repository = repository;
        this.sessionGeneration = sessionGeneration;
        this.canLoad = canLoad;
        this.currentSessionGeneration = sessionGeneration.getAsLong();
        Session.addChangeListener(sessionChangeListener);
        publish();
    }
    public LiveData<State> state() { return state; }
    public State getCurrentState() { return state.getValue(); }
    public void ensureLoaded() { synchronizeSession(); if (!cleared && canLoad.getAsBoolean() && !loaded && !loading && error == null) load(false); }
    public void refresh() { synchronizeSession(); if (!cleared && canLoad.getAsBoolean() && !loading) load(false); }
    public void loadMore() { synchronizeSession(); if (!cleared && canLoad.getAsBoolean() && loaded && !loading && error == null && !nextFrom.isEmpty()) load(true); }
    public void retry() { synchronizeSession(); if (!cleared && canLoad.getAsBoolean() && !loading && error != null) load(failedPage); }

    /** Clear the previous account before displaying or paging its retained snapshot. */
    public boolean synchronizeSession() {
        if (cleared) return false;
        long current = sessionGeneration.getAsLong();
        if (current == currentSessionGeneration) return false;
        currentSessionGeneration = current;
        generation++;
        NotificationsRepository.Request previous = request;
        request = null;
        if (previous != null) previous.cancel();
        items = Collections.emptyList();
        nextFrom = "";
        loaded = loading = pageRequest = failedPage = false;
        error = null;
        publish();
        return true;
    }

    private void load(boolean next) {
        final String cursor = next ? nextFrom : "";
        final long current = ++generation;
        pageRequest = next; loading = true; error = null; publish();
        NotificationsRepository.Request started = repository.load(cursor, new NotificationsRepository.Callback<EventPage>() {
            @Override public void success(EventPage page) {
                if (!isCurrent(current)) return;
                request = null; loading = false; loaded = true;
                List<NotificationEvent> result = new ArrayList<>();
                if (next) result.addAll(items);
                if (page.items != null) for (NotificationEvent item : page.items) if (item != null) result.add(item);
                items = Collections.unmodifiableList(result);
                String newCursor = page.nextFrom == null ? "" : page.nextFrom;
                nextFrom = next && cursor.equals(newCursor) ? "" : newCursor;
                publish();
            }
            @Override public void error(NotificationsError failure) {
                if (!isCurrent(current)) return;
                request = null; loading = false; error = failure; failedPage = next;
                if (failure.kind == NotificationsError.Kind.AUTH) {
                    items = Collections.emptyList(); loaded = false; nextFrom = ""; failedPage = false;
                }
                publish();
            }
        });
        if (isCurrent(current)) request = started;
    }
    private boolean isCurrent(long value) {
        if (synchronizeSession()) return false;
        return !cleared && loading && generation == value;
    }
    private void publish() { if (!cleared) state.setValue(new State(this)); }
    @Override protected void onCleared() {
        cleared = true; generation++;
        Session.removeChangeListener(sessionChangeListener);
        if (request != null) request.cancel();
        repository.close(); super.onCleared();
    }
}
