package com.vktea.ui;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import androidx.annotation.ColorRes;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import com.vktea.R;
import java.util.LinkedHashMap;
import java.util.Map;

/** Resolve semantic colors through the active activity theme, including on Android 7–10. */
public final class TeaColors {
    private TeaColors() { }

    public static int get(Context context, @ColorRes int color) {
        int attr = attribute(color);
        TypedValue value = new TypedValue();
        if (attr != 0 && context.getTheme().resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) return ContextCompat.getColor(context, value.resourceId);
            return value.data;
        }
        return ContextCompat.getColor(context, color);
    }

    private static int attribute(int color) {
        if (color == R.color.vk_blue) return R.attr.teaVkBlue;
        if (color == R.color.brand_bar) return R.attr.teaBrandBar;
        if (color == R.color.surface) return R.attr.teaSurface;
        if (color == R.color.surface_secondary) return R.attr.teaSurfaceSecondary;
        if (color == R.color.page_background) return R.attr.teaPageBackground;
        if (color == R.color.chat_background) return R.attr.teaChatBackground;
        if (color == R.color.surface_divider) return R.attr.teaSurfaceDivider;
        if (color == R.color.almost_black) return R.attr.teaAlmostBlack;
        if (color == R.color.section_header) return R.attr.teaSectionHeader;
        if (color == R.color.counter_text) return R.attr.teaCounterText;
        if (color == R.color.menu_bg) return R.attr.teaMenuBg;
        if (color == R.color.menu_icon_normal) return R.attr.teaMenuIconNormal;
        if (color == R.color.menu_selected_bg) return R.attr.teaMenuSelectedBg;
        if (color == R.color.menu_header_bg) return R.attr.teaMenuHeaderBg;
        if (color == R.color.avatar_placeholder) return R.attr.teaAvatarPlaceholder;
        if (color == R.color.toolbar_bg) return R.attr.teaToolbarBg;
        if (color == R.color.post_name) return R.attr.teaPostName;
        if (color == R.color.post_time) return R.attr.teaPostTime;
        if (color == R.color.post_icons) return R.attr.teaPostIcons;
        if (color == R.color.post_divider) return R.attr.teaPostDivider;
        if (color == R.color.msg_in_bg) return R.attr.teaMsgInBg;
        if (color == R.color.audio_surface) return R.attr.teaAudioSurface;
        if (color == R.color.audio_text) return R.attr.teaAudioText;
        if (color == R.color.audio_secondary) return R.attr.teaAudioSecondary;
        if (color == R.color.audio_accent) return R.attr.teaAudioAccent;
        if (color == R.color.audio_artwork) return R.attr.teaAudioArtwork;
        if (color == R.color.audio_divider) return R.attr.teaAudioDivider;
        if (color == R.color.chat_bubble_in) return R.attr.teaChatBubbleIn;
        if (color == R.color.chat_bubble_out) return R.attr.teaChatBubbleOut;
        if (color == R.color.chat_bubble_text) return R.attr.teaChatBubbleText;
        if (color == R.color.button_gray_pressed) return R.attr.teaButtonGrayPressed;
        if (color == R.color.button_gray_background) return R.attr.teaButtonGrayBackground;
        if (color == R.color.button_gray_text) return R.attr.teaButtonGrayText;
        return 0;
    }

    static Map<Integer, Integer> resourceOverrides(CoffeePalette palette) {
        Map<Integer, Integer> result = new LinkedHashMap<>();
        int accent = palette.colors.get("accent");
        int onAccent = ColorUtils.calculateContrast(Color.BLACK, accent)
                >= ColorUtils.calculateContrast(Color.WHITE, accent) ? Color.BLACK : Color.WHITE;
        result.put(R.color.tea_on_accent, onAccent);
        result.put(R.color.vk_blue, accent);
        result.put(R.color.brand_bar, palette.colors.get("brand"));
        result.put(R.color.surface, palette.colors.get("card_background"));
        result.put(R.color.surface_secondary, palette.colors.get("brand"));
        result.put(R.color.page_background, palette.colors.get("root_background"));
        result.put(R.color.chat_background, palette.colors.get("root_background"));
        result.put(R.color.surface_divider, palette.colors.get("divider"));
        result.put(R.color.almost_black, palette.colors.get("main_text"));
        result.put(R.color.section_header, palette.colors.get("summary_text"));
        result.put(R.color.counter_text, palette.colors.get("accent"));
        result.put(R.color.menu_bg, palette.colors.get("card_background"));
        result.put(R.color.menu_icon_normal, palette.colors.get("summary_text"));
        result.put(R.color.menu_selected_bg, palette.colors.get("brand"));
        result.put(R.color.menu_header_bg, palette.colors.get("brand"));
        result.put(R.color.avatar_placeholder, palette.colors.get("brand"));
        result.put(R.color.toolbar_bg, palette.colors.get("card_background"));
        result.put(R.color.post_name, palette.colors.get("accent"));
        result.put(R.color.post_time, palette.colors.get("summary_text"));
        result.put(R.color.post_icons, palette.colors.get("summary_text"));
        result.put(R.color.post_divider, palette.colors.get("divider"));
        result.put(R.color.msg_in_bg, palette.colors.get("message_buble"));
        result.put(R.color.audio_surface, palette.colors.get("card_background"));
        result.put(R.color.audio_text, palette.colors.get("main_text"));
        result.put(R.color.audio_secondary, palette.colors.get("summary_text"));
        result.put(R.color.audio_accent, palette.colors.get("accent"));
        result.put(R.color.audio_artwork, palette.colors.get("brand"));
        result.put(R.color.audio_divider, palette.colors.get("divider"));
        result.put(R.color.chat_bubble_in, palette.colors.get("message_buble"));
        result.put(R.color.chat_bubble_out, palette.colors.get("message_buble_active"));
        result.put(R.color.chat_bubble_text, palette.colors.get("main_text"));
        result.put(R.color.button_gray_pressed, palette.colors.get("divider"));
        result.put(R.color.button_gray_background, palette.colors.get("message_buble"));
        result.put(R.color.button_gray_text, palette.colors.get("accent"));
        return result;
    }
}
