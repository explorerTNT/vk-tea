package com.vktea.notifications;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
            return type.cast(new NotificationsViewModel(NotificationsRepository.create(context)));
        }
    }
    private final NotificationsRepository repository;
    private final MutableLiveData<State> state = new MutableLiveData<>();
    private List<NotificationEvent> items = Collections.emptyList();
    private String nextFrom = "";
    private boolean loaded, loading, pageRequest, failedPage, cleared;
    private NotificationsError error;
    private long generation;
    private NotificationsRepository.Request request;

    public NotificationsViewModel(NotificationsRepository repository) { this.repository = repository; publish(); }
    public LiveData<State> state() { return state; }
    public State getCurrentState() { return state.getValue(); }
    public void ensureLoaded() { if (!cleared && !loaded && !loading && error == null) load(false); }
    public void refresh() { if (!cleared && !loading) load(false); }
    public void loadMore() { if (!cleared && loaded && !loading && error == null && !nextFrom.isEmpty()) load(true); }
    public void retry() { if (!cleared && !loading && error != null) load(failedPage); }

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
    private boolean isCurrent(long value) { return !cleared && loading && generation == value; }
    private void publish() { if (!cleared) state.setValue(new State(this)); }
    @Override protected void onCleared() {
        cleared = true; generation++;
        if (request != null) request.cancel();
        repository.close(); super.onCleared();
    }
}
