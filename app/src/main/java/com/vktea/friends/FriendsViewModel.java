package com.vktea.friends;

import android.content.Context;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Retains the complete list and local filters; only explicit loading methods reach the repository. */
public final class FriendsViewModel extends ViewModel {
    public static final class State {
        public final List<Friend> items, visibleItems;
        public final String query;
        public final boolean onlineOnly, loading, refreshing, loaded;
        public final FriendsError error;
        public final int totalCount, onlineCount;
        private State(FriendsViewModel model) {
            items = model.items; query = model.query; onlineOnly = model.onlineOnly;
            loading = model.loading; refreshing = model.loading && model.loaded; loaded = model.loaded; error = model.error;
            totalCount = items.size();
            int count = 0;
            List<Friend> visible = new ArrayList<>();
            String needle = normalized(query).trim();
            for (Friend row : items) {
                if (row.online) count++;
                if ((!onlineOnly || row.online) && normalized(row.name).contains(needle)) visible.add(row);
            }
            onlineCount = count;
            if (!"hints".equals(model.order)) {
                boolean byLastName = "lastname".equals(model.order);
                visible.sort(Comparator.comparing((Friend row) -> normalized(byLastName ? row.lastName : row.firstName))
                        .thenComparing(row -> normalized(byLastName ? row.firstName : row.lastName))
                        .thenComparingLong(row -> row.id));
            }
            visibleItems = Collections.unmodifiableList(visible);
        }
    }
    public static final class Factory implements ViewModelProvider.Factory {
        private final Context application;
        public Factory(Context context) { application = context.getApplicationContext(); }
        @NonNull @Override public <T extends ViewModel> T create(@NonNull Class<T> type) {
            if (!type.isAssignableFrom(FriendsViewModel.class)) throw new IllegalArgumentException("Unsupported ViewModel");
            return type.cast(new FriendsViewModel(FriendsRepository.create(application)));
        }
    }
    private final FriendsRepository repository;
    private final MutableLiveData<State> state = new MutableLiveData<>();
    private List<Friend> items = Collections.emptyList();
    private String query = "";
    private String order = "firstname";
    private boolean onlineOnly, loading, loaded, cleared;
    private FriendsError error;
    private long generation;
    private FriendsRepository.Request request;

    public FriendsViewModel(FriendsRepository repository) { this.repository = repository; publish(); }
    public LiveData<State> state() { return state; }
    public State getCurrentState() { return state.getValue(); }
    public void ensureLoaded() { requireMain(); if (!loaded && !loading && error == null) load(); }
    public void refresh() { requireMain(); if (!loading) load(); }
    public void retry() { requireMain(); if (!loading && error != null) load(); }
    public void setQuery(String value) {
        requireMain(); if (cleared) return;
        String next = value == null ? "" : value;
        if (!next.equals(query)) { query = next; publish(); }
    }
    public void setOnlineOnly(boolean value) { requireMain(); if (!cleared && onlineOnly != value) { onlineOnly = value; publish(); } }
    public void setOrder(String value) {
        requireMain();
        String next = "lastname".equals(value) || "hints".equals(value) ? value : "firstname";
        if (!cleared && !next.equals(order)) { order = next; publish(); }
    }
    private void load() {
        if (cleared) return;
        long requested = ++generation;
        loading = true; error = null; publish();
        FriendsRepository.Request started = repository.load(new FriendsRepository.Callback<List<Friend>>() {
            @Override public void success(List<Friend> result) {
                if (!current(requested)) return;
                request = null; loading = false; loaded = true;
                LinkedHashMap<Long, Friend> unique = new LinkedHashMap<>();
                for (Friend row : result) if (row != null && row.id > 0) unique.putIfAbsent(row.id, row);
                items = Collections.unmodifiableList(new ArrayList<>(unique.values())); publish();
            }
            @Override public void error(FriendsError failure) {
                if (!current(requested)) return;
                request = null; loading = false; error = failure;
                if (failure.kind == FriendsError.Kind.AUTH) { items = Collections.emptyList(); loaded = false; }
                publish();
            }
        });
        if (current(requested)) request = started;
    }
    private boolean current(long requested) { return !cleared && loading && generation == requested; }
    private void publish() { if (!cleared) state.setValue(new State(this)); }
    private static String normalized(String value) { return value.toLowerCase(Locale.ROOT).replace('ё', 'е'); }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Friends state must be changed on main");
    }
    @Override protected void onCleared() {
        cleared = true; generation++;
        if (request != null) request.cancel();
        repository.close(); super.onCleared();
    }
}
