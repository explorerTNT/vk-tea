package com.vktea.audio.catalog;

import android.content.Context;

import java.util.List;

/** Explicit, cancellable catalog operations. Callbacks are delivered only on the main thread. */
public interface VkAudioRepository extends AutoCloseable {
    interface Callback<T> {
        void onSuccess(T result);
        void onError(VkAudioError error);
    }

    interface Request {
        void cancel();
        boolean isCanceled();
    }

    static VkAudioRepository create(Context context) {
        return new DefaultVkAudioRepository(context.getApplicationContext());
    }

    Request getMyAudio(int offset, int count, Callback<VkAudioPage> callback);

    /** audios are validated owner_id_id[_access_key] identities, never media URLs. */
    Request getById(List<String> audios, Callback<List<VkAudioTrack>> callback);

    // Defaults preserve compatibility with offline repositories that only implement the catalog.
    default Request search(String query, int offset, int count, Callback<VkAudioPage> callback) { throw new UnsupportedOperationException(); }
    default Request getPlaylists(int offset, int count, Callback<List<VkAudioPlaylist>> callback) { throw new UnsupportedOperationException(); }
    default Request getPlaylistAudio(VkAudioPlaylist playlist, int offset, int count, Callback<VkAudioPage> callback) { throw new UnsupportedOperationException(); }
    default Request createPlaylist(String title, Callback<Boolean> callback) { throw new UnsupportedOperationException(); }
    default Request changeLibrary(VkAudioTrack track, boolean add, Callback<Boolean> callback) { throw new UnsupportedOperationException(); }
    default Request changePlaylist(VkAudioPlaylist playlist, VkAudioTrack track, boolean add, Callback<Boolean> callback) { throw new UnsupportedOperationException(); }

    @Override
    void close();
}
