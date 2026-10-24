package com.vktea.feed;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.app.Application;
import android.os.Looper;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class PhotoShareViewModelTest {
    private final ViewModelStore store = new ViewModelStore();
    private final FakeRepository repository = new FakeRepository();
    private PhotoShareViewModel model;
    private Application application;
    @Before public void setup() { application = RuntimeEnvironment.getApplication(); model = provider().get(PhotoShareViewModel.class); }
    @After public void clear() { store.clear(); }
    @Test public void openingAndReobservingOnlyLoadRecipientsWithoutSending() {
        model.open(photo()); assertEquals(1, repository.lookups.size());
        model.open(photo()); assertEquals(1, repository.lookups.size());
        assertSame(model, provider().get(PhotoShareViewModel.class));
        Observer<PhotoShareViewModel.State> observer = state -> { }; model.state().observeForever(observer);
        model.state().removeObserver(observer);
        assertTrue(state().open); assertNull(state().selected); assertEquals(0, repository.sends.size());
    }
    @Test public void queryDebounceCancelsOldLookupAndIgnoresItsLateResult() {
        model.open(photo()); Pending<List<PhotoShareRepository.Recipient>> old = repository.lookups.get(0);
        model.setQuery("S"); model.setQuery("Synthetic"); assertTrue(old.canceled);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(299)); assertEquals(1, repository.lookups.size());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1)); assertEquals(2, repository.lookups.size());
        assertEquals("Synthetic", repository.queries.get(1));
        old.success(Collections.singletonList(recipient(99))); assertTrue(state().recipients.isEmpty());
        repository.lookups.get(1).success(Collections.singletonList(recipient(7)));
        assertEquals(7, state().recipients.get(0).peerId); assertFalse(state().loading);
    }
    @Test public void choosingRecipientIsNotSendingAndBusySurvivesRetainedOwner() {
        ready(); model.selectRecipient(recipient(7)); model.setDraft("Synthetic draft");
        assertEquals(0, repository.sends.size()); assertEquals(0, state().randomId);
        model.send(); int id = state().randomId;
        assertTrue(id > 0); assertTrue(state().busy); assertEquals(1, repository.sends.size());
        model.send(); model.open(photo()); model.close(); model.cancelRecipient();
        assertSame(model, provider().get(PhotoShareViewModel.class));
        assertEquals(1, repository.sends.size()); assertEquals(id, state().randomId);
        repository.sends.get(0).success(123L);
        assertFalse(state().open); assertFalse(state().busy); assertEquals(PhotoShareViewModel.Event.SENT, state().event);
        model.consumeEvent(); assertNull(state().event);
    }
    @Test public void ambiguousFailureKeepsExactPayloadAndIdForExplicitRetryWithoutAutomaticSend() {
        ready(); model.selectRecipient(recipient(7)); model.setDraft("Synthetic exact payload"); model.send();
        int id = repository.ids.get(0);
        repository.sends.get(0).error(new PhotoSocialError(PhotoSocialError.Kind.NETWORK));
        assertFalse(state().busy); assertTrue(state().retrying); assertEquals(id, state().randomId);
        model.setDraft("Changed text must not replace an uncertain operation"); model.consumeEvent();
        model.open(photo()); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertEquals(1, repository.sends.size()); assertEquals("Synthetic exact payload", state().draft);
        model.send(); assertEquals(2, repository.sends.size());
        assertEquals(repository.ids.get(0), repository.ids.get(1)); assertEquals(repository.drafts.get(0), repository.drafts.get(1));
        assertEquals(repository.targets.get(0).peerId, repository.targets.get(1).peerId);
    }
    @Test public void onlyKnownRecipientCanBeConfirmedAndNewOperationResetsUncertainKey() {
        ready(); model.selectRecipient(recipient(999)); model.send(); assertNull(state().selected); assertEquals(0, repository.sends.size());
        model.selectRecipient(recipient(7)); model.send(); repository.sends.get(0).error(new PhotoSocialError(PhotoSocialError.Kind.ACCESS));
        model.cancelRecipient(); assertNull(state().selected); assertEquals(0, state().randomId);
        model.selectRecipient(recipient(8)); model.setDraft("New operation"); model.send();
        assertEquals(8, repository.targets.get(1).peerId); assertEquals("New operation", repository.drafts.get(1));
    }
    @Test public void closingOrClearingCancelsLookupAndSuppressesLateUiChanges() {
        model.open(photo()); Pending<List<PhotoShareRepository.Recipient>> pending = repository.lookups.get(0);
        model.close(); assertTrue(pending.canceled); pending.success(Collections.singletonList(recipient(7)));
        assertFalse(state().open); assertTrue(state().recipients.isEmpty());
        ready(); model.selectRecipient(recipient(7)); model.send(); Pending<Long> send = repository.sends.get(0);
        store.clear(); assertTrue(send.canceled); assertTrue(repository.closed);
    }
    @Test public void urlOnlySourceCannotLookupOrSendAndDeniedWallPermissionDoesNotBlockForwarding() {
        model.open(ViewerPhoto.fromUrl("https://synthetic.invalid/photo.jpg")); assertEquals(0, repository.lookups.size());
        model.open(photo()); repository.lookups.get(0).success(Collections.singletonList(recipient(7)));
        assertFalse(state().photo.canRepost); model.selectRecipient(recipient(7)); model.send(); assertEquals(1, repository.sends.size());
    }
    private void ready() {
        model.open(photo()); repository.lookups.get(repository.lookups.size() - 1).success(Arrays.asList(recipient(7), recipient(8)));
    }
    private PhotoShareViewModel.State state() { return model.state().getValue(); }
    private ViewModelProvider provider() {
        return new ViewModelProvider(store, new ViewModelProvider.Factory() {
            @Override @SuppressWarnings("unchecked") public <T extends ViewModel> T create(Class<T> type) { return (T) new PhotoShareViewModel(application, repository); }
        });
    }
    static ViewerPhoto photo() { return new ViewerPhoto("https://synthetic.invalid/photo.jpg", 12, -41, "synthetic-key", 0, 0, 0, false, true, true, false); }
    static PhotoShareRepository.Recipient recipient(long id) { return new PhotoShareRepository.Recipient(id, "Synthetic recipient " + id, ""); }
    static final class Pending<T> implements PhotoShareRepository.Request {
        final PhotoShareRepository.Callback<T> callback; boolean canceled;
        Pending(PhotoShareRepository.Callback<T> callback) { this.callback = callback; }
        @Override public void cancel() { canceled = true; }
        void success(T value) { callback.success(value); }
        void error(PhotoSocialError error) { callback.error(error); }
    }
    static final class FakeRepository implements PhotoShareRepository {
        final List<Pending<List<Recipient>>> lookups = new ArrayList<>();
        final List<Pending<Long>> sends = new ArrayList<>();
        final List<String> queries = new ArrayList<>(), drafts = new ArrayList<>();
        final List<Recipient> targets = new ArrayList<>(); final List<Integer> ids = new ArrayList<>();
        boolean closed;
        @Override public Request recipients(String query, Callback<List<Recipient>> callback) {
            queries.add(query); Pending<List<Recipient>> pending = new Pending<>(callback); lookups.add(pending); return pending;
        }
        @Override public Request send(ViewerPhoto photo, Recipient recipient, int randomId, String draft, Callback<Long> callback) {
            targets.add(recipient); ids.add(randomId); drafts.add(draft); Pending<Long> pending = new Pending<>(callback); sends.add(pending); return pending;
        }
        @Override public void close() { closed = true; }
    }
}
