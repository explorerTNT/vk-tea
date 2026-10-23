package com.vktea.audio;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;

import com.vktea.R;
import com.vktea.ui.AudioPlayerActivity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Shared presentation and user initiated queue actions, independent of a controller connection. */
public final class AudioUi {
    public static final String EXTRA_DURATION_MS = "audio.duration_ms";
    public static final String EXTRA_BITRATE = "audio.bitrate_bps";
    public static final String EXTRA_FILE_SIZE = "audio.file_size";

    private AudioUi() {}

    public static String title(Context context, MediaItem item) {
        CharSequence text = item == null ? null : item.mediaMetadata.title;
        return text == null || text.toString().trim().isEmpty()
                ? context.getString(R.string.audio_untitled) : text.toString();
    }

    public static String artist(Context context, MediaItem item) {
        CharSequence text = item == null ? null : item.mediaMetadata.artist;
        return text == null || text.toString().trim().isEmpty()
                ? context.getString(R.string.audio_unknown_artist) : text.toString();
    }

    public static long duration(MediaItem item) {
        Bundle extras = item == null ? null : item.mediaMetadata.extras;
        return extras == null ? 0 : Math.max(0, extras.getLong(EXTRA_DURATION_MS, 0));
    }

    public static String formatTime(long milliseconds) {
        long seconds = Math.max(0, milliseconds) / 1000;
        return seconds >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    public static String mediaId(Uri uri) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(uri.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder id = new StringBuilder("local-");
            for (byte value : hash) id.append(String.format(Locale.ROOT, "%02x", value & 255));
            return id.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static MediaItem source(MediaItem item) {
        if (item == null) return null;
        if (item.localConfiguration != null) return item;
        return AudioSources.find(item.mediaId);
    }

    public static boolean isLocal(MediaItem item) {
        MediaItem original = source(item);
        if (original == null || original.localConfiguration == null) return false;
        Uri uri = original.localConfiguration.uri;
        String authority = uri.getAuthority();
        return "content".equals(uri.getScheme()) && ("media".equals(authority)
                || "com.android.providers.media.documents".equals(authority)
                || "com.android.providers.downloads.documents".equals(authority)
                || "com.android.externalstorage.documents".equals(authority));
    }

    public static boolean canExport(MediaItem item) {
        MediaItem original = source(item);
        return original != null && original.localConfiguration != null
                && "content".equals(original.localConfiguration.uri.getScheme());
    }

    public static boolean isSaved(Context context, MediaItem item) {
        return VkAudioIds.isVk(item) || item != null && context.getSharedPreferences("audio_marks", Context.MODE_PRIVATE)
                .getBoolean(item.mediaId, false);
    }

    public static boolean canToggleSaved(MediaItem item) { return item != null && !VkAudioIds.isVk(item); }

    public static void toggleSaved(Context context, MediaItem item) {
        if (!canToggleSaved(item)) return;
        boolean value = !isSaved(context, item);
        context.getSharedPreferences("audio_marks", Context.MODE_PRIVATE).edit()
                .putBoolean(item.mediaId, value).apply();
        Toast.makeText(context, value ? R.string.audio_mark_saved : R.string.audio_mark_removed,
                Toast.LENGTH_SHORT).show();
    }

    public static void togglePlay(Player player) {
        if (player.getMediaItemCount() == 0) return;
        if (player.getPlayerError() != null || player.getPlaybackState() == Player.STATE_IDLE) {
            player.prepare();
            player.play();
        } else if (player.getPlaybackState() == Player.STATE_ENDED) {
            player.seekToDefaultPosition();
            player.play();
        } else if (player.getPlayWhenReady()) {
            player.pause();
        } else {
            player.play();
        }
    }

    public static void toggleRepeat(Player player) {
        int mode = player.getRepeatMode();
        player.setRepeatMode(mode == Player.REPEAT_MODE_OFF ? Player.REPEAT_MODE_ALL
                : mode == Player.REPEAT_MODE_ALL ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
    }

    public static void openPlayer(Context context) {
        context.startActivity(new Intent(context, AudioPlayerActivity.class));
    }

    public static void showActions(Activity activity, View anchor, Player player) {
        showActions(activity, anchor, player, player.getCurrentMediaItemIndex());
    }

    public static void showActions(Activity activity, View anchor, Player player, int index) {
        if (index < 0 || index >= player.getMediaItemCount()) return;
        MediaItem selected = player.getMediaItemAt(index);
        showItemActions(activity, anchor, player, selected);
    }

    public static void showCatalogActions(Activity activity, View anchor, MediaItem item) {
        showItemActions(activity, anchor, null, item);
    }

    private static void showItemActions(Activity activity, View anchor, Player player, MediaItem selected) {
        PopupMenu popup = new PopupMenu(new ContextThemeWrapper(activity, R.style.Theme_VKTea_AudioPlayer), anchor);
        popup.getMenu().add(0, 1, 0, R.string.audio_save).setCheckable(true)
                .setChecked(isSaved(activity, selected)).setEnabled(canToggleSaved(selected));
        popup.getMenu().add(0, 2, 1, R.string.audio_download).setEnabled(canExport(selected));
        popup.getMenu().add(0, 3, 2, R.string.audio_lyrics).setEnabled(false);
        popup.getMenu().add(0, 4, 3, R.string.audio_similar).setEnabled(false);
        popup.getMenu().add(0, 5, 4, R.string.audio_copy_title);
        popup.getMenu().add(0, 6, 5, VkAudioIds.isVk(selected)
                ? R.string.audio_remove_from_queue : R.string.audio_remove).setEnabled(player != null);
        popup.getMenu().add(0, 7, 6, R.string.audio_search_artist).setEnabled(false);
        popup.getMenu().add(0, 8, 7, R.string.audio_share);
        popup.setOnMenuItemClickListener(action -> {
            switch (action.getItemId()) {
                case 1: toggleSaved(activity, selected); return true;
                case 2: export(activity, selected); return true;
                case 5:
                    ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.audio_copy_title),
                            artist(activity, selected) + " — " + title(activity, selected)));
                    return true;
                case 6:
                    if (player == null) return false;
                    // Resolve the captured ID again: playback may have changed while the menu was open.
                    for (int i = 0; i < player.getMediaItemCount(); i++) {
                        if (player.getMediaItemAt(i).mediaId.equals(selected.mediaId)) {
                            player.removeMediaItem(i);
                            break;
                        }
                    }
                    return true;
                case 8:
                    Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, artist(activity, selected) + " — " + title(activity, selected));
                    activity.startActivity(Intent.createChooser(share, activity.getString(R.string.audio_share)));
                    return true;
                default: return false;
            }
        });
        popup.show();
    }

    public static void showQueue(Activity activity, Player player) {
        int count = player.getMediaItemCount();
        if (count == 0) return;
        String[] titles = new String[count];
        String[] ids = new String[count];
        for (int i = 0; i < count; i++) {
            MediaItem item = player.getMediaItemAt(i);
            titles[i] = title(activity, item) + "\n" + artist(activity, item);
            ids[i] = item.mediaId;
        }
        new AlertDialog.Builder(activity).setTitle(R.string.audio_queue)
                .setSingleChoiceItems(titles, player.getCurrentMediaItemIndex(), (dialog, index) -> {
                    for (int i = 0; i < player.getMediaItemCount(); i++) {
                        if (ids[index].equals(player.getMediaItemAt(i).mediaId)) {
                            player.seekToDefaultPosition(i);
                            player.prepare();
                            player.play();
                            break;
                        }
                    }
                    dialog.dismiss();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    public static void export(Activity activity, MediaItem item) {
        MediaItem original = source(item);
        if (original == null || original.localConfiguration == null
                || !"content".equals(original.localConfiguration.uri.getScheme())) {
            Toast.makeText(activity, R.string.audio_export_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        activity.startActivity(new Intent(activity, AudioExportActivity.class)
                .putExtra("media_id", item.mediaId));
    }
}
