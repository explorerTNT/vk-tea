package com.vktea.ui;

import android.graphics.Color;
import androidx.core.graphics.ColorUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A bounded, data-only subset of Coffee's color keys. All colors are opaque ARGB. */
public final class CoffeePalette {
    public static final List<String> KEYS = Collections.unmodifiableList(Arrays.asList(
            "brand", "statusbar", "root_background", "card_background", "main_text",
            "summary_text", "accent", "divider", "message_buble", "message_buble_active"));
    public final String id;
    public final String name;
    public final Map<String, Integer> colors;

    private CoffeePalette(String id, String name, Map<String, Integer> colors) {
        this.id = id;
        this.name = name;
        this.colors = Collections.unmodifiableMap(colors);
    }

    public static CoffeePalette create(String id, String name, Map<String, Integer> colors) {
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,64}") || name == null
                || name.trim().isEmpty() || name.codePointCount(0, name.length()) > 64 || hasControls(name)
                || colors == null || colors.size() != KEYS.size()) {
            throw new IllegalArgumentException("Invalid palette");
        }
        Map<String, Integer> copy = new LinkedHashMap<>();
        for (String key : KEYS) {
            Integer color = colors.get(key);
            if (color == null || (color >>> 24) != 0xff) {
                throw new IllegalArgumentException("Missing or non-opaque color: " + key);
            }
            copy.put(key, color);
        }
        return new CoffeePalette(id, name.trim(), copy);
    }

    /** Existing toolbars and status bars use white content, so both backgrounds must support it. */
    public boolean hasReadableChrome() {
        int brand = colors.get("brand");
        // Some existing menu labels and the hamburger icon deliberately use translucent white.
        int secondaryText = ColorUtils.compositeColors(0xb3ffffff, brand);
        int mutedIcon = ColorUtils.compositeColors(0x8cffffff, brand);
        return ColorUtils.calculateContrast(secondaryText, brand) >= 4.5
                && ColorUtils.calculateContrast(mutedIcon, brand) >= 3.0
                && ColorUtils.calculateContrast(0xffb9cde3, brand) >= 4.5
                && ColorUtils.calculateContrast(Color.WHITE, colors.get("statusbar")) >= 4.5;
    }

    private static boolean hasControls(String name) {
        for (int i = 0; i < name.length(); i++) if (Character.isISOControl(name.charAt(i))) return true;
        return false;
    }

    private static CoffeePalette builtIn(String id, String name, int... colors) {
        Map<String, Integer> values = new LinkedHashMap<>();
        for (int i = 0; i < KEYS.size(); i++) values.put(KEYS.get(i), colors[i]);
        return create(id, name, values);
    }

    public static List<CoffeePalette> builtIns() {
        return Arrays.asList(
                builtIn("dark", "Coffee: тёмная", 0xff242728, 0xff242728, 0xff0f0f0f,
                        0xff171819, 0xffe4e8ec, 0xffa5adb5, 0xff91b9df, 0xff353b41,
                        0xff242728, 0xff33485a),
                builtIn("amoled", "Coffee: AMOLED", 0xff000000, 0xff000000, 0xff000000,
                        0xff000000, 0xffe4e8ec, 0xffa5adb5, 0xff91b9df, 0xff363636,
                        0xff242728, 0xff33485a),
                builtIn("dark_blue", "Coffee: тёмно-синяя", 0xff1c2938, 0xff1c2938, 0xff10171f,
                        0xff15202b, 0xffe4e8ec, 0xffa5adb5, 0xff91b9df, 0xff38434d,
                        0xff242728, 0xff33485a));
    }
}
