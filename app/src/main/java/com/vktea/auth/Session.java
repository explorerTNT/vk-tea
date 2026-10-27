package com.vktea.auth;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Current credentials and locally saved accounts. Credentials never leave private preferences. */
public class Session {
    private static final String PREFS = "session";
    private static final String KEY_TOKEN = "access_token";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_SECRET = "secret";
    private static final String KEY_PENDING_REVEAL = "pending_token_reveal";
    private static final String PREFIX = "account.";
    private static final java.util.concurrent.atomic.AtomicLong GENERATION = new java.util.concurrent.atomic.AtomicLong();

    public static void save(Context context, String token, String userId) {
        save(context, token, userId, null);
    }

    public static synchronized void save(Context context, String token, String userId, String secret) {
        saveInternal(context, token, userId, secret, false);
    }

    /** Atomically persist the new credentials and the unfinished login confirmation. */
    public static synchronized void saveForLogin(Context context, String token, String userId, String secret) {
        saveInternal(context, token, userId, secret, true);
    }

    private static void saveInternal(Context context, String token, String userId, String secret, boolean pendingReveal) {
        if (!validId(userId) || token == null || token.isEmpty()) return;
        SharedPreferences p = prefs(context);
        boolean previousSession = isAuthorized(context);
        SharedPreferences.Editor editor = p.edit();
        preserveCurrent(p, editor);
        long previousGeneration = GENERATION.getAndIncrement();
        com.vktea.api.VKApi.cancelSessionCalls();
        if (pendingReveal) editor.putBoolean(KEY_PENDING_REVEAL, true);
        editor.putString(accountKey(userId, "token"), token)
                .putString(accountKey(userId, "secret"), secret != null ? secret : "")
                .putString(KEY_TOKEN, token).putString(KEY_USER_ID, userId)
                .putString(KEY_SECRET, secret != null ? secret : "").apply();
        if (previousSession) clearAccountData(context, previousGeneration);
    }

    public static synchronized List<String> savedAccounts(Context context) {
        SharedPreferences p = prefs(context);
        SharedPreferences.Editor editor = p.edit();
        preserveCurrent(p, editor);
        editor.apply();
        List<String> result = new ArrayList<>();
        for (String key : p.getAll().keySet()) {
            if (key.startsWith(PREFIX) && key.endsWith(".token")) {
                String id = key.substring(PREFIX.length(), key.length() - ".token".length());
                String token = p.getString(key, "");
                if (validId(id) && token != null && !token.isEmpty()) result.add(id);
            }
        }
        Collections.sort(result);
        return result;
    }

    public static boolean hasSavedAccounts(Context context) { return !savedAccounts(context).isEmpty(); }

    public static synchronized boolean switchTo(Context context, String userId) {
        if (hasPendingReveal(context) || !validId(userId)) return false;
        SharedPreferences p = prefs(context);
        String token = p.getString(accountKey(userId, "token"), null);
        if (token == null || token.isEmpty()) return false;
        if (userId.equals(getUserId(context)) && token.equals(getToken(context))) return true;
        String secret = p.getString(accountKey(userId, "secret"), "");
        long previousGeneration = GENERATION.getAndIncrement();
        com.vktea.api.VKApi.cancelSessionCalls();
        p.edit().putString(KEY_TOKEN, token).putString(KEY_USER_ID, userId)
                .putString(KEY_SECRET, secret).apply();
        clearAccountData(context, previousGeneration);
        clearTrustedHash(context);
        return true;
    }

    public static synchronized void removeAccount(Context context, String userId) {
        if (!validId(userId)) return;
        if (userId.equals(getUserId(context))) { clear(context); return; }
        prefs(context).edit().remove(accountKey(userId, "token"))
                .remove(accountKey(userId, "secret")).remove(accountKey(userId, "name")).apply();
    }

    public static String accountName(Context context, String userId) {
        return validId(userId) ? prefs(context).getString(accountKey(userId, "name"), "") : "";
    }

    public static void setAccountName(Context context, String userId, String name) {
        if (validId(userId) && name != null && !name.trim().isEmpty()
                && prefs(context).contains(accountKey(userId, "token"))) {
            prefs(context).edit().putString(accountKey(userId, "name"), name.trim()).apply();
        }
    }

    public static boolean hasPendingReveal(Context context) {
        return isAuthorized(context) && prefs(context).getBoolean(KEY_PENDING_REVEAL, false);
    }

    /** Called only by the token dialog confirmation. */
    public static synchronized void completeReveal(Context context) {
        prefs(context).edit().remove(KEY_PENDING_REVEAL).apply();
    }

    public static boolean isAuthorized(Context context) {
        String token = getToken(context);
        return token != null && !token.isEmpty();
    }
    public static String getToken(Context context) { return prefs(context).getString(KEY_TOKEN, null); }
    public static String getUserId(Context context) { return prefs(context).getString(KEY_USER_ID, null); }
    public static String getSecret(Context context) { return prefs(context).getString(KEY_SECRET, null); }
    public static long generation() { return GENERATION.get(); }
    public static final class Snapshot {
        public final String token, secret, userId;
        public final long generation;
        private Snapshot(String token, String secret, String userId, long generation) {
            this.token = token; this.secret = secret; this.userId = userId; this.generation = generation;
        }
    }
    public static synchronized Snapshot snapshot(Context context) {
        SharedPreferences p = prefs(context);
        return new Snapshot(p.getString(KEY_TOKEN, null), p.getString(KEY_SECRET, null),
                p.getString(KEY_USER_ID, null), GENERATION.get());
    }

    /** Sign out only the active account; other explicitly saved accounts remain available. */
    public static synchronized void clear(Context context) {
        SharedPreferences p = prefs(context);
        String userId = p.getString(KEY_USER_ID, null);
        SharedPreferences.Editor editor = p.edit().remove(KEY_TOKEN).remove(KEY_USER_ID)
                .remove(KEY_SECRET).remove(KEY_PENDING_REVEAL);
        if (validId(userId)) editor.remove(accountKey(userId, "token"))
                .remove(accountKey(userId, "secret")).remove(accountKey(userId, "name"));
        long previousGeneration = GENERATION.getAndIncrement();
        com.vktea.api.VKApi.cancelSessionCalls();
        editor.apply();
        clearAccountData(context, previousGeneration);
        clearTrustedHash(context);
    }

    private static void preserveCurrent(SharedPreferences p, SharedPreferences.Editor editor) {
        String id = p.getString(KEY_USER_ID, null), token = p.getString(KEY_TOKEN, null);
        if (validId(id) && token != null && !token.isEmpty()) {
            editor.putString(accountKey(id, "token"), token)
                    .putString(accountKey(id, "secret"), p.getString(KEY_SECRET, ""));
        }
    }
    private static boolean validId(String id) { return id != null && id.matches("[0-9]{1,20}") && !id.matches("0+"); }
    private static String accountKey(String id, String field) { return PREFIX + id + "." + field; }
    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private static void clearAccountData(Context context, long generation) {
        com.vktea.audio.AudioPlayback.stop(context, generation);
    }
    public static void clearTrustedHash(Context context) {
        context.getSharedPreferences("auth", Context.MODE_PRIVATE).edit().remove("trusted_hash").apply();
    }
    public static boolean clearFeedCache(Context context) {
        String id = getUserId(context);
        java.io.File legacy = new java.io.File(context.getFilesDir(), "feed_cache.json");
        boolean ok = !legacy.exists() || legacy.delete();
        if (validId(id)) {
            java.io.File current = new java.io.File(context.getFilesDir(), "feed_cache_" + id + ".json");
            ok = (!current.exists() || current.delete()) && ok;
        }
        return ok;
    }
    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
}
