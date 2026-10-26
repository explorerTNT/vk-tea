package com.vktea.groups;

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
import java.util.Locale;

/** Retains a complete snapshot in API order and filters it without further requests. */
public final class GroupsViewModel extends ViewModel {
    public static final class State {
        public final List<Group> items, visibleItems;
        public final String query;
        public final boolean managementOnly, loading, refreshing, loaded;
        public final GroupsError error;
        public final int totalCount, managementCount;
        private State(GroupsViewModel model) {
            items = model.items; query = model.query; managementOnly = model.managementOnly;
            loading = model.loading; refreshing = model.loading && model.loaded; loaded = model.loaded; error = model.error;
            totalCount = items.size();
            int count = 0;
            List<Group> visible = new ArrayList<>();
            String needle = normalized(query).trim();
            for (Group row : items) {
                if (row.isManaged()) count++;
                if ((!managementOnly || row.isManaged())
                        && (normalized(row.name).contains(needle) || normalized(row.screenName).contains(needle))) visible.add(row);
            }
            managementCount = count;
            visibleItems = Collections.unmodifiableList(visible);
        }
    }
    public static final class Factory implements ViewModelProvider.Factory {
        private final Context application;
        public Factory(Context context) { application = context.getApplicationContext(); }
        @NonNull @Override public <T extends ViewModel> T create(@NonNull Class<T> type) {
            if (!type.isAssignableFrom(GroupsViewModel.class)) throw new IllegalArgumentException("Unsupported ViewModel");
            return type.cast(new GroupsViewModel(GroupsRepository.create(application)));
        }
    }
    private final GroupsRepository repository;
    private final MutableLiveData<State> state = new MutableLiveData<>();
    private List<Group> items = Collections.emptyList();
    private String query = "";
    private boolean managementOnly, loading, loaded, cleared;
    private GroupsError error;
    private long generation;
    private GroupsRepository.Request request;

    public GroupsViewModel(GroupsRepository repository) { this.repository = repository; publish(); }
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
    public void setManagementOnly(boolean value) {
        requireMain(); if (!cleared && managementOnly != value) { managementOnly = value; publish(); }
    }
    private void load() {
        if (cleared) return;
        long requested = ++generation;
        loading = true; error = null; publish();
        GroupsRepository.Request started = repository.load(new GroupsRepository.Callback<List<Group>>() {
            @Override public void success(List<Group> result) {
                if (!current(requested)) return;
                request = null; loading = false; loaded = true;
                LinkedHashMap<Long, Group> unique = new LinkedHashMap<>();
                for (Group row : result) if (row != null && row.id > 0) unique.putIfAbsent(row.id, row);
                items = Collections.unmodifiableList(new ArrayList<>(unique.values())); publish();
            }
            @Override public void error(GroupsError failure) {
                if (!current(requested)) return;
                request = null; loading = false; error = failure;
                if (failure.kind == GroupsError.Kind.AUTH) { items = Collections.emptyList(); loaded = false; }
                publish();
            }
        });
        if (current(requested)) request = started;
    }
    private boolean current(long requested) { return !cleared && loading && generation == requested; }
    private void publish() { if (!cleared) state.setValue(new State(this)); }
    private static String normalized(String value) { return value.toLowerCase(Locale.ROOT).replace('ё', 'е'); }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Groups state must be changed on main");
    }
    @Override protected void onCleared() {
        cleared = true; generation++;
        if (request != null) request.cancel();
        repository.close(); super.onCleared();
    }
}
