package com.vktea.auth;

import android.content.Context;
import android.content.SharedPreferences;

public class Session {

    private static final java.util.concurrent.atomic.AtomicLong GENERATION = new java.util.concurrent.atomic.AtomicLong();

    private static final String PREFS = "session";
    private static final String KEY_TOKEN = "access_token";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_SECRET = "secret";

    public static void save(Context context, String token, String userId) {
        save(context, token, userId, null);
    }

    public static synchronized void save(Context context, String token, String userId, String secret) {
        prefs(context).edit()
                .putString(KEY_TOKEN, token)
                .putString(KEY_USER_ID, userId)
                .putString(KEY_SECRET, secret != null ? secret : "")
                .apply();
        GENERATION.incrementAndGet();
        com.vktea.msg.MessageLongPoll.sessionChanged();
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
        com.vktea.audio.AudioPlayback.stop(context);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static long generation() { return GENERATION.get(); }

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
