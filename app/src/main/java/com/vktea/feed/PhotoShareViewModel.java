package com.vktea.feed;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Rotation retains the recipient, exact payload and idempotency key; observing never sends. */
public class PhotoShareViewModel extends AndroidViewModel {
    public enum Event { ERROR, SENT }
    public static final class State {
        public final ViewerPhoto photo;
        public final PhotoShareRepository.Recipient selected;
        public final List<PhotoShareRepository.Recipient> recipients;
        public final boolean open, loading, busy, retrying;
        public final String query, draft;
        public final int randomId;
        public final PhotoSocialError error;
        public final Event event;
        State(PhotoShareViewModel model) {
            photo = model.photo; selected = model.selected; recipients = model.recipients;
            open = model.open; loading = model.loading; busy = model.busy; retrying = model.randomId != 0;
            query = model.query; draft = model.draft; randomId = model.randomId; error = model.error; event = model.event;
        }
    }
    private final PhotoShareRepository repository;
    private final MutableLiveData<State> state = new MutableLiveData<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();
    private ViewerPhoto photo;
    private PhotoShareRepository.Recipient selected;
    private List<PhotoShareRepository.Recipient> recipients = Collections.emptyList();
    private boolean open, loading, busy, cleared;
    private String query = "", draft = "";
    private int generation, randomId;
    private PhotoSocialError error;
    private Event event;
    private PhotoShareRepository.Request lookup, send;
    private Runnable deferredLookup;

    public PhotoShareViewModel(Application app) { this(app, PhotoShareRepository.create(app)); }
    public PhotoShareViewModel(Application app, PhotoShareRepository repository) { super(app); this.repository = repository; publish(); }
    public LiveData<State> state() { return state; }
    public void open(ViewerPhoto value) {
        if (cleared || value == null || !value.hasIdentity() || busy) return;
        if (photo != null && photo.id == value.id && photo.ownerId == value.ownerId && open) return;
        photo = value; selected = null; randomId = 0; query = draft = ""; open = true; error = null; event = null;
        recipients = Collections.emptyList(); requestRecipients(false);
    }
    public void close() {
        if (busy) return;
        open = false; selected = null; randomId = 0; draft = ""; generation++; cancelLookup(); loading = false; publish();
    }
    public void setQuery(String value) {
        if (busy || selected != null || !open) return;
        String next = value == null ? "" : value;
        if (next.equals(query)) return;
        query = next; recipients = Collections.emptyList(); requestRecipients(true);
    }
    public void refresh() { if (open && !busy && selected == null) requestRecipients(false); }
    public void selectRecipient(PhotoShareRepository.Recipient recipient) {
        if (!open || busy || recipient == null || recipient.peerId == 0) return;
        // UI only selects server-supplied peers, never an arbitrary ID inferred from a photo or URL.
        PhotoShareRepository.Recipient match = null;
        for (PhotoShareRepository.Recipient known : recipients) if (known.peerId == recipient.peerId) { match = known; break; }
        if (match == null) return;
        selected = match; draft = ""; randomId = 0; error = null; event = null; generation++; cancelLookup(); loading = false; publish();
    }
    public void cancelRecipient() {
        if (busy) return;
        selected = null; draft = ""; randomId = 0; error = null; event = null; publish();
    }
    public void setDraft(String value) {
        if (busy || randomId != 0) return;
        draft = value == null ? "" : value; publish();
    }
    public void send() {
        if (cleared || !open || busy || photo == null || !photo.hasIdentity() || selected == null) return;
        if (randomId == 0) randomId = random.nextInt(Integer.MAX_VALUE - 1) + 1;
        busy = true; error = null; event = null; publish();
        send = repository.send(photo, selected, randomId, draft, new PhotoShareRepository.Callback<Long>() {
            @Override public void success(Long messageId) {
                if (cleared) return;
                send = null; busy = false; open = false; selected = null; randomId = 0; draft = ""; event = Event.SENT; publish();
            }
            @Override public void error(PhotoSocialError failure) {
                if (cleared) return;
                send = null; busy = false; error = failure; event = Event.ERROR; publish();
            }
        });
    }
    public void consumeEvent() { if (event != null) { event = null; publish(); } }
    private void requestRecipients(boolean debounce) {
        int request = ++generation; cancelLookup(); loading = true; error = null; event = null; publish();
        String search = query.trim();
        deferredLookup = () -> {
            deferredLookup = null;
            lookup = repository.recipients(search, new PhotoShareRepository.Callback<List<PhotoShareRepository.Recipient>>() {
                @Override public void success(List<PhotoShareRepository.Recipient> result) {
                    if (!current(request)) return;
                    lookup = null; loading = false;
                    recipients = Collections.unmodifiableList(new ArrayList<>(result)); publish();
                }
                @Override public void error(PhotoSocialError failure) {
                    if (!current(request)) return;
                    lookup = null; loading = false; error = failure; event = Event.ERROR; publish();
                }
            });
        };
        if (debounce) main.postDelayed(deferredLookup, 300); else deferredLookup.run();
    }
    private boolean current(int request) { return !cleared && open && selected == null && generation == request; }
    private void cancelLookup() {
        if (deferredLookup != null) main.removeCallbacks(deferredLookup);
        deferredLookup = null;
        if (lookup != null) lookup.cancel(); lookup = null;
    }
    private void publish() { if (!cleared) state.setValue(new State(this)); }
    @Override protected void onCleared() {
        cleared = true; cancelLookup(); if (send != null) send.cancel(); repository.close();
    }
}
