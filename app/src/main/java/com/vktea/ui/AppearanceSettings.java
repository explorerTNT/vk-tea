package com.vktea.ui;

import android.content.Context;

import androidx.appcompat.app.AppCompatDelegate;

/** Persists the user's appearance choice independently of account credentials. */
public final class AppearanceSettings {
    public static final String SYSTEM = "system";
    public static final String LIGHT = "light";
    public static final String DARK = "dark";
    private static final String PREFS = "appearance";
    private static final String KEY_MODE = "theme_mode";

    private AppearanceSettings() { }

    public static String getMode(Context context) {
        String mode = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, SYSTEM);
        return LIGHT.equals(mode) || DARK.equals(mode) ? mode : SYSTEM;
    }

    public static void setMode(Context context, String mode) {
        if (!SYSTEM.equals(mode) && !LIGHT.equals(mode) && !DARK.equals(mode)) {
            throw new IllegalArgumentException("Unknown theme mode");
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode).apply();
        apply(context);
    }

    public static void apply(Context context) {
        String mode = getMode(context);
        AppCompatDelegate.setDefaultNightMode(DARK.equals(mode) ? AppCompatDelegate.MODE_NIGHT_YES
                : LIGHT.equals(mode) ? AppCompatDelegate.MODE_NIGHT_NO
                : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }
}
