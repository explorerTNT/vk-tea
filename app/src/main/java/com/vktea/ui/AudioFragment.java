package com.vktea.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
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
import com.vktea.auth.AuthWebViewActivity;
import com.vktea.auth.Session;
import com.vktea.audio.AudioPlayback;
import com.vktea.audio.AudioSources;
import com.vktea.audio.AudioOfflineCache;
import com.vktea.audio.AudioUi;
import com.vktea.audio.catalog.VkAudioError;
import com.vktea.audio.catalog.VkAudioTrack;
import com.vktea.audio.catalog.VkAudioPlaylist;
import com.vktea.audio.catalog.VkAudioRepository;
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
    private static final String OFFLINE_MODE = "audio_offline_mode";
    private static final int MENU_LIBRARY = 1;
    private static final int MENU_REFRESH = 2;
    private static final int MENU_PLAYLISTS = 3;
    private static final int MENU_OFFLINE = 4;
    private static final int MENU_PLAYLIST_WEB = 5;
    private static final int MENU_PLAYLIST_RENAME = 6;
    private static final int MENU_PLAYLIST_DELETE = 7;

    private String searchQuery = "";
    private boolean connectionFailed;
    private boolean offlineMode;
    private boolean actionPending;
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
    private TextView sourceLabel;
    private TextView status;
    private TextView empty;
    private TextView retry;
    private View messageRow;
    private View emptyState;
    private View loading;
    private View loadMore;
    private AlertDialog searchDialog;
    private AlertDialog playlistDialog;

    private final Player.Listener playerListener = new Player.Listener() {
        @Override public void onEvents(@NonNull Player player, @NonNull Player.Events events) {
            render();
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            searchQuery = savedInstanceState.getString(SEARCH_QUERY, "");
            offlineMode = savedInstanceState.getBoolean(OFFLINE_MODE, false);
        }
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
        sourceLabel = view.findViewById(R.id.audio_source_label);
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
                    if (offlineMode) AudioUi.showCatalogActions(requireActivity(), anchor, libraryItems.get(index), AudioFragment.this::render);
                    else showTrackActions(anchor, libraryState.items.get(index));
                }
            }
        });
        queueAdapter.setQuery(catalog.isSearch() ? "" : searchQuery);
        list.setAdapter(queueAdapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
                if (dy > 0 && !offlineMode && libraryState != null
                        && libraryState.hasMore && !libraryState.loading && libraryState.error == null
                        && layout.findLastVisibleItemPosition() >= queueAdapter.getItemCount() - 5) {
                    catalog.loadNext();
                }
            }
        });
        refresh.setColorSchemeColors(ContextCompat.getColor(requireContext(), R.color.audio_accent));
        refresh.setOnChildScrollUpCallback((parent, child) -> list.canScrollVertically(-1));
        refresh.setOnRefreshListener(() -> {
            actionMessage = 0;
            if (offlineMode) render(); else catalog.refresh();
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
        catalog.getPlaylistMutationEvent().observe(getViewLifecycleOwner(), event -> {
            if (event == null || !event.consume()) return;
            if (event.error == null) {
                android.widget.Toast.makeText(requireContext(), R.string.audio_change_success,
                        android.widget.Toast.LENGTH_SHORT).show();
            } else if (event.error.kind == VkAudioError.Kind.NETWORK || event.error.kind == VkAudioError.Kind.HTTP
                    || event.error.kind == VkAudioError.Kind.MALFORMED_RESPONSE) {
                android.widget.Toast.makeText(requireContext(), R.string.audio_playlist_change_unconfirmed,
                        android.widget.Toast.LENGTH_LONG).show();
            } else if (event.error.kind == VkAudioError.Kind.AUTH_REQUIRED) {
                android.widget.Toast.makeText(requireContext(), R.string.audio_playlist_auth_unconfirmed,
                        android.widget.Toast.LENGTH_LONG).show();
            } else {
                android.widget.Toast.makeText(requireContext(), errorMessage(event.error),
                        android.widget.Toast.LENGTH_LONG).show();
            }
        });
        catalog.ensureLoaded();
        render();
    }

    public void showAudioMenu(View anchor) {
        if (!isAdded() || queueAdapter == null) return;
        PopupMenu menu = new PopupMenu(audioTheme(), anchor);
        menu.getMenu().add(0, MENU_LIBRARY, 0, R.string.audio_list_my_audio)
                .setCheckable(true).setChecked(!offlineMode && !catalog.isSearch() && catalog.getPlaylist() == null);
        menu.getMenu().add(0, MENU_PLAYLISTS, 1, R.string.audio_playlists);
        menu.getMenu().add(0, MENU_OFFLINE, 2, R.string.audio_offline_library);
        VkAudioPlaylist selectedPlaylist = !offlineMode ? catalog.getPlaylist() : null;
        if (selectedPlaylist != null) {
            menu.getMenu().add(0, MENU_PLAYLIST_WEB, 3, R.string.audio_playlist_open_vk);
            if (canRenamePlaylist(selectedPlaylist))
                menu.getMenu().add(0, MENU_PLAYLIST_RENAME, 4, R.string.audio_playlist_rename).setEnabled(!actionPending && !catalog.isPlaylistMutationPending());
            if (canDeletePlaylist(selectedPlaylist))
                menu.getMenu().add(0, MENU_PLAYLIST_DELETE, 5, R.string.audio_playlist_delete).setEnabled(!actionPending && !catalog.isPlaylistMutationPending());
        }
        menu.getMenu().add(0, MENU_REFRESH, 1, R.string.audio_list_refresh)
                .setEnabled(libraryState == null || !libraryState.loading);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_LIBRARY) {
                offlineMode = false;
                setSearchQuery("");
                catalog.showLibrary();
                list.scrollToPosition(0);
            } else if (item.getItemId() == MENU_PLAYLISTS) {
                showPlaylists(null);
            } else if (item.getItemId() == MENU_OFFLINE) {
                offlineMode = true;
                setSearchQuery("");
                render();
            } else if (item.getItemId() == MENU_PLAYLIST_WEB && selectedPlaylist != null) {
                startActivity(new Intent(requireContext(), AuthWebViewActivity.class)
                        .setData(Uri.parse(selectedPlaylist.webUrl())));
            } else if (item.getItemId() == MENU_PLAYLIST_RENAME && selectedPlaylist != null) {
                renamePlaylist(selectedPlaylist);
            } else if (item.getItemId() == MENU_PLAYLIST_DELETE && selectedPlaylist != null) {
                confirmDeletePlaylist(selectedPlaylist);
            } else if (item.getItemId() == MENU_REFRESH) {
                actionMessage = 0;
                if (offlineMode) render(); else catalog.refresh();
            }
            return true;
        });
        menu.show();
    }

    /** Local filtering while typing; the positive action searches VK. */
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
                .setPositiveButton(R.string.audio_search_network, (dialog, which) -> {
                    String query = input.getText().toString().trim();
                    if (!query.isEmpty()) {
                        offlineMode = false;
                        searchQuery = query;
                        queueAdapter.setQuery("");
                        catalog.search(query);
                        list.scrollToPosition(0);
                    }
                })
                .setNegativeButton(R.string.audio_list_search_clear, (dialog, which) -> {
                    setSearchQuery("");
                    if (catalog.isSearch()) catalog.showLibrary();
                })
                .create();
        searchDialog.setOnDismissListener(dialog -> {
            if (queueAdapter != null && catalog.isSearch()) {
                searchQuery = catalog.getQuery();
                queueAdapter.setQuery("");
                render();
            }
        });
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
    public void onResume() {
        super.onResume();
        if (offlineMode) render();
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
        state.putBoolean(OFFLINE_MODE, offlineMode);
    }

    @Override
    public void onDestroyView() {
        if (searchDialog != null) searchDialog.dismiss();
        searchDialog = null;
        if (playlistDialog != null) playlistDialog.dismiss();
        playlistDialog = null;
        queueAdapter = null;
        list = null;
        refresh = null;
        shuffle = null;
        sourceLabel = null;
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
        if (offlineMode) {
            libraryItems.addAll(AudioOfflineCache.list(requireContext()));
        } else {
            AudioSources.rememberTracks(requireContext(), libraryState.items);
            for (VkAudioTrack track : libraryState.items) libraryItems.add(track.toMediaItem());
        }
        sourceLabel.setText(offlineMode ? getString(R.string.audio_offline_library)
                : catalog.isSearch() ? getString(R.string.audio_search_results, catalog.getQuery())
                : catalog.getPlaylist() != null ? catalog.getPlaylist().title : getString(R.string.audio_list_my_audio));
        Player player = playbackPlayer();
        MediaItem current = player == null ? null : player.getCurrentMediaItem();
        queueAdapter.submit(libraryItems, current == null ? null : current.mediaId);
        boolean noMatches = queueAdapter.getItemCount() == 0;
        boolean hasRows = !libraryItems.isEmpty();
        boolean initialLoading = !offlineMode && !hasRows && (libraryState.loading
                || !libraryState.loaded && libraryState.error == null);
        boolean hasPlayableRows = false;
        for (MediaItem item : libraryItems) {
            if (!Boolean.FALSE.equals(item.mediaMetadata.isPlayable)) hasPlayableRows = true;
        }
        shuffle.setText(!offlineMode && libraryState.hasMore ? R.string.audio_list_shuffle_loaded : R.string.audio_list_shuffle_all);
        shuffle.setEnabled(player != null && hasPlayableRows);
        refresh.setRefreshing(!offlineMode && libraryState.loading && libraryState.refreshing && hasRows);
        loading.setVisibility(initialLoading ? View.VISIBLE : View.GONE);
        emptyState.setVisibility(noMatches && (offlineMode || libraryState.error == null) ? View.VISIBLE : View.GONE);
        empty.setText(initialLoading ? R.string.audio_list_loading
                : offlineMode ? R.string.audio_offline_empty
                : hasRows || !searchQuery.trim().isEmpty() || catalog.getPlaylist() != null ? R.string.audio_list_no_results
                : libraryState.totalCount > 0 ? R.string.audio_list_empty_unavailable : R.string.audio_list_empty);
        list.setVisibility(noMatches ? View.GONE : View.VISIBLE);
        loadMore.setVisibility(!offlineMode && libraryState.hasMore && !libraryState.loading && libraryState.error == null
                ? View.VISIBLE : View.GONE);
        retry.setVisibility(!offlineMode && libraryState.error != null && !libraryState.loading ? View.VISIBLE : View.GONE);
        int message = !offlineMode && libraryState.error != null ? errorMessage(libraryState.error)
                : actionMessage != 0 ? actionMessage
                : !offlineMode && libraryState.loading && hasRows && !libraryState.refreshing ? R.string.audio_list_loading
                : connectionFailed && player == null && hasRows ? R.string.audio_connection_failed : 0;
        if (message != 0) status.setText(message);
        messageRow.setVisibility(message != 0 ? View.VISIBLE : View.GONE);
    }

    private void showTrackActions(View anchor, VkAudioTrack track) {
        PopupMenu popup = new PopupMenu(audioTheme(), anchor);
        boolean library = !catalog.isSearch() && catalog.getPlaylist() == null;
        if (library) popup.getMenu().add(0, 1, 0, R.string.audio_remove_library);
        else popup.getMenu().add(0, 2, 0, R.string.audio_add_library);
        popup.getMenu().add(0, 3, 1, R.string.audio_add_playlist);
        if (catalog.getPlaylist() != null) popup.getMenu().add(0, 4, 2, R.string.audio_remove_playlist);
        popup.getMenu().add(0, 5, 3, R.string.audio_more_actions);
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    new AlertDialog.Builder(audioTheme()).setMessage(R.string.audio_remove_library_confirm)
                            .setPositiveButton(R.string.audio_remove_library, (d, w) -> mutateLibrary(track, false))
                            .setNegativeButton(android.R.string.cancel, null).show();
                    return true;
                case 2: mutateLibrary(track, true); return true;
                case 3: showPlaylists(track); return true;
                case 4: mutatePlaylist(catalog.getPlaylist(), track, false); return true;
                case 5: AudioUi.showCatalogActions(requireActivity(), anchor, track.toMediaItem()); return true;
                default: return false;
            }
        });
        popup.show();
    }

    private void showPlaylists(@Nullable VkAudioTrack track) {
        if (actionPending) return;
        actionPending = true;
        catalog.getPlaylists(new VkAudioRepository.Callback<List<VkAudioPlaylist>>() {
            @Override public void onSuccess(List<VkAudioPlaylist> playlists) {
                actionPending = false;
                if (!isAdded() || getView() == null) return;
                String[] titles = new String[playlists.size()];
                for (int i = 0; i < playlists.size(); i++) titles[i] = playlists.get(i).title;
                new AlertDialog.Builder(audioTheme()).setTitle(track == null ? R.string.audio_playlists : R.string.audio_add_playlist)
                        .setItems(titles, (dialog, which) -> {
                            VkAudioPlaylist selected = playlists.get(which);
                            if (track != null) mutatePlaylist(selected, track, true);
                            else {
                                offlineMode = false;
                                setSearchQuery("");
                                catalog.showPlaylist(selected);
                                list.scrollToPosition(0);
                            }
                        }).setPositiveButton(R.string.audio_create_playlist, (dialog, which) -> createPlaylist())
                        .setNegativeButton(android.R.string.cancel, null).show();
            }
            @Override public void onError(VkAudioError error) { actionFailed(error); }
        });
    }

    private void createPlaylist() {
        EditText input = new EditText(audioTheme());
        input.setSingleLine(true);
        new AlertDialog.Builder(audioTheme()).setTitle(R.string.audio_create_playlist).setView(input)
                .setPositiveButton(R.string.audio_create_playlist, (dialog, which) -> {
                    String title = input.getText().toString().trim();
                    if (title.isEmpty() || actionPending) return;
                    actionPending = true;
                    catalog.createPlaylist(title, mutationCallback(false));
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private boolean isCurrentOwnPlaylist(VkAudioPlaylist selected) {
        VkAudioPlaylist current = catalog.getPlaylist();
        return isAdded() && getView() != null && !offlineMode && selected != null && current == selected
                && Long.toString(selected.ownerId).equals(Session.getUserId(requireContext()));
    }

    private boolean canRenamePlaylist(VkAudioPlaylist selected) {
        return isCurrentOwnPlaylist(selected) && !Boolean.FALSE.equals(selected.canEdit);
    }

    private boolean canDeletePlaylist(VkAudioPlaylist selected) {
        return isCurrentOwnPlaylist(selected) && !Boolean.FALSE.equals(selected.canDelete);
    }

    private void renamePlaylist(VkAudioPlaylist selected) {
        if (actionPending || catalog.isPlaylistMutationPending() || !canRenamePlaylist(selected)) return;
        EditText input = new EditText(audioTheme());
        input.setSingleLine(true);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(255)});
        input.setText(selected.title);
        input.setSelection(input.length());
        AlertDialog dialog = new AlertDialog.Builder(audioTheme()).setTitle(R.string.audio_playlist_rename)
                .setView(input).setPositiveButton(R.string.audio_playlist_save, null)
                .setNegativeButton(android.R.string.cancel, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String title = input.getText().toString().trim();
            if (title.isEmpty()) {
                input.setError(getString(R.string.audio_playlist_title_required));
                return;
            }
            if (actionPending || catalog.isPlaylistMutationPending() || !canRenamePlaylist(selected)) return;
            if (!title.equals(selected.title)) {
                catalog.renamePlaylist(selected, title);
            }
            dialog.dismiss();
        }));
        playlistDialog = dialog;
        dialog.show();
    }

    private void confirmDeletePlaylist(VkAudioPlaylist selected) {
        if (actionPending || catalog.isPlaylistMutationPending() || !canDeletePlaylist(selected)) return;
        playlistDialog = new AlertDialog.Builder(audioTheme())
                .setTitle(R.string.audio_playlist_delete)
                .setMessage(getString(R.string.audio_playlist_delete_confirm, selected.title))
                .setPositiveButton(R.string.audio_playlist_delete, (dialog, which) -> {
                    if (actionPending || catalog.isPlaylistMutationPending() || !canDeletePlaylist(selected)) return;
                    catalog.deletePlaylist(selected);
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void mutateLibrary(VkAudioTrack track, boolean add) {
        if (actionPending) return;
        actionPending = true;
        catalog.changeLibrary(track, add, mutationCallback(!add));
    }

    private void mutatePlaylist(VkAudioPlaylist playlist, VkAudioTrack track, boolean add) {
        if (actionPending || playlist == null) return;
        actionPending = true;
        catalog.changePlaylist(playlist, track, add, mutationCallback(!add));
    }

    private VkAudioRepository.Callback<Boolean> mutationCallback(boolean refreshAfter) {
        return new VkAudioRepository.Callback<Boolean>() {
            @Override public void onSuccess(Boolean result) {
                actionPending = false;
                if (!isAdded() || getView() == null) return;
                android.widget.Toast.makeText(requireContext(), R.string.audio_change_success, android.widget.Toast.LENGTH_SHORT).show();
                if (refreshAfter) catalog.refresh();
            }
            @Override public void onError(VkAudioError error) { actionFailed(error); }
        };
    }

    private void actionFailed(VkAudioError error) {
        actionPending = false;
        if (!isAdded() || getView() == null) return;
        android.widget.Toast.makeText(requireContext(), errorMessage(error), android.widget.Toast.LENGTH_LONG).show();
    }

    private static int errorMessage(VkAudioError error) {
        switch (error.kind) {
            case AUTH_REQUIRED: return R.string.audio_list_error_auth;
            case ACCESS_DENIED: return R.string.audio_list_error_access;
            case RATE_LIMITED: return R.string.audio_list_error_limit;
            case NETWORK: return R.string.audio_list_error_network;
            case MALFORMED_RESPONSE: return R.string.audio_list_error_response;
            case PLAYLIST_VISIBILITY_UNKNOWN: return R.string.audio_playlist_visibility_unknown;
            default: return R.string.audio_list_error_generic;
        }
    }
}
