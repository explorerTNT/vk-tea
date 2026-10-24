package com.vktea.share;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.vktea.feed.PhotoShareRepository;
import com.vktea.feed.PhotoSocialError;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Destination and confirmation are separate retained states. Only submit() performs a write. */
public class ShareViewModel extends AndroidViewModel {
    public enum Destination { OWN_WALL, COMMUNITY, FRIENDS, DIALOG }
    public enum Event { ERROR, PUBLISHED, SENT }
    public static final class Target {
        public final long id;
        public final String title, photo;
        public Target(long id, String title, String photo) { this.id = id; this.title = title == null ? "" : title; this.photo = photo == null ? "" : photo; }
    }
    public static final class State {
        public final ShareAttachment attachment;
        public final Destination destination;
        public final Target selected;
        public final List<Target> targets;
        public final boolean open, loading, busy, retrying, canSubmit;
        public final String query, draft;
        public final PhotoSocialError error;
        public final Event event;
        private State(ShareViewModel model) {
            attachment = model.attachment; destination = model.destination; selected = model.selected; targets = model.targets;
            open = model.open; loading = model.loading; busy = model.busy; retrying = model.attempted;
            canSubmit = model.canSubmit(); query = model.query; draft = model.draft; error = model.error; event = model.event;
        }
    }
    private final PhotoShareRepository repository;
    private final MutableLiveData<State> state = new MutableLiveData<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();
    private ShareAttachment attachment;
    private Destination destination;
    private Target selected;
    private List<Target> targets = Collections.emptyList(), allCommunities = Collections.emptyList(), allFriends = Collections.emptyList();
    private boolean open, loading, busy, attempted, cleared;
    private String query = "", draft = "";
    private int generation, randomId;
    private PhotoSocialError error;
    private Event event;
    private PhotoShareRepository.Request lookup, mutation;
    private Runnable deferred;
    public ShareViewModel(Application application) { this(application, PhotoShareRepository.create(application)); }
    public ShareViewModel(Application application, PhotoShareRepository repository) { super(application); this.repository = repository; publish(); }
    public LiveData<State> state() { return state; }
    public void open(ShareAttachment value) {
        if (cleared || busy || value == null || !value.hasIdentity()) return;
        if (open && attachment != null && attachment.object().equals(value.object())) return;
        cancelLookup(); generation++; attachment = value; open = true; destination = null; selected = null;
        targets = allCommunities = allFriends = Collections.emptyList(); query = draft = ""; loading = attempted = false; randomId = 0; error = null; event = null; publish();
    }
    public void chooseDestination(Destination value) {
        if (!open || busy || value == null || value == destination || (!isMessage(value) && !attachment.canRepost)) return;
        generation++; cancelLookup(); destination = value; selected = null; targets = Collections.emptyList();
        query = draft = ""; attempted = false; randomId = 0; error = null; event = null; loading = false;
        if (value == Destination.OWN_WALL) { selected = new Target(0, "", ""); publish(); }
        else if (value == Destination.COMMUNITY) loadCommunities(); else if (value == Destination.FRIENDS) loadFriends(); else loadDialogs(false);
    }
    public void selectTarget(Target value) {
        if (!open || busy || loading || value == null || destination == null || destination == Destination.OWN_WALL) return;
        Target found = null;
        for (Target known : targets) if (known.id == value.id) { found = known; break; }
        if (found == null) return;
        generation++; cancelLookup(); selected = found; draft = ""; attempted = false; randomId = 0; error = null; event = null; publish();
    }
    public void back() {
        if (!open || busy) return;
        generation++; cancelLookup(); loading = false; error = null; event = null; attempted = false; randomId = 0; draft = "";
        if (selected != null && destination != Destination.OWN_WALL) selected = null;
        else { selected = null; destination = null; targets = Collections.emptyList(); query = ""; }
        publish();
    }
    public void setQuery(String value) {
        if (!open || busy || selected != null || destination == null || destination == Destination.OWN_WALL) return;
        String next = value == null ? "" : value; if (next.equals(query)) return;
        query = next;
        if (destination == Destination.COMMUNITY || destination == Destination.FRIENDS) { filterTargets(); publish(); } else loadDialogs(true);
    }
    public void setDraft(String value) { if (!busy && !attempted) { draft = value == null ? "" : value; publish(); } }
    public void refresh() {
        if (!open || busy || selected != null || loading) return;
        if (destination == Destination.COMMUNITY) loadCommunities(); else if (destination == Destination.FRIENDS) loadFriends();
        else if (destination == Destination.DIALOG) loadDialogs(false);
    }
    public void submit() {
        if (!canSubmit()) return;
        busy = true; attempted = true; error = null; event = null;
        if (isMessage(destination) && randomId == 0) randomId = random.nextInt(Integer.MAX_VALUE - 1) + 1;
        publish();
        Destination targetDestination = destination;
        PhotoShareRepository.Callback<Long> callback = new PhotoShareRepository.Callback<Long>() {
            @Override public void success(Long id) {
                if (cleared) return;
                mutation = null; busy = false; open = false;
                event = isMessage(targetDestination) ? Event.SENT : Event.PUBLISHED; publish();
            }
            @Override public void error(PhotoSocialError failure) {
                if (cleared) return;
                mutation = null; busy = false; fail(failure);
            }
        };
        if (isMessage(destination))
            mutation = repository.send(attachment, new PhotoShareRepository.Recipient(selected.id, selected.title, selected.photo), randomId, draft, callback);
        else mutation = repository.repost(attachment, destination == Destination.COMMUNITY ? selected.id : 0, draft, callback);
    }
    public void consumeEvent() { if (event != null) { event = null; publish(); } }
    private boolean canSubmit() {
        return !cleared && open && attachment != null && attachment.hasIdentity() && destination != null && selected != null
                && !loading && !busy && (isMessage(destination) || attachment.canRepost && !attempted);
    }
    private void loadCommunities() {
        int request = ++generation; cancelLookup(); loading = true; error = null; event = null; publish();
        lookup = repository.communities(new PhotoShareRepository.Callback<List<PhotoShareRepository.Community>>() {
            @Override public void success(List<PhotoShareRepository.Community> result) {
                if (!current(request, Destination.COMMUNITY)) return;
                lookup = null; loading = false; List<Target> rows = new ArrayList<>();
                for (PhotoShareRepository.Community group : result) if (group.id > 0) rows.add(new Target(group.id, group.title, group.photo));
                allCommunities = Collections.unmodifiableList(rows); filterTargets(); publish();
            }
            @Override public void error(PhotoSocialError failure) {
                if (!current(request, Destination.COMMUNITY)) return;
                lookup = null; loading = false; fail(failure);
            }
        });
    }
    private void loadFriends() {
        int request = ++generation; cancelLookup(); loading = true; error = null; event = null; publish();
        lookup = repository.friends("", new PhotoShareRepository.Callback<List<PhotoShareRepository.Recipient>>() {
            @Override public void success(List<PhotoShareRepository.Recipient> result) {
                if (!current(request, Destination.FRIENDS)) return;
                lookup = null; loading = false; List<Target> rows = new ArrayList<>();
                for (PhotoShareRepository.Recipient friend : result) if (friend.peerId > 0) rows.add(new Target(friend.peerId, friend.title, friend.photo));
                allFriends = Collections.unmodifiableList(rows); filterTargets(); publish();
            }
            @Override public void error(PhotoSocialError failure) {
                if (!current(request, Destination.FRIENDS)) return;
                lookup = null; loading = false; fail(failure);
            }
        });
    }
    private void filterTargets() {
        String needle = query.trim().toLowerCase(Locale.ROOT); List<Target> rows = new ArrayList<>();
        for (Target target : destination == Destination.FRIENDS ? allFriends : allCommunities)
            if (target.title.toLowerCase(Locale.ROOT).contains(needle)) rows.add(target);
        targets = Collections.unmodifiableList(rows);
    }
    private void loadDialogs(boolean debounce) {
        int request = ++generation; cancelLookup(); loading = true; error = null; event = null; targets = Collections.emptyList(); publish();
        String search = query.trim();
        Destination expected = destination;
        deferred = () -> {
            deferred = null;
            PhotoShareRepository.Callback<List<PhotoShareRepository.Recipient>> callback = new PhotoShareRepository.Callback<List<PhotoShareRepository.Recipient>>() {
                @Override public void success(List<PhotoShareRepository.Recipient> result) {
                    if (!current(request, expected)) return;
                    lookup = null; loading = false; List<Target> rows = new ArrayList<>();
                    for (PhotoShareRepository.Recipient peer : result) rows.add(new Target(peer.peerId, peer.title, peer.photo));
                    targets = Collections.unmodifiableList(rows); publish();
                }
                @Override public void error(PhotoSocialError failure) {
                    if (!current(request, expected)) return;
                    lookup = null; loading = false; fail(failure);
                }
            };
            lookup = repository.recipients(search, callback);
        };
        if (debounce) main.postDelayed(deferred, 300); else deferred.run();
    }
    private boolean current(int request, Destination expected) { return !cleared && open && selected == null && generation == request && destination == expected; }
    private static boolean isMessage(Destination value) { return value == Destination.DIALOG || value == Destination.FRIENDS; }
    private void fail(PhotoSocialError failure) { error = failure; event = Event.ERROR; publish(); }
    private void cancelLookup() {
        if (deferred != null) main.removeCallbacks(deferred); deferred = null;
        if (lookup != null) lookup.cancel(); lookup = null;
    }
    private void publish() { if (!cleared) state.setValue(new State(this)); }
    @Override protected void onCleared() { cleared = true; cancelLookup(); if (mutation != null) mutation.cancel(); repository.close(); }
}
