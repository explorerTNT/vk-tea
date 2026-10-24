package com.vktea.share;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.os.Looper;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStore;
import com.vktea.feed.PhotoShareRepository;
import com.vktea.feed.PhotoSocialError;
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
public class ShareViewModelTest {
    private final ViewModelStore store = new ViewModelStore();
    private final Fake repository = new Fake();
    private ShareViewModel model;
    @Before public void setup() { model = provider().get(ShareViewModel.class); model.open(ShareAttachment.post(-41, 12, "synthetic-key")); }
    @After public void clear() { store.clear(); }
    @Test public void initialChooserAndOwnWallConfirmationPerformNoReadOrWriteBeforeSubmit() {
        assertTrue(state().open); assertNull(state().destination); assertEquals(0, repository.reads());
        model.chooseDestination(ShareViewModel.Destination.OWN_WALL);
        assertNotNull(state().selected); assertTrue(state().canSubmit); assertEquals(0, repository.writes.size());
        model.setDraft("Synthetic caption"); model.submit(); model.submit();
        assertEquals(1, repository.writes.size()); assertEquals(0, repository.groupId); assertEquals("wall-41_12", repository.attachment.object());
        assertSame(model, provider().get(ShareViewModel.class)); assertTrue(state().busy);
        repository.writes.get(0).success(88L);
        assertFalse(state().open); assertEquals(ShareViewModel.Event.PUBLISHED, state().event);
        model.consumeEvent(); assertNull(state().event);
    }
    @Test public void communitySelectionOnlyAcceptsLoadedWritableTargetsAndKeepsCorrectGroupForSubmit() {
        model.chooseDestination(ShareViewModel.Destination.COMMUNITY);
        model.selectTarget(new ShareViewModel.Target(999, "Unknown", "")); assertNull(state().selected);
        repository.groups.get(0).success(Arrays.asList(new PhotoShareRepository.Community(7, "Synthetic Alpha", ""),
                new PhotoShareRepository.Community(8, "Synthetic Beta", "")));
        model.setQuery("beta"); assertEquals(1, state().targets.size()); assertEquals(8, state().targets.get(0).id);
        assertEquals(1, repository.groups.size()); model.selectTarget(state().targets.get(0));
        model.setDraft("Synthetic group caption"); assertEquals(0, repository.writes.size()); model.submit();
        assertEquals(8, repository.groupId); assertEquals("Synthetic group caption", repository.draft); assertEquals(1, repository.writes.size());
    }
    @Test public void failedWallPublicationIsNotReplayedOnRotationOrRepeatedSubmit() {
        model.chooseDestination(ShareViewModel.Destination.OWN_WALL); model.setDraft("Synthetic frozen wall draft"); model.submit();
        repository.writes.get(0).error(new PhotoSocialError(PhotoSocialError.Kind.NETWORK));
        assertFalse(state().canSubmit); assertFalse(state().busy); assertTrue(state().retrying);
        model.submit(); model.open(ShareAttachment.post(-41, 12, "synthetic-key")); model.setDraft("Changed draft");
        assertSame(model, provider().get(ShareViewModel.class)); assertEquals(1, repository.writes.size());
        assertEquals("Synthetic frozen wall draft", state().draft);
        model.back(); assertNull(state().destination); assertEquals(0, repository.reads());
    }
    @Test public void friendsIncludeUsersWithoutDialogAndUseStablePayloadForExplicitDmRetry() {
        model.chooseDestination(ShareViewModel.Destination.FRIENDS);
        repository.friends.get(0).success(Arrays.asList(new PhotoShareRepository.Recipient(7, "Synthetic Alpha", ""),
                new PhotoShareRepository.Recipient(8, "Synthetic Beta", "")));
        model.setQuery("Beta"); assertEquals(1, state().targets.size()); assertEquals(8, state().targets.get(0).id);
        assertEquals(0, repository.dialogs.size()); assertEquals(1, repository.friends.size());
        model.selectTarget(state().targets.get(0)); model.setDraft("Synthetic DM draft"); model.submit(); int id = repository.randomId;
        assertTrue(id > 0); repository.writes.get(0).error(new PhotoSocialError(PhotoSocialError.Kind.NETWORK));
        assertTrue(state().canSubmit); model.setDraft("Must not change uncertain payload"); model.submit();
        assertEquals(2, repository.writes.size()); assertEquals(id, repository.randomId); assertEquals("Synthetic DM draft", repository.draft);
        assertEquals(8, repository.peerId); assertEquals("message", repository.action);
    }
    @Test public void friendsRetryIsExplicitAndLateDestinationReadCannotReplaceAnotherList() {
        model.chooseDestination(ShareViewModel.Destination.FRIENDS); Pending<List<PhotoShareRepository.Recipient>> old = repository.friends.get(0);
        old.error(new PhotoSocialError(PhotoSocialError.Kind.ACCESS)); assertFalse(state().loading); model.consumeEvent();
        assertEquals(1, repository.friends.size()); model.refresh(); assertEquals(2, repository.friends.size());
        Pending<List<PhotoShareRepository.Recipient>> pending = repository.friends.get(1);
        model.back(); assertTrue(pending.canceled); model.chooseDestination(ShareViewModel.Destination.DIALOG);
        pending.success(Collections.singletonList(new PhotoShareRepository.Recipient(99, "Old friend", "")));
        assertTrue(state().targets.isEmpty()); repository.dialogs.get(0).success(Collections.singletonList(new PhotoShareRepository.Recipient(7, "Current dialog", "")));
        assertEquals(7, state().targets.get(0).id);
    }
    @Test public void dialogQueryDebouncesAndClearingOwnerCancelsReadsAndPendingWrite() {
        model.chooseDestination(ShareViewModel.Destination.DIALOG); model.setQuery("S"); model.setQuery("Synthetic");
        assertTrue(repository.dialogs.get(0).canceled); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        assertEquals(2, repository.dialogs.size()); assertEquals("Synthetic", repository.query);
        repository.dialogs.get(1).success(Collections.singletonList(new PhotoShareRepository.Recipient(7, "Synthetic", "")));
        model.selectTarget(state().targets.get(0)); model.submit(); Pending<Long> write = repository.writes.get(0);
        store.clear(); assertTrue(write.canceled); assertTrue(repository.closed);
    }
    @Test public void deniedWallRightsStillAllowPhotoForwardingWithoutInventingWallPermissions() {
        model.open(ShareAttachment.photo(-41, 12, "synthetic-key", false));
        model.chooseDestination(ShareViewModel.Destination.OWN_WALL); assertNull(state().destination);
        model.chooseDestination(ShareViewModel.Destination.COMMUNITY); assertNull(state().destination); assertEquals(0, repository.groups.size());
        model.chooseDestination(ShareViewModel.Destination.DIALOG); repository.dialogs.get(0).success(Collections.singletonList(new PhotoShareRepository.Recipient(7, "Synthetic", "")));
        model.selectTarget(state().targets.get(0)); model.submit(); assertEquals("photo-41_12", repository.attachment.object());
    }
    private ShareViewModel.State state() { return model.state().getValue(); }
    private ViewModelProvider provider() {
        return new ViewModelProvider(store, new ViewModelProvider.Factory() {
            @Override @SuppressWarnings("unchecked") public <T extends ViewModel> T create(Class<T> type) { return (T) new ShareViewModel(RuntimeEnvironment.getApplication(), repository); }
        });
    }
    static final class Pending<T> implements PhotoShareRepository.Request {
        final PhotoShareRepository.Callback<T> callback; boolean canceled;
        Pending(PhotoShareRepository.Callback<T> callback) { this.callback = callback; }
        @Override public void cancel() { canceled = true; }
        void success(T value) { callback.success(value); }
        void error(PhotoSocialError error) { callback.error(error); }
    }
    static final class Fake implements PhotoShareRepository {
        final List<Pending<List<Recipient>>> dialogs = new ArrayList<>(), friends = new ArrayList<>();
        final List<Pending<List<Community>>> groups = new ArrayList<>(); final List<Pending<Long>> writes = new ArrayList<>();
        ShareAttachment attachment; long groupId, peerId; int randomId; String query, draft, action; boolean closed;
        int reads() { return dialogs.size() + friends.size() + groups.size(); }
        @Override public Request recipients(String query, Callback<List<Recipient>> callback) { this.query = query; Pending<List<Recipient>> pending = new Pending<>(callback); dialogs.add(pending); return pending; }
        @Override public Request friends(String query, Callback<List<Recipient>> callback) { Pending<List<Recipient>> pending = new Pending<>(callback); friends.add(pending); return pending; }
        @Override public Request communities(Callback<List<Community>> callback) { Pending<List<Community>> pending = new Pending<>(callback); groups.add(pending); return pending; }
        @Override public Request send(ShareAttachment attachment, Recipient recipient, int randomId, String draft, Callback<Long> callback) {
            this.attachment = attachment; peerId = recipient.peerId; this.randomId = randomId; this.draft = draft; action = "message"; return write(callback);
        }
        @Override public Request repost(ShareAttachment attachment, long groupId, String draft, Callback<Long> callback) {
            this.attachment = attachment; this.groupId = groupId; this.draft = draft; action = "wall"; return write(callback);
        }
        private Request write(Callback<Long> callback) { Pending<Long> pending = new Pending<>(callback); writes.add(pending); return pending; }
        @Override public void close() { closed = true; }
    }
}
