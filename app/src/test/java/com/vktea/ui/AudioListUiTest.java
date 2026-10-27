package com.vktea.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.vktea.R;
import com.vktea.audio.AudioPlaybackService;
import com.vktea.audio.catalog.VkAudioError;
import com.vktea.audio.catalog.VkAudioPage;
import com.vktea.audio.catalog.VkAudioPlaylist;
import com.vktea.audio.catalog.VkAudioRepository;
import com.vktea.audio.catalog.VkAudioTrack;
import com.vktea.audio.catalog.VkAudioViewModel;
import com.vktea.auth.Session;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** All library responses and Player operations are synthetic; no VK transport is created. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class AudioListUiTest {
    private ActivityController<CatalogHostActivity> controller;
    private CatalogHostActivity activity;
    private CatalogFragment fragment;
    private FakeRepository repository;
    private VkAudioViewModel model;
    private RecordingPlayer playback;

    @Before
    public void prepareSyntheticCatalogAndPlayer() {
        Context application = RuntimeEnvironment.getApplication();
        application.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
        Session.save(application, "synthetic-catalog-ui-token", "1");
        shadowOf(RuntimeEnvironment.getApplication()).declareComponentUnbindable(
                new ComponentName(application, AudioPlaybackService.class));
        repository = new FakeRepository();
        model = new VkAudioViewModel(repository);
        playback = new RecordingPlayer();
        playback.items.add(track(99, "Other queue item", "Other artist").toMediaItem());
        CatalogFragment.model = model;
        CatalogFragment.playback = playback;
        CatalogFragment.opened = 0;
        controller = Robolectric.buildActivity(CatalogHostActivity.class).setup();
        activity = controller.get();
        fragment = (CatalogFragment) activity.getSupportFragmentManager().findFragmentById(R.id.content);
    }

    @After
    public void closeSyntheticOwners() {
        if (ShadowDialog.getLatestDialog() != null) ShadowDialog.getLatestDialog().dismiss();
        controller.close();
        CatalogFragment.model = null;
        CatalogFragment.playback = null;
        RuntimeEnvironment.getApplication().getSharedPreferences("session", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test
    public void selectedCatalogTrackUsesIdentityMarkerDarkTitleAndActualDuration() {
        AudioQueueAdapter adapter = adapter(new AtomicInteger(), new AtomicInteger());
        MediaItem first = track(1, "Synthetic title", "Synthetic artist").toMediaItem();
        MediaItem second = track(2, "Second", "Artist").toMediaItem();
        adapter.submit(Arrays.asList(first, second), second.mediaId);
        RecyclerView parent = new RecyclerView(activity);
        parent.setLayoutManager(new LinearLayoutManager(activity));
        AudioQueueAdapter.Holder holder = adapter.onCreateViewHolder(parent, 0);

        adapter.onBindViewHolder(holder, 1);

        assertEquals("Second", holder.title.getText().toString());
        assertEquals(ContextCompat.getColor(activity, R.color.audio_text), holder.title.getCurrentTextColor());
        assertEquals(View.VISIBLE, holder.current.getVisibility());
        assertEquals(View.GONE, holder.local.getVisibility());
        assertEquals("2:18", holder.duration.getText().toString());
        adapter.onBindViewHolder(holder, 0);
        assertEquals(View.GONE, holder.current.getVisibility());
        adapter.submit(Arrays.asList(first, second), "vk-audio:17_9999");
        adapter.onBindViewHolder(holder, 1);
        assertEquals(View.GONE, holder.current.getVisibility());
    }

    @Test
    public void loadedFilterPreservesOriginalLibraryIndicesForClickAndLongPress() {
        AtomicInteger clicked = new AtomicInteger(-1);
        AtomicInteger held = new AtomicInteger(-1);
        AudioQueueAdapter adapter = adapter(clicked, held);
        adapter.submit(Arrays.asList(track(1, "Other", "Artist").toMediaItem(),
                track(2, "Desired", "Target artist").toMediaItem()), null);
        adapter.setQuery("TARGET ARTIST");
        RecyclerView list = new RecyclerView(activity);
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setItemAnimator(null);
        list.setAdapter(adapter);
        activity.addContentView(list, new ViewGroup.LayoutParams(400, 600));
        layout(list);

        assertEquals(1, adapter.getItemCount());
        assertEquals(1, adapter.libraryIndexAt(0));
        RecyclerView.ViewHolder row = list.findViewHolderForAdapterPosition(0);
        assertNotNull(row);
        row.itemView.performClick();
        row.itemView.performLongClick();
        assertEquals(1, clicked.get());
        assertEquals(1, held.get());
        adapter.setQuery("");
        assertEquals(2, adapter.getItemCount());
    }

    @Test
    public void initialLoadingAndRealEmptyAccountHaveNoImportFlowOrPlaybackMutation() {
        assertEquals(1, repository.calls.size());
        assertEquals(View.VISIBLE, root().findViewById(R.id.audio_list_loading).getVisibility());

        repository.success(new VkAudioPage(0, Collections.emptyList(), 0));

        assertEquals(activity.getString(R.string.audio_list_empty), emptyText());
        assertEquals(View.VISIBLE, root().findViewById(R.id.audio_list_empty_state).getVisibility());
        assertEquals(View.GONE, root().findViewById(R.id.audio_list_loading).getVisibility());
        assertEquals(0, playback.replacements);
        assertNull(shadowOf(activity).getNextStartedActivity());
        int obsoleteImportId = activity.getResources().getIdentifier("audio_list_import", "id", activity.getPackageName());
        assertTrue(obsoleteImportId == 0 || root().findViewById(obsoleteImportId) == null);
    }

    @Test
    public void playlistDialogsValidateAndConfirmWithoutChangingPlayback() throws Exception {
        VkAudioPlaylist selected = new VkAudioPlaylist(1, 7, "Before", "", "Description", true, true);
        model.showPlaylist(selected);
        repository.success(new VkAudioPage(0, Collections.emptyList(), 0));
        invokePlaylistDialog("renamePlaylist", selected);
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        EditText input = findInput(dialog.getWindow().getDecorView());
        char[] longTitle = new char[256];
        Arrays.fill(longTitle, 'x');
        input.setText(new String(longTitle));
        assertEquals(255, input.length());
        input.setText("   ");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(dialog.isShowing());
        assertNotNull(input.getError());
        assertEquals(0, repository.mutations);
        input.setText("After");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals(1, repository.mutations);
        assertEquals("Before", model.getPlaylist().title);
        repository.mutation.onSuccess(true);
        assertEquals("After", model.getPlaylist().title);
        assertEquals(0, playback.replacements);

        invokePlaylistDialog("confirmDeletePlaylist", model.getPlaylist());
        dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, repository.mutations);
        invokePlaylistDialog("confirmDeletePlaylist", model.getPlaylist());
        dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(2, repository.mutations);
        assertNotNull(model.getPlaylist());
        repository.mutation.onSuccess(true);
        assertNull(model.getPlaylist());
        assertEquals(0, playback.replacements);
    }

    @Test
    public void foreignPlaylistCannotOpenMutationDialog() throws Exception {
        VkAudioPlaylist other = new VkAudioPlaylist(2, 7, "Other", "", "Description", true, true);
        model.showPlaylist(other);
        android.app.Dialog previous = ShadowDialog.getLatestDialog();
        invokePlaylistDialog("renamePlaylist", other);
        invokePlaylistDialog("confirmDeletePlaylist", other);
        assertEquals(0, repository.mutations);
        assertTrue(previous == ShadowDialog.getLatestDialog());
    }

    @Test
    public void missingPermissionsAllowOwnerAndExplicitPermissionsRemainSeparate() throws Exception {
        VkAudioPlaylist missing = new VkAudioPlaylist(1, 7, "Missing", "");
        model.showPlaylist(missing);
        android.app.Dialog previous = ShadowDialog.getLatestDialog();
        invokePlaylistDialog("renamePlaylist", missing);
        assertTrue(ShadowDialog.getLatestDialog().isShowing());
        ShadowDialog.getLatestDialog().dismiss();
        invokePlaylistDialog("confirmDeletePlaylist", missing);
        assertTrue(ShadowDialog.getLatestDialog().isShowing());
        ShadowDialog.getLatestDialog().dismiss();
        assertEquals(0, repository.mutations);
        previous = ShadowDialog.getLatestDialog();

        VkAudioPlaylist editOnly = new VkAudioPlaylist(1, 8, "Edit", "", "", true, false);
        model.showPlaylist(editOnly);
        invokePlaylistDialog("confirmDeletePlaylist", editOnly);
        assertTrue(previous == ShadowDialog.getLatestDialog());
        invokePlaylistDialog("renamePlaylist", editOnly);
        assertTrue(ShadowDialog.getLatestDialog().isShowing());
        ShadowDialog.getLatestDialog().dismiss();
        VkAudioPlaylist deleteOnly = new VkAudioPlaylist(1, 9, "Delete", "", "", false, true);
        model.showPlaylist(deleteOnly);
        previous = ShadowDialog.getLatestDialog();
        invokePlaylistDialog("renamePlaylist", deleteOnly);
        assertTrue(previous == ShadowDialog.getLatestDialog());
        invokePlaylistDialog("confirmDeletePlaylist", deleteOnly);
        assertTrue(ShadowDialog.getLatestDialog().isShowing());
    }

    @Test
    public void rotationDoesNotPermitDuplicateMutationAndKeepsConfirmedResult() throws Exception {
        VkAudioPlaylist selected = new VkAudioPlaylist(1, 7, "Old", "", "Description", true, true);
        model.showPlaylist(selected);
        invokePlaylistDialog("renamePlaylist", selected);
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        findInput(dialog.getWindow().getDecorView()).setText("New");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals(1, repository.mutations);
        controller.recreate();
        activity = controller.get();
        fragment = (CatalogFragment) activity.getSupportFragmentManager().findFragmentById(R.id.content);
        android.app.Dialog previous = ShadowDialog.getLatestDialog();
        invokePlaylistDialog("confirmDeletePlaylist", selected);
        assertTrue(previous == ShadowDialog.getLatestDialog());
        assertEquals(1, repository.mutations);
        repository.mutation.onSuccess(true);
        assertEquals("New", model.getPlaylist().title);
        assertEquals(0, playback.replacements);
    }

    @Test
    public void mutationResultAfterRotationIsShownOnceByNewScreen() throws Exception {
        ShadowToast.reset();
        VkAudioPlaylist selected = new VkAudioPlaylist(1, 7, "Old", "", "", true, true);
        model.showPlaylist(selected);
        invokePlaylistDialog("renamePlaylist", selected);
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        findInput(dialog.getWindow().getDecorView()).setText("New");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();

        controller.recreate();
        activity = controller.get();
        fragment = (CatalogFragment) activity.getSupportFragmentManager().findFragmentById(R.id.content);
        repository.mutation.onError(new VkAudioError(VkAudioError.Kind.AUTH_REQUIRED));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, ShadowToast.shownToastCount());
        assertEquals(activity.getString(R.string.audio_playlist_auth_unconfirmed), ShadowToast.getTextOfLatestToast());
        assertEquals("Old", model.getPlaylist().title);
        assertFalse(model.isPlaylistMutationPending());

        controller.recreate();
        activity = controller.get();
        fragment = (CatalogFragment) activity.getSupportFragmentManager().findFragmentById(R.id.content);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, ShadowToast.shownToastCount());
    }

    @Test
    public void staleDeleteDialogCannotMutateAnotherSelection() throws Exception {
        VkAudioPlaylist selected = new VkAudioPlaylist(1, 7, "Old", "", "", true, true);
        model.showPlaylist(selected);
        invokePlaylistDialog("confirmDeletePlaylist", selected);
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        model.showPlaylist(new VkAudioPlaylist(1, 8, "New", "", "", true, true));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, repository.mutations);
    }

    private void invokePlaylistDialog(String method, VkAudioPlaylist playlist) throws Exception {
        java.lang.reflect.Method target = AudioFragment.class.getDeclaredMethod(method, VkAudioPlaylist.class);
        target.setAccessible(true);
        target.invoke(fragment, playlist);
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void missingUrlRemainsVisibleAndExplicitPlayUsesFullCatalogSnapshot() {
        VkAudioTrack first = track(1, "No URL yet", "Artist");
        VkAudioTrack second = track(2, "Second", "Artist");
        repository.success(new VkAudioPage(2, Arrays.asList(first, second), 2));
        assertEquals(2, list().getAdapter().getItemCount());
        assertEquals(0, playback.replacements);
        assertNull(first.toMediaItem().localConfiguration);

        clickRow(0);

        assertEquals(1, playback.replacements);
        assertEquals(2, playback.items.size());
        assertEquals(first.mediaId(), playback.items.get(0).mediaId);
        assertNull(playback.items.get(0).localConfiguration);
        assertEquals(0, playback.currentIndex);
        assertEquals(1, playback.prepares);
        assertTrue(playback.playWhenReady);
        assertEquals(1, CatalogFragment.opened);
    }

    @Test
    public void pageFailureKeepsRowsAndManualRetryUsesServerOffsetWithoutTouchingQueue() {
        repository.success(new VkAudioPage(5, Arrays.asList(track(1, "First", "Artist"),
                track(2, "Second", "Artist")), 3));
        root().findViewById(R.id.audio_list_load_more).performClick();
        assertEquals(3, repository.last().offset);

        repository.error(new VkAudioError(VkAudioError.Kind.NETWORK));

        assertEquals(2, list().getAdapter().getItemCount());
        assertEquals(activity.getString(R.string.audio_list_error_network), statusText());
        assertEquals(View.VISIBLE, root().findViewById(R.id.audio_list_retry).getVisibility());
        root().findViewById(R.id.audio_list_retry).performClick();
        assertEquals(3, repository.last().offset);
        repository.success(new VkAudioPage(5, Collections.singletonList(track(3, "Third", "Artist")), 2));
        assertEquals(3, list().getAdapter().getItemCount());
        assertEquals(0, playback.replacements);
        assertEquals("vk-audio:17_99", playback.items.get(0).mediaId);
        assertFalse(playback.playWhenReady);
    }

    @Test
    public void refreshAndRotationKeepExistingQueueAndDoNotFetchAgainAutomatically() {
        repository.success(new VkAudioPage(1, Collections.singletonList(track(1, "Initial", "Artist")), 1));
        model.refresh();
        assertEquals(0, repository.last().offset);
        assertEquals(1, list().getAdapter().getItemCount());
        repository.success(new VkAudioPage(1, Collections.singletonList(track(2, "Refreshed", "Artist")), 1));
        int calls = repository.calls.size();

        controller.recreate();
        activity = controller.get();
        fragment = (CatalogFragment) activity.getSupportFragmentManager().findFragmentById(R.id.content);

        assertEquals(calls, repository.calls.size());
        assertEquals(1, list().getAdapter().getItemCount());
        assertEquals(0, playback.replacements);
        assertEquals("vk-audio:17_99", playback.items.get(0).mediaId);
        assertFalse(playback.playWhenReady);
    }

    @Test
    public void rateLimitWaitsForManualRetryAndKeepsSafeErrorState() {
        repository.error(new VkAudioError(VkAudioError.Kind.RATE_LIMITED, 6, 429));
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MINUTES);
        assertEquals(1, repository.calls.size());
        assertEquals(activity.getString(R.string.audio_list_error_limit), statusText());
        assertEquals(0, playback.replacements);

        root().findViewById(R.id.audio_list_retry).performClick();
        assertEquals(2, repository.calls.size());
        repository.success(new VkAudioPage(0, Collections.emptyList(), 0));
        assertEquals(activity.getString(R.string.audio_list_empty), emptyText());
    }

    @Test
    public void partialCatalogShuffleLabelDoesNotPromiseTheWholeAccount() {
        repository.success(new VkAudioPage(5, Collections.singletonList(track(1, "First", "Artist")), 1));
        assertEquals(activity.getString(R.string.audio_list_shuffle_loaded),
                ((TextView) root().findViewById(R.id.audio_list_shuffle)).getText().toString());
        model.refresh();
        repository.success(new VkAudioPage(1, Collections.singletonList(track(1, "First", "Artist")), 1));
        assertEquals(activity.getString(R.string.audio_list_shuffle_all),
                ((TextView) root().findViewById(R.id.audio_list_shuffle)).getText().toString());
        assertEquals(0, playback.replacements);
    }

    @Test
    public void skippedServerRowsDoNotClaimTheAccountIsEmpty() {
        repository.success(new VkAudioPage(5, Collections.emptyList(), 3));
        assertEquals(activity.getString(R.string.audio_list_empty_unavailable), emptyText());
        assertEquals(View.VISIBLE, root().findViewById(R.id.audio_list_load_more).getVisibility());
        assertFalse(root().findViewById(R.id.audio_list_shuffle).isEnabled());
    }

    @Test
    public void searchingLoadedRowsDoesNotSendRequestsAndKeepsFullPlaybackSnapshot() {
        repository.success(new VkAudioPage(3, Arrays.asList(track(1, "First", "Artist"),
                track(2, "Second", "Target artist"), track(3, "Third", "Artist")), 3));
        fragment.showAudioSearch();
        EditText input = findInput(ShadowDialog.getLatestDialog().getWindow().getDecorView());
        assertNotNull(input);
        input.setText("TARGET ARTIST");
        assertEquals(1, list().getAdapter().getItemCount());
        assertEquals(1, repository.calls.size());
        ShadowDialog.getLatestDialog().dismiss();

        clickRow(0);

        assertEquals(3, playback.items.size());
        assertEquals(1, playback.currentIndex);
        assertEquals("vk-audio:17_2", playback.items.get(playback.currentIndex).mediaId);
    }

    @Test
    public void currentIndicatorFollowsIdWhenPlaybackQueueHasDifferentOrder() {
        VkAudioTrack first = track(1, "First", "Artist");
        VkAudioTrack second = track(2, "Second", "Artist");
        playback.items.clear();
        playback.items.add(second.toMediaItem());
        playback.items.add(first.toMediaItem());
        playback.currentIndex = 0;
        repository.success(new VkAudioPage(2, Arrays.asList(first, second), 2));
        layout(list());
        RecyclerView.ViewHolder firstRow = list().findViewHolderForAdapterPosition(0);
        RecyclerView.ViewHolder secondRow = list().findViewHolderForAdapterPosition(1);
        assertNotNull(firstRow);
        assertNotNull(secondRow);
        assertEquals(View.GONE, firstRow.itemView.findViewById(R.id.audio_list_current).getVisibility());
        assertEquals(View.VISIBLE, secondRow.itemView.findViewById(R.id.audio_list_current).getVisibility());
        assertEquals(0, playback.replacements);
    }

    private View root() { return fragment.requireView(); }
    private RecyclerView list() { return root().findViewById(R.id.audio_queue); }
    private String statusText() { return ((TextView) root().findViewById(R.id.audio_status)).getText().toString(); }
    private String emptyText() { return ((TextView) root().findViewById(R.id.audio_empty)).getText().toString(); }

    private void clickRow(int position) {
        layout(list());
        RecyclerView.ViewHolder row = list().findViewHolderForAdapterPosition(position);
        assertNotNull(row);
        row.itemView.performClick();
    }

    private static void layout(RecyclerView list) {
        list.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, 800, 1200);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private AudioQueueAdapter adapter(AtomicInteger clicked, AtomicInteger held) {
        return new AudioQueueAdapter(activity, new AudioQueueAdapter.Callback() {
            @Override public void onTrackClick(int index) { clicked.set(index); }
            @Override public void onTrackLongClick(View anchor, int index) { held.set(index); }
        });
    }

    private static VkAudioTrack track(long id, String title, String artist) {
        return new VkAudioTrack(id, 17, artist, title, 138, "", "", true);
    }

    private static EditText findInput(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText input = findInput(group.getChildAt(i));
                if (input != null) return input;
            }
        }
        return null;
    }

    public static class CatalogHostActivity extends AppCompatActivity {
        @Override protected void onCreate(Bundle state) {
            super.onCreate(state);
            FrameLayout container = new FrameLayout(this);
            container.setId(R.id.content);
            setContentView(container);
            if (state == null) getSupportFragmentManager().beginTransaction()
                    .replace(R.id.content, new CatalogFragment()).commitNow();
        }
    }

    public static class CatalogFragment extends AudioFragment {
        static VkAudioViewModel model;
        static RecordingPlayer playback;
        static int opened;
        @Override protected ViewModelProvider.Factory createCatalogFactory() {
            return new ViewModelProvider.Factory() {
                @Override public <T extends ViewModel> T create(Class<T> type) { return type.cast(model); }
            };
        }
        @Override protected Player playbackPlayer() { return playback.player; }
        @Override protected void openPlayer() { opened++; }
    }

    static class FakeRepository implements VkAudioRepository {
        final List<Call> calls = new ArrayList<>();
        int mutations;
        Callback<Boolean> mutation;
        @Override public Request getPlaylistAudio(VkAudioPlaylist playlist, int offset, int count, Callback<VkAudioPage> callback) {
            return getMyAudio(offset, count, callback);
        }
        @Override public Request renamePlaylist(VkAudioPlaylist playlist, String title, Callback<Boolean> callback) {
            mutations++;
            mutation = callback;
            return new Call(0, null);
        }
        @Override public Request deletePlaylist(VkAudioPlaylist playlist, Callback<Boolean> callback) {
            mutations++;
            mutation = callback;
            return new Call(0, null);
        }
        @Override public Request getMyAudio(int offset, int count, Callback<VkAudioPage> callback) {
            Call call = new Call(offset, callback);
            calls.add(call);
            return call;
        }
        @Override public Request getById(List<String> ids, Callback<List<VkAudioTrack>> callback) {
            throw new AssertionError("UI must not resolve or request play URLs while loading the library");
        }
        @Override public void close() { for (Call call : calls) call.cancel(); }
        Call last() { return calls.get(calls.size() - 1); }
        void success(VkAudioPage page) { if (!last().canceled) last().callback.onSuccess(page); }
        void error(VkAudioError error) { if (!last().canceled) last().callback.onError(error); }
    }

    static class Call implements VkAudioRepository.Request {
        final int offset;
        final VkAudioRepository.Callback<VkAudioPage> callback;
        boolean canceled;
        Call(int offset, VkAudioRepository.Callback<VkAudioPage> callback) {
            this.offset = offset;
            this.callback = callback;
        }
        @Override public void cancel() { canceled = true; }
        @Override public boolean isCanceled() { return canceled; }
    }

    static class RecordingPlayer {
        final List<MediaItem> items = new ArrayList<>();
        int currentIndex;
        int replacements;
        int prepares;
        boolean playWhenReady;
        final Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getCurrentMediaItem": return items.isEmpty() ? null : items.get(currentIndex);
                        case "getCurrentMediaItemIndex": return currentIndex;
                        case "getMediaItemCount": return items.size();
                        case "getMediaItemAt": return items.get((Integer) args[0]);
                        case "getPlayWhenReady": return playWhenReady;
                        case "setMediaItems":
                            items.clear();
                            items.addAll((List<MediaItem>) args[0]);
                            currentIndex = args.length > 1 && args[1] instanceof Integer ? (Integer) args[1] : 0;
                            replacements++;
                            return null;
                        case "prepare": prepares++; return null;
                        case "play": playWhenReady = true; return null;
                        case "pause": playWhenReady = false; return null;
                        case "isCommandAvailable": return true;
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == args[0];
                        case "toString": return "Synthetic catalog UI player";
                        default:
                            Class<?> type = method.getReturnType();
                            if (type == boolean.class) return false;
                            if (type == int.class) return 0;
                            if (type == long.class) return 0L;
                            if (type == float.class) return 0f;
                            return null;
                    }
                });
    }
}
