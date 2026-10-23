package com.vktea.ui;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
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
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.common.util.concurrent.ListenableFuture;
import com.vktea.R;
import com.vktea.audio.AudioPlayback;
import com.vktea.audio.AudioUi;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;

/** Local document library backed by the service's queue; no catalogue is fabricated. */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
public class AudioFragment extends Fragment {
    private static final String PENDING_FILES = "audio_pending_files";
    private static final String SEARCH_QUERY = "audio_search_query";
    private static final int MENU_LIBRARY = 1;
    private static final int MENU_IMPORT = 2;
    private static final int MENU_CLEAR = 3;

    private final ExecutorService documentExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ArrayList<Uri> pendingFiles = new ArrayList<>();
    private boolean loadingFiles;
    private boolean connectionFailed;
    private boolean filesFailed;
    private String searchQuery = "";
    private ListenableFuture<MediaController> controllerFuture;
    private MediaController controller;
    private AudioQueueAdapter queueAdapter;
    private RecyclerView queue;
    private TextView shuffle;
    private TextView status;
    private TextView empty;
    private View emptyState;
    private TextView importLink;
    private AlertDialog searchDialog;

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onEvents(@NonNull Player player, @NonNull Player.Events events) {
            render();
        }
    };

    private final ActivityResultLauncher<String[]> openFiles = registerForActivityResult(
            new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                if (!uris.isEmpty()) {
                    pendingFiles.clear();
                    pendingFiles.addAll(uris);
                    filesFailed = false;
                    loadPendingFiles();
                    render();
                }
            });

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            ArrayList<Uri> saved = savedInstanceState.getParcelableArrayList(PENDING_FILES);
            if (saved != null) {
                pendingFiles.addAll(saved);
            }
            searchQuery = savedInstanceState.getString(SEARCH_QUERY, "");
        }
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
        importLink = view.findViewById(R.id.audio_list_import);
        queue = view.findViewById(R.id.audio_queue);
        queue.setLayoutManager(new LinearLayoutManager(requireContext()));
        queueAdapter = new AudioQueueAdapter(requireContext(), new AudioQueueAdapter.Callback() {
            @Override
            public void onTrackClick(int index) {
                if (connected() && index < controller.getMediaItemCount()) {
                    controller.seekToDefaultPosition(index);
                    controller.prepare();
                    controller.play();
                    AudioUi.openPlayer(requireContext());
                }
            }

            @Override
            public void onTrackLongClick(View anchor, int index) {
                if (connected() && index < controller.getMediaItemCount()) {
                    AudioUi.showActions(requireActivity(), anchor, controller, index);
                }
            }
        });
        queueAdapter.setQuery(searchQuery);
        queue.setAdapter(queueAdapter);
        shuffle.setOnClickListener(v -> shuffleAll());
        importLink.setOnClickListener(v -> openDocuments());
        render();
    }

    /** The toolbar title opens this menu; importing stays available from an empty library. */
    public void showAudioMenu(View anchor) {
        if (!isAdded() || queueAdapter == null) {
            return;
        }
        PopupMenu menu = new PopupMenu(new android.view.ContextThemeWrapper(requireContext(),
                R.style.Theme_VKTea_AudioPlayer), anchor);
        menu.getMenu().add(0, MENU_LIBRARY, 0, R.string.audio_list_my_audio)
                .setCheckable(true).setChecked(true);
        menu.getMenu().add(0, MENU_IMPORT, 1, R.string.audio_list_add_files)
                .setEnabled(!loadingFiles);
        menu.getMenu().add(0, MENU_CLEAR, 2, R.string.audio_list_clear_queue)
                .setEnabled(connected() && controller.getMediaItemCount() > 0 && !loadingFiles);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_IMPORT) {
                openDocuments();
            } else if (item.getItemId() == MENU_CLEAR && connected()) {
                controller.stop();
                controller.clearMediaItems();
                filesFailed = false;
                setSearchQuery("");
            } else if (item.getItemId() == MENU_LIBRARY) {
                setSearchQuery("");
                queue.scrollToPosition(0);
            }
            return true;
        });
        menu.show();
    }

    /** Searches only the existing local queue and leaves its playback order intact. */
    public void showAudioSearch() {
        if (!isAdded() || queueAdapter == null) {
            return;
        }
        if (searchDialog != null && searchDialog.isShowing()) {
            return;
        }
        Context audioTheme = new android.view.ContextThemeWrapper(requireContext(), R.style.Theme_VKTea_AudioPlayer);
        EditText input = new EditText(audioTheme);
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
        searchDialog = new AlertDialog.Builder(audioTheme)
                .setTitle(R.string.audio_list_search_title)
                .setView(input)
                .setPositiveButton(R.string.audio_list_search_done, null)
                .setNegativeButton(R.string.audio_list_search_clear, (dialog, which) -> setSearchQuery(""))
                .create();
        searchDialog.show();
        input.requestFocus();
        if (searchDialog.getWindow() != null) {
            searchDialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
    }

    private void setSearchQuery(String query) {
        searchQuery = query;
        if (queueAdapter != null) {
            queueAdapter.setQuery(query);
            render();
        }
    }

    private void openDocuments() {
        if (!loadingFiles && isAdded()) {
            openFiles.launch(new String[]{"audio/*"});
        }
    }

    private void shuffleAll() {
        if (!connected() || controller.getMediaItemCount() == 0) {
            return;
        }
        controller.setShuffleModeEnabled(true);
        controller.seekToDefaultPosition(ThreadLocalRandom.current().nextInt(controller.getMediaItemCount()));
        controller.prepare();
        controller.play();
    }

    @Override
    public void onStart() {
        super.onStart();
        connectionFailed = false;
        ListenableFuture<MediaController> future = AudioPlayback.connect(requireContext(),
                new MediaController.Listener() {
                    @Override
                    public void onDisconnected(@NonNull MediaController disconnected) {
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
            if (controllerFuture != future) {
                return;
            }
            try {
                controller = future.get();
                controller.addListener(playerListener);
                loadPendingFiles();
            } catch (ExecutionException | CancellationException e) {
                connectionFailed = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                connectionFailed = true;
            }
            render();
        }, ContextCompat.getMainExecutor(requireContext()));
        render();
    }

    @Override
    public void onStop() {
        if (controller != null) {
            controller.removeListener(playerListener);
            controller = null;
        }
        if (controllerFuture != null) {
            ListenableFuture<MediaController> future = controllerFuture;
            controllerFuture = null;
            MediaController.releaseFuture(future);
        }
        super.onStop();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelableArrayList(PENDING_FILES, new ArrayList<>(pendingFiles));
        outState.putString(SEARCH_QUERY, searchQuery);
    }

    @Override
    public void onDestroyView() {
        if (searchDialog != null) {
            searchDialog.dismiss();
            searchDialog = null;
        }
        queue = null;
        queueAdapter = null;
        shuffle = null;
        status = null;
        empty = null;
        emptyState = null;
        importLink = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        documentExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private boolean connected() {
        return controller != null && controller.isConnected();
    }

    private void loadPendingFiles() {
        if (!connected() || pendingFiles.isEmpty() || loadingFiles || !isAdded()) {
            return;
        }
        loadingFiles = true;
        ArrayList<Uri> selection = new ArrayList<>(pendingFiles);
        Context context = requireContext().getApplicationContext();
        render();
        try {
            documentExecutor.execute(() -> {
                List<MediaItem> items = new ArrayList<>();
                for (Uri uri : selection) {
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    MediaItem item = readDocument(context, uri);
                    if (item != null) {
                        items.add(item);
                    }
                }
                mainHandler.post(() -> {
                    loadingFiles = false;
                    if (!isAdded() || !connected()) {
                        return;
                    }
                    if (!pendingFiles.equals(selection)) {
                        loadPendingFiles();
                        return;
                    }
                    pendingFiles.clear();
                    filesFailed = items.isEmpty();
                    if (!items.isEmpty()) {
                        HashSet<String> known = new HashSet<>();
                        for (int i = 0; i < controller.getMediaItemCount(); i++) {
                            known.add(controller.getMediaItemAt(i).mediaId);
                        }
                        items.removeIf(item -> !known.add(item.mediaId));
                        boolean startPlayback = controller.getMediaItemCount() == 0;
                        if (!items.isEmpty()) controller.addMediaItems(items);
                        if (startPlayback && !items.isEmpty()) {
                            controller.prepare();
                            controller.play();
                        }
                    }
                    render();
                });
            });
        } catch (RejectedExecutionException ignored) {
            loadingFiles = false;
            filesFailed = true;
            render();
        }
    }

    /** Called only on the document worker; provider queries and tag reads may block. */
    @Nullable
    static MediaItem readDocument(Context context, Uri uri) {
        if (!"content".equals(uri.getScheme())) {
            return null;
        }
        String title = context.getString(R.string.audio_untitled);
        String artist = context.getString(R.string.audio_local_file);
        long fileSize = 0;
        long duration = 0;
        long bitrate = 0;
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    String name = cursor.getString(nameIndex);
                    if (name != null && !name.trim().isEmpty()) {
                        title = name;
                    }
                }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    fileSize = Math.max(0, cursor.getLong(sizeIndex));
                }
            }
        } catch (RuntimeException ignored) {
            // Playback reports inaccessible content without revealing its URI.
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            String taggedTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            String taggedArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            if (taggedTitle != null && !taggedTitle.trim().isEmpty()) {
                title = taggedTitle.trim();
            }
            if (taggedArtist != null && !taggedArtist.trim().isEmpty()) {
                artist = taggedArtist.trim();
            }
            duration = positiveNumber(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            bitrate = positiveNumber(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
        } catch (RuntimeException ignored) {
            // Missing tags still yield a playable item with its document filename.
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // Release failures do not expose provider details to UI or logs.
            }
        }
        Bundle extras = new Bundle();
        if (duration > 0) extras.putLong(AudioUi.EXTRA_DURATION_MS, duration);
        if (bitrate > 0) extras.putLong(AudioUi.EXTRA_BITRATE, bitrate);
        if (fileSize > 0) extras.putLong(AudioUi.EXTRA_FILE_SIZE, fileSize);
        return new MediaItem.Builder().setMediaId(AudioUi.mediaId(uri)).setUri(uri)
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(title).setArtist(artist)
                        .setExtras(extras).build()).build();
    }

    private static long positiveNumber(String value) {
        try {
            return value == null ? 0 : Math.max(0, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void render() {
        if (status == null || queueAdapter == null) {
            return;
        }
        boolean connected = connected();
        List<MediaItem> items = new ArrayList<>();
        if (connected) {
            for (int i = 0; i < controller.getMediaItemCount(); i++) {
                items.add(controller.getMediaItemAt(i));
            }
        }
        boolean hasItems = !items.isEmpty();
        queueAdapter.submit(items, hasItems ? controller.getCurrentMediaItemIndex() : -1);
        shuffle.setEnabled(connected && hasItems && !loadingFiles);
        boolean noResults = queueAdapter.getItemCount() == 0;
        emptyState.setVisibility(noResults && !loadingFiles ? View.VISIBLE : View.GONE);
        empty.setText(hasItems || !searchQuery.trim().isEmpty()
                ? R.string.audio_list_no_results : R.string.audio_list_empty);
        importLink.setVisibility(!hasItems && searchQuery.trim().isEmpty() ? View.VISIBLE : View.GONE);
        importLink.setEnabled(!loadingFiles);
        queue.setVisibility(noResults ? View.GONE : View.VISIBLE);
        if (connectionFailed) {
            status.setText(R.string.audio_connection_failed);
        } else if (!connected) {
            status.setText(R.string.audio_connecting);
        } else if (loadingFiles) {
            status.setText(R.string.audio_loading);
        } else if (filesFailed) {
            status.setText(R.string.audio_files_failed);
        } else if (controller.getPlayerError() != null) {
            status.setText(R.string.audio_playback_failed);
        } else {
            status.setText("");
        }
        status.setVisibility(status.length() == 0 ? View.GONE : View.VISIBLE);
    }
}
