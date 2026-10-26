package com.vktea.share;

import static org.junit.Assert.*;
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
import java.util.Collections;
import java.util.List;

/** Recent-row reads are independent of destination forms and never imply a message send. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ShareRecentViewModelTest {
    private final ShareViewModelTest.Fake repository = new ShareViewModelTest.Fake();
    private final ViewModelStore store = new ViewModelStore();
    private ShareViewModel model;
    @Before public void setup() { model = provider().get(ShareViewModel.class); model.open(ShareAttachment.post(-41, 12, "synthetic-key")); }
    @After public void clear() { store.clear(); }
    @Test public void explicitPreloadRunsOnceAcrossRepeatedRenderingAndRetainedOwner() {
        assertEquals(0, repository.reads()); model.preloadRecent(); model.preloadRecent();
        assertEquals(1, repository.dialogs.size()); assertTrue(state().recentLoading); assertNull(state().destination);
        assertSame(model, provider().get(ShareViewModel.class)); model.open(ShareAttachment.post(-41, 12, "synthetic-key")); model.preloadRecent();
        assertEquals(1, repository.reads()); repository.dialogs.get(0).success(peers());
        assertTrue(state().recentLoaded); assertFalse(state().recentLoading); assertEquals(7, state().recentTargets.get(0).id);
        model.preloadRecent(); assertEquals(1, repository.reads()); assertEquals(0, repository.writes.size());
    }
    @Test public void recentCompletionCannotChangeCommunityConfirmationOrDraft() {
        model.preloadRecent(); model.chooseDestination(ShareViewModel.Destination.COMMUNITY);
        repository.groups.get(0).success(Collections.singletonList(new PhotoShareRepository.Community(8, "Synthetic community", "")));
        model.selectTarget(state().targets.get(0)); model.setDraft("Synthetic retained caption");
        repository.dialogs.get(0).success(peers());
        assertEquals(ShareViewModel.Destination.COMMUNITY, state().destination); assertEquals(8, state().selected.id);
        assertEquals("Synthetic retained caption", state().draft); assertTrue(state().canSubmit); assertEquals(1, state().recentTargets.size());
        assertEquals(0, repository.writes.size());
    }
    @Test public void recentErrorIsSeparateAndOnlyExplicitRetryMakesAnotherRead() {
        model.preloadRecent(); repository.dialogs.get(0).error(new PhotoSocialError(PhotoSocialError.Kind.NETWORK));
        assertNotNull(state().recentError); assertNull(state().error); assertNull(state().event); assertFalse(state().recentLoading);
        model.preloadRecent(); assertSame(model, provider().get(ShareViewModel.class)); assertEquals(1, repository.reads());
        model.retryRecent(); model.retryRecent(); assertEquals(2, repository.reads());
        repository.dialogs.get(1).success(Collections.emptyList()); assertTrue(state().recentLoaded); assertTrue(state().recentTargets.isEmpty());
        assertNull(state().recentError); assertEquals(0, repository.writes.size());
    }
    @Test public void onlyKnownRecentPeerOpensConfirmationAndBackRestoresCachedHomeWithoutRead() {
        model.preloadRecent(); repository.dialogs.get(0).success(peers());
        model.selectRecent(new ShareViewModel.Target(999, "Unknown", "")); assertNull(state().selected);
        model.selectRecent(state().recentTargets.get(0));
        assertEquals(ShareViewModel.Destination.DIALOG, state().destination); assertEquals(7, state().selected.id); assertTrue(state().canSubmit);
        assertEquals(1, repository.reads()); assertEquals(0, repository.writes.size());
        model.back(); model.preloadRecent(); assertNull(state().destination); assertEquals(1, state().recentTargets.size()); assertEquals(1, repository.reads());
    }
    @Test public void directRecentSendStillNeedsExplicitSubmitAndRetainsAnUncertainOperation() {
        model.preloadRecent(); repository.dialogs.get(0).success(peers()); model.selectRecent(state().recentTargets.get(0));
        model.setDraft("Synthetic recent draft"); assertEquals(0, repository.writes.size()); model.submit(); model.submit();
        assertEquals(1, repository.writes.size()); int id = repository.randomId;
        assertSame(model, provider().get(ShareViewModel.class)); model.preloadRecent(); assertEquals(1, repository.reads());
        repository.writes.get(0).error(new PhotoSocialError(PhotoSocialError.Kind.NETWORK));
        model.setDraft("Must remain unchanged"); model.submit();
        assertEquals(2, repository.writes.size()); assertEquals(id, repository.randomId); assertEquals("Synthetic recent draft", repository.draft);
    }
    @Test public void newAttachmentOrClearedOwnerCancelsRecentReadAndIgnoresLateResult() {
        model.preloadRecent(); ShareViewModelTest.Pending<List<PhotoShareRepository.Recipient>> old = repository.dialogs.get(0);
        model.open(ShareAttachment.post(-41, 13, "synthetic-key")); assertTrue(old.canceled); old.success(peers());
        assertTrue(state().recentTargets.isEmpty()); assertFalse(state().recentLoaded);
        model.preloadRecent(); ShareViewModelTest.Pending<List<PhotoShareRepository.Recipient>> pending = repository.dialogs.get(1);
        store.clear(); assertTrue(pending.canceled); assertTrue(repository.closed);
    }
    private ShareViewModel.State state() { return model.state().getValue(); }
    private static List<PhotoShareRepository.Recipient> peers() { return Collections.singletonList(new PhotoShareRepository.Recipient(7, "Synthetic recipient", "")); }
    private ViewModelProvider provider() {
        return new ViewModelProvider(store, new ViewModelProvider.Factory() {
            @Override @SuppressWarnings("unchecked") public <T extends ViewModel> T create(Class<T> type) { return (T) new ShareViewModel(RuntimeEnvironment.getApplication(), repository); }
        });
    }
}
