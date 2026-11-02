package com.vktea.audio;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Persists only stable VK identities and display fields, never signed source URLs. */
@UnstableApi
final class AudioQueueStore {
    private static final String PREFS = "audio_queue";
    private static final int MAX_ITEMS = 2000;
    private final SharedPreferences prefs;

    AudioQueueStore(Context context) { prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    void save(Player player, String account) {
        if (account == null || account.isEmpty()) { clear(); return; }
        try {
            JSONArray items = new JSONArray();
            int count = player.getMediaItemCount();
            // Do not persist temporary document grants or URL-based items.
            if (count > MAX_ITEMS) { clear(); return; }
            for (int i = 0; i < count; i++) {
                MediaItem item = player.getMediaItemAt(i);
                if (!VkAudioIds.isVk(item)) { clear(); return; }
                JSONObject row = new JSONObject();
                row.put("id", item.mediaId);
                row.put("title", text(item.mediaMetadata.title));
                row.put("artist", text(item.mediaMetadata.artist));
                row.put("duration", AudioUi.duration(item));
                row.put("playable", !Boolean.FALSE.equals(item.mediaMetadata.isPlayable));
                items.put(row);
            }
            JSONObject state = new JSONObject();
            state.put("version", 1);
            state.put("account", account);
            state.put("items", items);
            state.put("index", player.getCurrentMediaItemIndex());
            state.put("position", Math.max(0, player.getCurrentPosition()));
            state.put("repeat", player.getRepeatMode());
            state.put("shuffle", player.getShuffleModeEnabled());
            prefs.edit().putString("state", state.toString()).apply();
        } catch (JSONException ignored) { clear(); }
    }

    void restore(Player player, @Nullable String account) {
        String raw = prefs.getString("state", null);
        if (raw == null || account == null || account.isEmpty()) return;
        try {
            JSONObject state = new JSONObject(raw);
            if (state.optInt("version") != 1 || !account.equals(state.optString("account"))) return;
            JSONArray rows = state.getJSONArray("items");
            if (rows.length() == 0 || rows.length() > MAX_ITEMS) return;
            List<MediaItem> items = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String id = row.getString("id");
                if (VkAudioIds.apiId(id) == null) throw new JSONException("Invalid ID");
                Bundle extras = new Bundle();
                extras.putLong(AudioUi.EXTRA_DURATION_MS, Math.max(0, row.optLong("duration")));
                items.add(new MediaItem.Builder().setMediaId(id)
                        .setMediaMetadata(new MediaMetadata.Builder()
                                .setTitle(row.optString("title", ""))
                                .setArtist(row.optString("artist", ""))
                                .setIsPlayable(row.optBoolean("playable", true)).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                .setExtras(extras).build()).build());
            }
            int index = state.optInt("index", 0);
            if (index < 0 || index >= items.size()) index = 0;
            int repeat = state.optInt("repeat", Player.REPEAT_MODE_OFF);
            if (repeat < Player.REPEAT_MODE_OFF || repeat > Player.REPEAT_MODE_ALL) repeat = Player.REPEAT_MODE_OFF;
            player.setMediaItems(items, index, Math.max(0, state.optLong("position")));
            player.setRepeatMode(repeat);
            player.setShuffleModeEnabled(state.optBoolean("shuffle"));
            // Restore paused: connecting a controller must never start playback.
        } catch (JSONException | IllegalArgumentException ignored) { clear(); }
    }

    void clear() { prefs.edit().clear().apply(); }
    private static String text(@Nullable CharSequence value) { return value == null ? "" : value.toString(); }
}
