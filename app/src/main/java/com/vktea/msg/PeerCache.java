package com.vktea.msg;

import android.content.Context;

/** Дисковый кеш имени/аватара пира: диалоги рисуются мгновенно, сеть только обновляет. */
public final class PeerCache {
    private static final String PREFS = "peer_cache";
    private static final char SEP = '\u0001';

    private PeerCache() {
    }

    public static void store(Context context, long id, String name, String photo) {
        if (id == 0 || name == null || name.isEmpty()) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(String.valueOf(id), name + SEP + (photo == null ? "" : photo))
                .apply();
    }

    /** @return {name, photo} или null */
    public static String[] load(Context context, long id) {
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(String.valueOf(id), null);
        if (raw == null) return null;
        int at = raw.indexOf(SEP);
        if (at < 0) return null;
        return new String[]{raw.substring(0, at), raw.substring(at + 1)};
    }
}
