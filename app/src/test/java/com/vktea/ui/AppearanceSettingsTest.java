package com.vktea.ui;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import androidx.appcompat.app.AppCompatDelegate;
import org.robolectric.RuntimeEnvironment;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class AppearanceSettingsTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit();
        AppearanceSettings.apply(context);
    }

    @After public void tearDown() {
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit();
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    @Test public void choicePersistsAndAppliesAcrossReload() {
        assertEquals(AppearanceSettings.SYSTEM, AppearanceSettings.getMode(context));
        AppearanceSettings.setMode(context, AppearanceSettings.DARK);
        assertEquals(AppearanceSettings.DARK, AppearanceSettings.getMode(context));
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.getDefaultNightMode());
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        AppearanceSettings.apply(context);
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.getDefaultNightMode());
        AppearanceSettings.setMode(context, AppearanceSettings.LIGHT);
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.getDefaultNightMode());
        AppearanceSettings.setMode(context, AppearanceSettings.SYSTEM);
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppCompatDelegate.getDefaultNightMode());
    }

    @Test public void invalidStoredValueFallsBackToSystem() {
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putString("theme_mode", "unexpected").commit();
        AppearanceSettings.apply(context);
        assertEquals(AppearanceSettings.SYSTEM, AppearanceSettings.getMode(context));
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppCompatDelegate.getDefaultNightMode());
    }
}
