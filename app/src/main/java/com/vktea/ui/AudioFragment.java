package com.vktea.ui;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.ui.PlayerControlView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.common.util.concurrent.ListenableFuture;
import com.vktea.R;
import com.vktea.audio.AudioPlayback;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A usable entry point for the playback core while the VK audio catalogue is pending. */
@OptIn(markerClass = UnstableApi.class)
public class AudioFragment extends Fragment {
    private static final String PENDING_FILES = "audio_pending_files";
    private final ExecutorService documentExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ArrayList<Uri> pendingFiles = new ArrayList<>();
    private boolean loadingFiles;
    private boolean connectionFailed;
    private boolean filesFailed;
    private ListenableFuture<MediaController> controllerFuture;
    private MediaController controller;
    private PlayerControlView controls;
    private AudioQueueAdapter queueAdapter;
    private Button open;
    private Button clear;
    private TextView status;
    private TextView empty;

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
        controls = view.findViewById(R.id.audio_controls);
        open = view.findViewById(R.id.audio_open);
        clear = view.findViewById(R.id.audio_clear);
        status = view.findViewById(R.id.audio_status);
        empty = view.findViewById(R.id.audio_empty);
        RecyclerView queue = view.findViewById(R.id.audio_queue);
        queue.setLayoutManager(new LinearLayoutManager(requireContext()));
        queueAdapter = new AudioQueueAdapter(index -> {
            if (controller != null) {
                controller.seekToDefaultPosition(index);
                controller.prepare();
                controller.play();
            }
        });
        queue.setAdapter(queueAdapter);
        open.setOnClickListener(v -> openFiles.launch(new String[]{"audio/*"}));
        clear.setOnClickListener(v -> {
            if (controller != null) {
                controller.stop();
                controller.clearMediaItems();
                filesFailed = false;
                render();
            }
        });
        render();
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
                            controller.removeListener(playerListener);
                            controller = null;
                            controls.setPlayer(null);
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
                controls.setPlayer(controller);
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
        if (controls != null) {
            controls.setPlayer(null);
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
    }

    @Override
    public void onDestroyView() {
        controls = null;
        queueAdapter = null;
        open = null;
        clear = null;
        status = null;
        empty = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        documentExecutor.shutdownNow();
        super.onDestroy();
    }

    private void loadPendingFiles() {
        if (controller == null || !controller.isConnected()
                || pendingFiles.isEmpty() || loadingFiles) {
            return;
        }
        loadingFiles = true;
        ArrayList<Uri> selection = new ArrayList<>(pendingFiles);
        Context context = requireContext().getApplicationContext();
        render();
        documentExecutor.execute(() -> {
            List<MediaItem> items = new ArrayList<>();
            for (Uri uri : selection) {
                if (!"content".equals(uri.getScheme())) {
                    continue;
                }
                String title = context.getString(R.string.audio_untitled);
                // A cloud document provider can block. Never query it on the main thread.
                try (Cursor cursor = context.getContentResolver().query(uri,
                        new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                        title = cursor.getString(0);
                    }
                } catch (RuntimeException ignored) {
                    // The player reports inaccessible documents without exposing their URI.
                }
                items.add(new MediaItem.Builder()
                        .setMediaId(UUID.randomUUID().toString())
                        .setUri(uri)
                        .setMediaMetadata(new MediaMetadata.Builder()
                                .setTitle(title)
                                .setArtist(context.getString(R.string.audio_local_file))
                                .build())
                        .build());
            }
            mainHandler.post(() -> {
                loadingFiles = false;
                if (!pendingFiles.equals(selection) || controller == null || !controller.isConnected()) {
                    return;
                }
                pendingFiles.clear();
                filesFailed = items.isEmpty();
                if (!items.isEmpty()) {
                    boolean startPlayback = controller.getMediaItemCount() == 0;
                    controller.addMediaItems(items);
                    if (startPlayback) {
                        controller.prepare();
                        controller.play();
                    }
                }
                render();
            });
        });
    }

    private void render() {
        if (status == null) {
            return;
        }
        boolean connected = controller != null && controller.isConnected();
        boolean hasItems = connected && controller.getMediaItemCount() > 0;
        open.setEnabled(connected && !loadingFiles);
        clear.setEnabled(hasItems && !loadingFiles);
        empty.setVisibility(connected && !hasItems ? View.VISIBLE : View.GONE);
        controls.setVisibility(hasItems ? View.VISIBLE : View.GONE);
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
        } else if (hasItems) {
            status.setText(getString(R.string.audio_queue_position,
                    controller.getCurrentMediaItemIndex() + 1, controller.getMediaItemCount()));
        } else {
            status.setText("");
        }
        status.setVisibility(status.length() == 0 ? View.GONE : View.VISIBLE);
        List<MediaItem> items = new ArrayList<>();
        if (connected) {
            for (int i = 0; i < controller.getMediaItemCount(); i++) {
                items.add(controller.getMediaItemAt(i));
            }
        }
        queueAdapter.submit(items, hasItems ? controller.getCurrentMediaItemIndex() : -1);
    }
}
