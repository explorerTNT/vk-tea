package com.vktea.auth;

import android.content.Context;
import android.content.SharedPreferences;

public class Session {

    private static final String PREFS = "session";
    private static final String KEY_TOKEN = "access_token";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_SECRET = "secret";

    public static void save(Context context, String token, String userId) {
        save(context, token, userId, null);
    }

    public static void save(Context context, String token, String userId, String secret) {
        String previousUser = getUserId(context);
        prefs(context).edit()
                .putString(KEY_TOKEN, token)
                .putString(KEY_USER_ID, userId)
                .putString(KEY_SECRET, secret != null ? secret : "")
                .apply();
        if (previousUser != null && !previousUser.equals(userId)) clearAudioData(context);
        com.vktea.audio.AudioSources.clearPrivateAccess();
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

    public static void clear(Context context) {
        prefs(context).edit().clear().apply();
        clearAudioData(context);
        com.vktea.audio.AudioSources.clearPrivateAccess();
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
}
