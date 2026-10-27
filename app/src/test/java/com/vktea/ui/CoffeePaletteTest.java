package com.vktea.ui;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.widget.TextView;
import android.view.ContextThemeWrapper;

import com.vktea.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.LinkedHashMap;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CoffeePaletteTest {
    @Test public void builtInsHaveStableIdsAndReferenceBackgrounds() {
        assertEquals("dark", CoffeePalette.builtIns().get(0).id);
        assertEquals("amoled", CoffeePalette.builtIns().get(1).id);
        assertEquals("dark_blue", CoffeePalette.builtIns().get(2).id);
        assertEquals(Integer.valueOf(0xff000000), CoffeePalette.builtIns().get(1).colors.get("root_background"));
        assertEquals(Integer.valueOf(0xff15202b), CoffeePalette.builtIns().get(2).colors.get("card_background"));
    }

    @Test public void rejectsInvalidAndTransparentColorsAndCopiesInput() {
        Map<String, Integer> input = new LinkedHashMap<>(CoffeePalette.builtIns().get(0).colors);
        CoffeePalette result = CoffeePalette.create("custom-one", "Example", input);
        input.put("brand", 0xff123456);
        assertNotEquals(input.get("brand"), result.colors.get("brand"));
        input.put("brand", 0x00123456);
        assertThrows(IllegalArgumentException.class, () -> CoffeePalette.create("custom-one", "Example", input));
        input.remove("brand");
        assertThrows(IllegalArgumentException.class, () -> CoffeePalette.create("custom-one", "Example", input));
        assertThrows(IllegalArgumentException.class, () -> CoffeePalette.create("bad/id", "Example", result.colors));
    }

    @Test public void namesUseTheSameCodePointLimitAsThemeExchange() {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 64; i++) name.appendCodePoint(0x10400);
        CoffeePalette palette = CoffeePalette.create("custom", name.toString(), CoffeePalette.builtIns().get(0).colors);
        assertEquals(name.toString(), palette.name);
        name.appendCodePoint(0x10400);
        assertThrows(IllegalArgumentException.class,
                () -> CoffeePalette.create("custom", name.toString(), palette.colors));
    }

    @Test public void failedCustomApplicationRestoresPreviousChoice() {
        Activity activity = Robolectric.buildActivity(Activity.class).create().get();
        SharedPreferences p = activity.getSharedPreferences("appearance", Context.MODE_PRIVATE);
        p.edit().clear().putString("theme_mode", "dark").putString("palette_id", "custom")
                .putBoolean("palette_pending", true).putString("previous_mode", "dark")
                .putString("previous_id", "dark_blue").putString("previous_name", "").commit();
        AppearanceSettings.recoverFailedPalette(activity);
        assertEquals("dark_blue", AppearanceSettings.getPalette(activity).id);
        assertEquals("dark", AppearanceSettings.getMode(activity));
        assertFalse(p.getBoolean("palette_pending", false));
        p.edit().clear().commit();
    }

    @Test public void chromeRequiresWhiteContrastBeforeApplication() {
        Map<String, Integer> colors = new LinkedHashMap<>(CoffeePalette.builtIns().get(0).colors);
        colors.put("brand", 0xff565656);
        colors.put("statusbar", 0xff767676);
        assertTrue(CoffeePalette.create("custom", "Boundary", colors).hasReadableChrome());
        colors.put("brand", 0xff575757);
        CoffeePalette unsafeBrand = CoffeePalette.create("custom", "Bright", colors);
        assertFalse(unsafeBrand.hasReadableChrome());
        assertThrows(IllegalArgumentException.class, () -> AppearanceSettings.setPalette(null, unsafeBrand));
        colors.put("brand", 0xff000000);
        colors.put("statusbar", 0xffffffff);
        assertFalse(CoffeePalette.create("custom", "Bright status", colors).hasReadableChrome());
    }

    @Test @Config(qualifiers = "notnight")
    public void customLightThemeUsesDarkTextAndReadableAccent() {
        Map<String, Integer> colors = new LinkedHashMap<>(CoffeePalette.builtIns().get(0).colors);
        colors.put("card_background", Color.WHITE);
        colors.put("root_background", 0xfff3f3f3);
        colors.put("main_text", 0xff2e3033);
        colors.put("summary_text", 0xff78797a);
        colors.put("accent", 0xffffff00);
        CoffeePalette palette = CoffeePalette.create("custom", "Light", colors);
        Map<Integer, Integer> resources = TeaColors.resourceOverrides(palette);
        assertEquals(Integer.valueOf(Color.WHITE), resources.get(R.color.surface));
        assertEquals(Integer.valueOf(0xff2e3033), resources.get(R.color.almost_black));
        assertEquals(Integer.valueOf(Color.BLACK), resources.get(R.color.tea_on_accent));
        colors.put("accent", 0xff003366);
        assertEquals(Integer.valueOf(Color.WHITE), TeaColors.resourceOverrides(
                CoffeePalette.create("custom", "Light", colors)).get(R.color.tea_on_accent));

        // The normal light resources here match the custom foreground fixture; test actual themed views
        // as well as the Material attributes, without relying on Robolectric's resource-loader support.
        ContextThemeWrapper context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_VKTea);
        context.getTheme().applyStyle(R.style.TeaPaletteDefault, true);
        context.getTheme().applyStyle(R.style.TeaPaletteCustom, true);
        int text = TeaColors.get(context, R.color.almost_black);
        int secondary = TeaColors.get(context, R.color.section_header);
        assertEquals(0xff2e3033, text);
        assertEquals(text, new TextView(context).getCurrentTextColor());
        assertEquals(text, themeColor(context, com.google.android.material.R.attr.colorOnSurface));
        assertEquals(secondary, themeColor(context, android.R.attr.textColorSecondary));
        assertEquals(TeaColors.get(context, R.color.vk_blue), themeColor(context, android.R.attr.colorAccent));
    }

    @Test @Config(qualifiers = "night")
    public void nightPaletteKeepsOverridableBaseResources() {
        ContextThemeWrapper context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_VKTea);
        // Loader tables contain unqualified colors; a -night resource with the same ID would win over them.
        assertEquals(0xffe5e7ea, context.getResources().getColor(R.color.page_background, context.getTheme()));
        context.getTheme().applyStyle(R.style.TeaPaletteDefault, true);
        context.getTheme().applyStyle(R.style.TeaPaletteNight, true);
        assertEquals(0xff0f0f0f, TeaColors.get(context, R.color.page_background));
        assertEquals(0xff171819, TeaColors.get(context, R.color.surface));
    }

    @Test @Config(qualifiers = "night")
    public void defaultOverlayRetainsDayNightForeground() {
        ContextThemeWrapper context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_VKTea);
        int primary = themeColor(context, android.R.attr.textColorPrimary);
        int secondary = themeColor(context, android.R.attr.textColorSecondary);
        context.getTheme().applyStyle(R.style.TeaPaletteDefault, true);
        assertEquals(primary, themeColor(context, android.R.attr.textColorPrimary));
        assertEquals(secondary, themeColor(context, android.R.attr.textColorSecondary));
    }

    private int themeColor(Context context, int attr) {
        android.content.res.TypedArray values = context.obtainStyledAttributes(new int[]{attr});
        try { return values.getColor(0, Color.TRANSPARENT); }
        finally { values.recycle(); }
    }

    @Test public void overlaysResolveForViewsAndDrawablesOnAndroidNine() {
        ContextThemeWrapper context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_VKTea);
        context.getTheme().applyStyle(R.style.TeaPaletteDefault, true);
        context.getTheme().applyStyle(R.style.TeaPaletteAMOLED, true);
        assertEquals(Color.BLACK, TeaColors.get(context, R.color.surface));
        GradientDrawable bubble = (GradientDrawable) context.getDrawable(R.drawable.bg_comment_bubble);
        assertNotNull(bubble);
        assertEquals(Color.BLACK, bubble.getColor().getDefaultColor());
        context.getTheme().applyStyle(R.style.TeaPaletteBlue, true);
        assertEquals(0xff15202b, TeaColors.get(context, R.color.surface));
        assertEquals(0xff10171f, TeaColors.get(context, R.color.page_background));
        assertFalse(AppearanceSettings.supportsCustomPalettes());
    }
}
