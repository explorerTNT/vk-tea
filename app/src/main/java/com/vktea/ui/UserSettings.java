package com.vktea.ui;

import android.content.Context;

/** Preferences for optional feed behavior, independent of the current account. */
public final class UserSettings {
    private static final String PREFS = "user_settings";
    private static final String STORIES = "show_stories";
    private static final String NEW_POSTS = "check_new_posts";

    private UserSettings() {}

    public static boolean showStories(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(STORIES, true);
    }

    public static void setShowStories(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(STORIES, enabled).apply();
    }

    public static boolean checkNewPosts(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(NEW_POSTS, true);
    }

    public static void setCheckNewPosts(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(NEW_POSTS, enabled).apply();
    }
}
