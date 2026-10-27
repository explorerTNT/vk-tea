package com.vktea.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.common.util.concurrent.ListenableFuture;
import com.vktea.R;
import com.vktea.audio.AudioPlayback;
import com.vktea.audio.AudioUi;
import com.vktea.audio.catalog.VkAudioError;
import com.vktea.audio.catalog.VkAudioTrack;
import com.vktea.audio.catalog.VkAudioViewModel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;

/** Account audio catalog. Loading library pages never changes the playback queue. */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
public class AudioFragment extends Fragment {
    private static final String SEARCH_QUERY = "audio_search_query";
    private static final int MENU_LIBRARY = 1;
    private static final int MENU_REFRESH = 2;

    private String searchQuery = "";
    private boolean connectionFailed;
    private int actionMessage;
    private VkAudioViewModel catalog;
    private VkAudioViewModel.State libraryState;
    private final List<MediaItem> libraryItems = new ArrayList<>();
    private ListenableFuture<MediaController> controllerFuture;
    private MediaController controller;
    private AudioQueueAdapter queueAdapter;
    private RecyclerView list;
    private SwipeRefreshLayout refresh;
    private TextView shuffle;
    private TextView status;
    private TextView empty;
    private TextView retry;
    private View messageRow;
    private View emptyState;
    private View loading;
    private View loadMore;
    private AlertDialog searchDialog;

    private final Player.Listener playerListener = new Player.Listener() {
        @Override public void onEvents(@NonNull Player player, @NonNull Player.Events events) {
            render();
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) searchQuery = savedInstanceState.getString(SEARCH_QUERY, "");
        catalog = new ViewModelProvider(this, createCatalogFactory()).get(VkAudioViewModel.class);
        libraryState = catalog.getCurrentState();
    }

    /** Allows offline tests to provide a repository without changing production transport. */
    protected ViewModelProvider.Factory createCatalogFactory() {
        return new VkAudioViewModel.Factory(requireContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_audio, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        shuffle = view.findViewById(R.id.audio_list_shuffle);
        status = view.findViewById(R.id.audio_status);
        empty = view.findViewById(R.id.audio_empty);
        emptyState = view.findViewById(R.id.audio_list_empty_state);
        messageRow = view.findViewById(R.id.audio_list_message_row);
        retry = view.findViewById(R.id.audio_list_retry);
        loading = view.findViewById(R.id.audio_list_loading);
        loadMore = view.findViewById(R.id.audio_list_load_more);
        refresh = view.findViewById(R.id.audio_list_refresh);
        list = view.findViewById(R.id.audio_queue);
        LinearLayoutManager layout = new LinearLayoutManager(requireContext());
        list.setLayoutManager(layout);
        queueAdapter = new AudioQueueAdapter(requireContext(), new AudioQueueAdapter.Callback() {
            @Override public void onTrackClick(int index) { playLibrary(index, false); }
            @Override public void onTrackLongClick(View anchor, int index) {
                if (index >= 0 && index < libraryItems.size()) {
                    AudioUi.showCatalogActions(requireActivity(), anchor, libraryItems.get(index));
                }
            }
        });
        queueAdapter.setQuery(searchQuery);
        list.setAdapter(queueAdapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
                if (dy > 0 && searchQuery.trim().isEmpty() && libraryState != null
                        && libraryState.hasMore && !libraryState.loading && libraryState.error == null
                        && layout.findLastVisibleItemPosition() >= queueAdapter.getItemCount() - 5) {
                    catalog.loadNext();
                }
            }
        });
        refresh.setColorSchemeColors(com.vktea.ui.TeaColors.get(requireContext(), R.color.audio_accent));
        refresh.setOnChildScrollUpCallback((parent, child) -> list.canScrollVertically(-1));
        refresh.setOnRefreshListener(() -> {
            actionMessage = 0;
            catalog.refresh();
        });
        retry.setOnClickListener(v -> catalog.retry());
        loadMore.setOnClickListener(v -> catalog.loadNext());
        shuffle.setOnClickListener(v -> {
            if (!libraryItems.isEmpty()) {
                List<Integer> available = new ArrayList<>();
                for (int i = 0; i < libraryItems.size(); i++) {
                    if (!Boolean.FALSE.equals(libraryItems.get(i).mediaMetadata.isPlayable)) available.add(i);
                }
                if (!available.isEmpty()) {
                    playLibrary(available.get(ThreadLocalRandom.current().nextInt(available.size())), true);
                }
            }
        });
        catalog.getState().observe(getViewLifecycleOwner(), state -> {
            libraryState = state;
            render();
        });
        catalog.ensureLoaded();
        render();
    }

    public void showAudioMenu(View anchor) {
        if (!isAdded() || queueAdapter == null) return;
        PopupMenu menu = new PopupMenu(audioTheme(), anchor);
        menu.getMenu().add(0, MENU_LIBRARY, 0, R.string.audio_list_my_audio)
                .setCheckable(true).setChecked(true);
        menu.getMenu().add(0, MENU_REFRESH, 1, R.string.audio_list_refresh)
                .setEnabled(libraryState == null || !libraryState.loading);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_LIBRARY) {
                setSearchQuery("");
                list.scrollToPosition(0);
            } else if (item.getItemId() == MENU_REFRESH) {
                actionMessage = 0;
                catalog.refresh();
            }
            return true;
        });
        menu.show();
    }

    /** Filters only already loaded account rows; this is not a server-wide audio search. */
    public void showAudioSearch() {
        if (!isAdded() || queueAdapter == null || searchDialog != null && searchDialog.isShowing()) return;
        EditText input = new EditText(audioTheme());
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding / 2, padding, padding / 2);
        input.setSingleLine(true);
        input.setHint(R.string.audio_list_search_hint);
        input.setText(searchQuery);
        input.setSelection(input.length());
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                setSearchQuery(s.toString());
            }
            @Override public void afterTextChanged(Editable editable) {}
        });
        searchDialog = new AlertDialog.Builder(audioTheme())
                .setTitle(R.string.audio_list_search_title).setView(input)
                .setPositiveButton(R.string.audio_list_search_done, null)
                .setNegativeButton(R.string.audio_list_search_clear, (dialog, which) -> setSearchQuery(""))
                .create();
        searchDialog.show();
        input.requestFocus();
    }

    private Context audioTheme() {
        return new android.view.ContextThemeWrapper(requireContext(), R.style.Theme_VKTea_AudioPlayer);
    }

    private void setSearchQuery(String query) {
        searchQuery = query;
        if (queueAdapter != null) {
            queueAdapter.setQuery(query);
            render();
        }
    }

    @Nullable
    protected Player playbackPlayer() {
        return controller != null && controller.isConnected() ? controller : null;
    }

    protected void openPlayer() {
        AudioUi.openPlayer(requireContext());
    }

    private void playLibrary(int index, boolean shuffled) {
        if (index < 0 || index >= libraryItems.size()) return;
        if (Boolean.FALSE.equals(libraryItems.get(index).mediaMetadata.isPlayable)) {
            actionMessage = R.string.audio_list_unavailable;
            render();
            return;
        }
        Player player = playbackPlayer();
        if (player == null) {
            connectionFailed = true;
            render();
            return;
        }
        // Only an explicit play action changes the service queue. Paging and refresh
        // leave an existing queue, position and pause state untouched.
        List<MediaItem> snapshot = new ArrayList<>(libraryItems);
        actionMessage = 0;
        player.setMediaItems(snapshot, index, 0);
        player.setShuffleModeEnabled(shuffled);
        player.prepare();
        player.play();
        openPlayer();
    }

    @Override
    public void onStart() {
        super.onStart();
        connectionFailed = false;
        ListenableFuture<MediaController> future = AudioPlayback.connect(requireContext(),
                new MediaController.Listener() {
                    @Override public void onDisconnected(@NonNull MediaController disconnected) {
                        if (controller == disconnected) {
                            disconnected.removeListener(playerListener);
                            controller = null;
                            connectionFailed = true;
                            render();
                        }
                    }
                });
        controllerFuture = future;
        future.addListener(() -> {
            if (controllerFuture != future) return;
            try {
                controller = future.get();
                controller.addListener(playerListener);
            } catch (ExecutionException | CancellationException ignored) {
                connectionFailed = true;
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                connectionFailed = true;
            }
            render();
        }, ContextCompat.getMainExecutor(requireContext()));
        render();
    }

    @Override
    public void onStop() {
        if (controller != null) controller.removeListener(playerListener);
        controller = null;
        if (controllerFuture != null) {
            ListenableFuture<MediaController> future = controllerFuture;
            controllerFuture = null;
            MediaController.releaseFuture(future);
        }
        super.onStop();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        state.putString(SEARCH_QUERY, searchQuery);
    }

    @Override
    public void onDestroyView() {
        if (searchDialog != null) searchDialog.dismiss();
        searchDialog = null;
        queueAdapter = null;
        list = null;
        refresh = null;
        shuffle = null;
        status = null;
        empty = null;
        emptyState = null;
        messageRow = null;
        retry = null;
        loading = null;
        loadMore = null;
        super.onDestroyView();
    }

    private void render() {
        if (queueAdapter == null || libraryState == null) return;
        libraryItems.clear();
        for (VkAudioTrack track : libraryState.items) libraryItems.add(track.toMediaItem());
        Player player = playbackPlayer();
        MediaItem current = player == null ? null : player.getCurrentMediaItem();
        queueAdapter.submit(libraryItems, current == null ? null : current.mediaId);
        boolean noMatches = queueAdapter.getItemCount() == 0;
        boolean hasRows = !libraryItems.isEmpty();
        boolean initialLoading = !hasRows && (libraryState.loading
                || !libraryState.loaded && libraryState.error == null);
        boolean hasPlayableRows = false;
        for (MediaItem item : libraryItems) {
            if (!Boolean.FALSE.equals(item.mediaMetadata.isPlayable)) hasPlayableRows = true;
        }
        shuffle.setText(libraryState.hasMore ? R.string.audio_list_shuffle_loaded : R.string.audio_list_shuffle_all);
        shuffle.setEnabled(player != null && hasPlayableRows);
        refresh.setRefreshing(libraryState.loading && libraryState.refreshing && hasRows);
        loading.setVisibility(initialLoading ? View.VISIBLE : View.GONE);
        emptyState.setVisibility(noMatches && libraryState.error == null ? View.VISIBLE : View.GONE);
        empty.setText(initialLoading ? R.string.audio_list_loading
                : hasRows || !searchQuery.trim().isEmpty() ? R.string.audio_list_no_results
                : libraryState.totalCount > 0 ? R.string.audio_list_empty_unavailable : R.string.audio_list_empty);
        list.setVisibility(noMatches ? View.GONE : View.VISIBLE);
        loadMore.setVisibility(libraryState.hasMore && !libraryState.loading && libraryState.error == null
                ? View.VISIBLE : View.GONE);
        retry.setVisibility(libraryState.error != null && !libraryState.loading ? View.VISIBLE : View.GONE);
        int message = libraryState.error != null ? errorMessage(libraryState.error)
                : actionMessage != 0 ? actionMessage
                : libraryState.loading && hasRows && !libraryState.refreshing ? R.string.audio_list_loading
                : connectionFailed && player == null && hasRows ? R.string.audio_connection_failed : 0;
        if (message != 0) status.setText(message);
        messageRow.setVisibility(message != 0 ? View.VISIBLE : View.GONE);
    }

    private static int errorMessage(VkAudioError error) {
        switch (error.kind) {
            case AUTH_REQUIRED: return R.string.audio_list_error_auth;
            case ACCESS_DENIED: return R.string.audio_list_error_access;
            case RATE_LIMITED: return R.string.audio_list_error_limit;
            case NETWORK: return R.string.audio_list_error_network;
            case MALFORMED_RESPONSE: return R.string.audio_list_error_response;
            default: return R.string.audio_list_error_generic;
        }
    }
}
