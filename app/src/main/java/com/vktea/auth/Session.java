package com.vktea.auth;

import android.content.Context;
import android.content.SharedPreferences;

public class Session {

    private static final java.util.concurrent.atomic.AtomicLong GENERATION = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.CopyOnWriteArraySet<Runnable> CHANGE_LISTENERS =
            new java.util.concurrent.CopyOnWriteArraySet<>();

    private static final String PREFS = "session";
    private static final String KEY_TOKEN = "access_token";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_SECRET = "secret";

    public static void save(Context context, String token, String userId) {
        save(context, token, userId, null);
    }

    public static synchronized void save(Context context, String token, String userId, String secret) {
        String previousUser = getUserId(context);
        prefs(context).edit()
                .putString(KEY_TOKEN, token)
                .putString(KEY_USER_ID, userId)
                .putString(KEY_SECRET, secret != null ? secret : "")
                .apply();
        GENERATION.incrementAndGet();
        com.vktea.msg.MessageLongPoll.sessionChanged();
        if (previousUser != null && !previousUser.equals(userId)) clearAudioData(context);
        com.vktea.audio.AudioSources.clearPrivateAccess();
        notifyChangeListeners();
    }

    public static boolean isAuthorized(Context context) {
        return prefs(context).contains(KEY_TOKEN);
    }

    public static String getToken(Context context) {
        return prefs(context).getString(KEY_TOKEN, null);
    }

    public static String getUserId(Context context) {
        return prefs(context).getString(KEY_USER_ID, null);
    }

    public static String getSecret(Context context) {
        return prefs(context).getString(KEY_SECRET, null);
    }

    public static synchronized void clear(Context context) {
        prefs(context).edit().clear().apply();
        GENERATION.incrementAndGet();
        com.vktea.msg.MessageLongPoll.sessionChanged();
        clearAudioData(context);
        com.vktea.audio.AudioSources.clearPrivateAccess();
        notifyChangeListeners();
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private static void clearAudioData(Context context) {
        com.vktea.audio.AudioPlayback.stop(context);
        context.getSharedPreferences("audio_queue", Context.MODE_PRIVATE).edit().clear().apply();
        com.vktea.audio.AudioOfflineCache.clearAll(context);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static long generation() { return GENERATION.get(); }

    /** Listeners must be removed by their owner when it is cleared. */
    public static void addChangeListener(Runnable listener) {
        if (listener != null) CHANGE_LISTENERS.add(listener);
    }

    public static void removeChangeListener(Runnable listener) {
        if (listener != null) CHANGE_LISTENERS.remove(listener);
    }

    private static void notifyChangeListeners() {
        if (CHANGE_LISTENERS.isEmpty()) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            for (Runnable listener : CHANGE_LISTENERS) {
                try { listener.run(); }
                catch (RuntimeException ignored) {
                    android.util.Log.e("VKTea", "Session listener failed");
                }
            }
        });
    }

    public static final class Snapshot {
        public final String token, secret, userId;
        public final long generation;
        private Snapshot(String token, String secret, String userId, long generation) {
            this.token = token; this.secret = secret; this.userId = userId; this.generation = generation;
        }
    }

    public static synchronized Snapshot snapshot(Context context) {
        SharedPreferences values = prefs(context);
        return new Snapshot(values.getString(KEY_TOKEN, null), values.getString(KEY_SECRET, null),
                values.getString(KEY_USER_ID, null), GENERATION.get());
    }
}
