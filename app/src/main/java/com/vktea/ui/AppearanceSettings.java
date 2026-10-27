package com.vktea.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.color.ColorResourcesOverride;
import com.vktea.R;

import java.util.LinkedHashMap;
import java.util.Map;

/** Persists appearance independently of account credentials. */
@SuppressLint("RestrictedApi") // Material 1.12 is pinned; only used on Android 11+.
public final class AppearanceSettings {
    public static final String SYSTEM = "system";
    public static final String LIGHT = "light";
    public static final String DARK = "dark";
    private static final String PREFS = "appearance";
    private static final String KEY_MODE = "theme_mode";
    private static final String PALETTE_ID = "palette_id";
    private static final String PALETTE_NAME = "palette_name";
    private static final String REVISION = "palette_revision";
    private static final String PENDING = "palette_pending";

    private AppearanceSettings() { }
    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    public static String getMode(Context context) {
        String mode = prefs(context).getString(KEY_MODE, SYSTEM);
        return LIGHT.equals(mode) || DARK.equals(mode) ? mode : SYSTEM;
    }

    public static void setMode(Context context, String mode) {
        if (!SYSTEM.equals(mode) && !LIGHT.equals(mode) && !DARK.equals(mode)) throw new IllegalArgumentException("Unknown theme mode");
        SharedPreferences p = prefs(context);
        boolean hadPalette = getPalette(context) != null;
        p.edit().putString(KEY_MODE, mode).remove(PALETTE_ID).remove(PENDING)
                .putLong(REVISION, p.getLong(REVISION, 0) + 1).apply();
        apply(context);
        if (hadPalette && context instanceof Activity) refresh((Activity) context);
    }

    public static void apply(Context context) {
        String mode = getMode(context);
        AppCompatDelegate.setDefaultNightMode(DARK.equals(mode) ? AppCompatDelegate.MODE_NIGHT_YES
                : LIGHT.equals(mode) ? AppCompatDelegate.MODE_NIGHT_NO : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    public static CoffeePalette getPalette(Context context) {
        SharedPreferences p = prefs(context);
        String id = p.getString(PALETTE_ID, "");
        if (id.isEmpty()) return null;
        for (CoffeePalette palette : CoffeePalette.builtIns()) if (palette.id.equals(id)) return palette;
        try {
            Map<String, Integer> colors = new LinkedHashMap<>();
            for (String key : CoffeePalette.KEYS) {
                if (!p.contains("palette_" + key)) return null;
                colors.put(key, p.getInt("palette_" + key, 0));
            }
            CoffeePalette restored = CoffeePalette.create(id, p.getString(PALETTE_NAME, ""), colors);
            // A previously stored palette may predate the contrast validation.
            return restored.hasReadableChrome() ? restored : null;
        } catch (IllegalArgumentException | ClassCastException ex) { return null; }
    }

    public static boolean supportsCustomPalettes() { return Build.VERSION.SDK_INT >= 30; }

    public static void setPalette(Activity activity, CoffeePalette palette) {
        if (palette == null) throw new IllegalArgumentException("Palette is required");
        if (!palette.hasReadableChrome()) throw new IllegalArgumentException("Toolbar and status bar backgrounds need 4.5:1 white contrast");
        boolean builtIn = false;
        for (CoffeePalette option : CoffeePalette.builtIns()) if (option.id.equals(palette.id)) { builtIn = true; break; }
        if (!builtIn) {
            if (!supportsCustomPalettes()) throw new UnsupportedOperationException("Custom palettes require Android 11 or later");
            ColorResourcesOverride override = ColorResourcesOverride.getInstance();
            Context probe = override == null ? activity : override.wrapContextIfPossible(activity, TeaColors.resourceOverrides(palette));
            if (probe == activity || !resourcesMatch(probe, palette)) {
                throw new UnsupportedOperationException("Color resource override is unavailable");
            }
        }
        SharedPreferences p = prefs(activity);
        SharedPreferences.Editor edit = p.edit().putString(PALETTE_ID, palette.id)
                .putString(PALETTE_NAME, palette.name).putString(KEY_MODE, DARK)
                .putLong(REVISION, p.getLong(REVISION, 0) + 1);
        if (!builtIn) {
            edit.putBoolean(PENDING, true).putString("previous_mode", getMode(activity))
                    .putString("previous_id", p.getString(PALETTE_ID, ""))
                    .putString("previous_name", p.getString(PALETTE_NAME, ""));
            for (String key : CoffeePalette.KEYS) edit.putInt("previous_" + key, p.getInt("palette_" + key, 0));
        } else edit.remove(PENDING);
        for (String key : CoffeePalette.KEYS) edit.putInt("palette_" + key, palette.colors.get(key));
        edit.apply();
        apply(activity);
        refresh(activity);
    }

    public static void clearPalette(Activity activity) {
        SharedPreferences p = prefs(activity);
        p.edit().remove(PALETTE_ID).remove(PENDING).putLong(REVISION, p.getLong(REVISION, 0) + 1).apply();
        refresh(activity);
    }

    private static void refresh(Activity activity) {
        activity.getWindow().getDecorView().post(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed() && !activity.isChangingConfigurations()) activity.recreate();
        });
    }

    static long paletteRevision(Context context) { return prefs(context).getLong(REVISION, 0); }

    /** Give each activity isolated resources so clearing a palette cannot retain old loaders. */
    static Context wrapBaseContext(Context base) {
        CoffeePalette palette = getPalette(base);
        if (palette == null || isBuiltIn(palette.id) || !supportsCustomPalettes()) return base;
        ColorResourcesOverride override = ColorResourcesOverride.getInstance();
        return override == null ? base : override.wrapContextIfPossible(base, TeaColors.resourceOverrides(palette));
    }

    /** Called after AppCompat attaches its final context but before content inflation. */
    static boolean applyPalette(Activity activity) {
        CoffeePalette palette = getPalette(activity);
        if (palette != null && !isBuiltIn(palette.id)) {
            ColorResourcesOverride override = supportsCustomPalettes() ? ColorResourcesOverride.getInstance() : null;
            if (override == null || !override.applyIfPossible(activity, TeaColors.resourceOverrides(palette))
                    || !resourcesMatch(activity, palette)) return false;
        }
        activity.getTheme().applyStyle(R.style.TeaPaletteDefault, true);
        boolean night = (activity.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        if (night && (palette == null || isBuiltIn(palette.id))) activity.getTheme().applyStyle(R.style.TeaPaletteNight, true);
        if (palette != null && !isBuiltIn(palette.id)) {
            activity.getTheme().applyStyle(R.style.TeaPaletteCustom, true);
            if (TeaColors.get(activity, R.color.page_background) != palette.colors.get("root_background")) return false;
            activity.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(palette.colors.get("root_background")));
            prefs(activity).edit().remove(PENDING).apply();
        }
        if (palette != null && "amoled".equals(palette.id)) activity.getTheme().applyStyle(R.style.TeaPaletteAMOLED, true);
        if (palette != null && "dark_blue".equals(palette.id)) activity.getTheme().applyStyle(R.style.TeaPaletteBlue, true);
        return true;
    }

    private static boolean resourcesMatch(Context context, CoffeePalette palette) {
        try {
            for (Map.Entry<Integer, Integer> entry : TeaColors.resourceOverrides(palette).entrySet()) {
                if (context.getResources().getColor(entry.getKey(), context.getTheme()) != entry.getValue()) return false;
            }
            return true;
        } catch (RuntimeException failure) { return false; }
    }

    /** Restore the last selection when a newly selected palette cannot be applied. */
    static void recoverFailedPalette(Activity activity) {
        SharedPreferences p = prefs(activity);
        SharedPreferences.Editor edit = p.edit().remove(PENDING)
                .putLong(REVISION, p.getLong(REVISION, 0) + 1);
        if (p.getBoolean(PENDING, false)) {
            String previous = p.getString("previous_id", "");
            if (previous.isEmpty()) edit.remove(PALETTE_ID); else edit.putString(PALETTE_ID, previous);
            edit.putString(PALETTE_NAME, p.getString("previous_name", ""))
                    .putString(KEY_MODE, p.getString("previous_mode", SYSTEM));
            for (String key : CoffeePalette.KEYS) edit.putInt("palette_" + key, p.getInt("previous_" + key, 0));
        } else edit.remove(PALETTE_ID);
        edit.apply();
        apply(activity);
    }

    private static boolean isBuiltIn(String id) {
        for (CoffeePalette option : CoffeePalette.builtIns()) if (option.id.equals(id)) return true;
        return false;
    }
}
