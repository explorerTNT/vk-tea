package com.vktea.ui;

import android.content.Context;

/** User preferences independent of the current account. */
public final class UserSettings {
    private static final String PREFS = "user_settings";
    private static final String STORIES = "show_stories";
    private static final String NEW_POSTS = "check_new_posts";
    private static final String SEND_BY_ENTER = "send_by_enter";
    private static final String MESSAGE_TEXT_SIZE = "message_text_size";
    private static final String FRIENDS_ORDER = "friends_order";
    private static final String START_SECTION = "start_section";

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

    public static boolean sendByEnter(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(SEND_BY_ENTER, false);
    }

    public static void setSendByEnter(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(SEND_BY_ENTER, enabled).apply();
    }

    /** Coffee's smaller, normal and larger choices, expressed as offsets from 16sp. */
    public static int messageTextSize(Context context) {
        int size = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(MESSAGE_TEXT_SIZE, 0);
        return size >= -1 && size <= 1 ? size : 0;
    }

    public static void setMessageTextSize(Context context, int size) {
        if (size < -1 || size > 1) throw new IllegalArgumentException("Invalid message text size");
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(MESSAGE_TEXT_SIZE, size).apply();
    }

    public static String friendsOrder(Context context) {
        String order = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(FRIENDS_ORDER, "firstname");
        return "lastname".equals(order) || "hints".equals(order) ? order : "firstname";
    }

    public static void setFriendsOrder(Context context, String order) {
        if (!"firstname".equals(order) && !"lastname".equals(order) && !"hints".equals(order))
            throw new IllegalArgumentException("Invalid friends order");
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(FRIENDS_ORDER, order).apply();
    }

    public static String startSection(Context context) {
        String section = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(START_SECTION, "news");
        return "messages".equals(section) || "friends".equals(section) || "audio".equals(section) ? section : "news";
    }

    public static void setStartSection(Context context, String section) {
        if (!"news".equals(section) && !"messages".equals(section)
                && !"friends".equals(section) && !"audio".equals(section))
            throw new IllegalArgumentException("Invalid start section");
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(START_SECTION, section).apply();
    }
}
