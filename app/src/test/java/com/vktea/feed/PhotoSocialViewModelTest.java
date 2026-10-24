package com.vktea.feed;

import static org.junit.Assert.*;
import android.app.Application;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PhotoSocialViewModelTest {
    private final ViewModelStore store = new ViewModelStore();
    private final FakeRepository repository = new FakeRepository();
    private PhotoSocialViewModel model;
    private Application application;
    @Before public void setup() { application = RuntimeEnvironment.getApplication(); model = provider().get(PhotoSocialViewModel.class); }
    @After public void clear() { store.clear(); }

    @Test public void urlOnlyPhotoDoesNotFetchOrOfferAnyMutation() {
        model.select(ViewerPhoto.fromUrl("https://synthetic.invalid/photo.jpg"));
        model.toggleLike(); model.openComments(); model.openRepost(); model.sendComment(); model.submitRepost();
        assertEquals(0, repository.loads.size()); assertEquals(0, repository.writes.size());
        assertFalse(state().loading); assertFalse(state().commentsOpen); assertFalse(state().repostOpen);
    }
    @Test public void repeatedIdentityPreservesServerCountsAndOnlyUpdatesImageSource() {
        model.select(photo(1)); model.select(photo(1));
        assertEquals(1, repository.loads.size());
        repository.loads.get(0).success(photo(1, 12, false, true));
        model.select(new ViewerPhoto("https://synthetic.invalid/fresh.jpg", 1, 41, "", 0, 0, 0, false, false, false, false));
        assertEquals(12, state().photo.likes); assertEquals("https://synthetic.invalid/fresh.jpg", state().photo.url);
        assertEquals(1, repository.loads.size());
    }
    @Test public void selectionCancelsReadsAndLateResultCannotReplaceCurrentPhoto() {
        model.select(photo(1)); Pending<ViewerPhoto> old = repository.loads.get(0);
        model.select(photo(2)); assertTrue(old.canceled);
        old.success(photo(1, 99, true, true));
        assertEquals(2, state().photo.id); assertTrue(state().loading);
        repository.loads.get(1).success(photo(2)); assertFalse(state().loading);
    }
    @Test public void retainedBusyLikeDoesNotDuplicateAndExistingLikeCanBeRemovedWithoutCanLike() {
        load(photo(1, 9, true, false));
        model.toggleLike(); model.toggleLike();
        assertSame(model, provider().get(PhotoSocialViewModel.class));
        assertTrue(state().busy); assertEquals(1, repository.writes.size());
        assertEquals("unlike", repository.action);
        repository.<Integer>write().success(8);
        assertFalse(state().busy); assertFalse(state().photo.liked); assertEquals(8, state().photo.likes);
    }
    @Test public void repostNeedsExplicitSubmitAndNetworkFailureDoesNotReplayOrDiscardDraft() {
        load(photo(1)); model.openRepost(); model.setRepostDraft("Synthetic draft");
        assertTrue(state().repostOpen); assertEquals(0, repository.writes.size());
        model.submitRepost(); model.submitRepost(); assertEquals(1, repository.writes.size());
        repository.write().error(new PhotoSocialError(PhotoSocialError.Kind.NETWORK));
        assertEquals("Synthetic draft", state().repostDraft); assertTrue(state().repostOpen);
        assertEquals(PhotoSocialViewModel.Event.ERROR, state().event);
        model.consumeEvent(); Observer<PhotoSocialViewModel.State> observer = result -> assertNull(result.event);
        model.state().observeForever(observer); model.select(photo(1)); model.state().removeObserver(observer);
        assertEquals(1, repository.writes.size());
        model.submitRepost(); assertEquals(2, repository.writes.size());
        repository.<Integer>write().success(6);
        assertEquals(6, state().photo.reposts); assertFalse(state().repostOpen); assertEquals("", state().repostDraft);
        assertEquals(PhotoSocialViewModel.Event.REPOSTED, state().event);
    }
    @Test public void commentSendsOnceAndRefreshesReadWithoutReplayingMutation() {
        load(photo(1)); model.openComments();
        Pending<PhotoSocialRepository.Comments> old = repository.reads.get(0);
        model.setCommentDraft("  Synthetic comment  "); assertEquals(0, repository.writes.size());
        model.sendComment(); model.sendComment(); assertEquals(1, repository.writes.size());
        assertEquals("Synthetic comment", repository.text);
        repository.<Long>write().success(77L);
        assertEquals("", state().commentDraft); assertEquals(4, state().photo.comments);
        assertTrue(old.canceled); assertEquals(2, repository.reads.size());
        old.success(new PhotoSocialRepository.Comments(99, Collections.emptyList()));
        assertEquals(4, state().commentsTotal);
        repository.reads.get(1).success(new PhotoSocialRepository.Comments(4,
                Collections.singletonList(new PhotoSocialRepository.Comment(77, 41, "Synthetic", "Synthetic comment", 1))));
        assertEquals(1, state().comments.size()); assertEquals(1, repository.writes.size());
    }
    @Test public void deniedWritesStillPermitReadingCommentsAndManualMetadataRecoveryIsExplicit() {
        model.select(photo(1)); repository.loads.get(0).error(new PhotoSocialError(PhotoSocialError.Kind.NETWORK));
        assertEquals(1, repository.loads.size()); model.refresh(); model.refresh(); assertEquals(2, repository.loads.size());
        repository.loads.get(1).success(new ViewerPhoto("synthetic", 1, 41, "", 2, 3, 4, false, false, false, false));
        model.toggleLike(); model.openRepost(); model.setCommentDraft("Synthetic"); model.sendComment();
        assertEquals(0, repository.writes.size());
        model.openComments(); assertTrue(state().commentsOpen); assertEquals(1, repository.reads.size());
    }
    @Test public void oldPhotoWriteCompletesWithoutChangingNewSelectionAndClearCancelsPendingWork() {
        load(photo(1)); model.toggleLike(); Pending<Integer> write = repository.write();
        model.select(photo(2)); repository.loads.get(1).success(photo(2));
        write.success(50); assertEquals(2, state().photo.id); assertEquals(2, state().photo.likes);
        model.openComments(); Pending<PhotoSocialRepository.Comments> read = repository.reads.get(0);
        model.toggleLike(); Pending<Integer> pending = repository.write();
        store.clear(); assertTrue(read.canceled); assertTrue(pending.canceled); assertTrue(repository.closed);
    }
    @Test public void metadataFromBeforeConfirmedWriteCannotRollBackLikeAfterReturningToPhoto() {
        load(photo(1)); model.toggleLike(); Pending<Integer> write = repository.write();
        model.select(photo(2)); model.select(photo(1));
        Pending<ViewerPhoto> metadata = repository.loads.get(2);
        write.success(11); assertTrue(state().photo.liked);
        metadata.success(photo(1, 2, false, true));
        assertTrue(state().photo.liked); assertEquals(11, state().photo.likes); assertFalse(state().loading);
    }
    private PhotoSocialViewModel.State state() { return model.state().getValue(); }
    private void load(ViewerPhoto photo) { model.select(photo); repository.loads.get(repository.loads.size() - 1).success(photo); }
    private ViewModelProvider provider() {
        return new ViewModelProvider(store, new ViewModelProvider.Factory() {
            @Override @SuppressWarnings("unchecked") public <T extends ViewModel> T create(Class<T> type) {
                return (T) new PhotoSocialViewModel(application, repository);
            }
        });
    }
    static ViewerPhoto photo(long id) { return photo(id, 2, false, true); }
    static ViewerPhoto photo(long id, int likes, boolean liked, boolean canLike) {
        return new ViewerPhoto("https://synthetic.invalid/" + id + ".jpg", id, 41, "synthetic-key", likes, 3, 4, liked, canLike, true, true);
    }
    static final class Pending<T> implements PhotoSocialRepository.Request {
        final PhotoSocialRepository.Callback<T> callback; boolean canceled;
        Pending(PhotoSocialRepository.Callback<T> callback) { this.callback = callback; }
        @Override public void cancel() { canceled = true; }
        void success(T value) { callback.success(value); }
        void error(PhotoSocialError error) { callback.error(error); }
    }
    static final class FakeRepository implements PhotoSocialRepository {
        final List<Pending<ViewerPhoto>> loads = new ArrayList<>();
        final List<Pending<Comments>> reads = new ArrayList<>();
        final List<Pending<?>> writes = new ArrayList<>();
        String action, text; boolean closed;
        @Override public Request load(ViewerPhoto photo, Callback<ViewerPhoto> callback) { Pending<ViewerPhoto> pending = new Pending<>(callback); loads.add(pending); return pending; }
        @Override public Request like(ViewerPhoto photo, boolean liked, Callback<Integer> callback) { action = liked ? "like" : "unlike"; return add(callback); }
        @Override public Request comments(ViewerPhoto photo, Callback<Comments> callback) { Pending<Comments> pending = new Pending<>(callback); reads.add(pending); return pending; }
        @Override public Request comment(ViewerPhoto photo, String text, Callback<Long> callback) { action = "comment"; this.text = text; return add(callback); }
        @Override public Request repost(ViewerPhoto photo, String text, Callback<Integer> callback) { action = "repost"; this.text = text; return add(callback); }
        private <T> Request add(Callback<T> callback) { Pending<T> pending = new Pending<>(callback); writes.add(pending); return pending; }
        @SuppressWarnings("unchecked") <T> Pending<T> write() { return (Pending<T>) writes.get(writes.size() - 1); }
        @Override public void close() { closed = true; }
    }
}
