package com.vktea.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;

import com.vktea.BuildConfig;
import com.vktea.R;
import com.vktea.auth.AccountActivity;
import com.vktea.auth.AuthActivity;
import com.vktea.auth.Session;

public class SettingsFragment extends Fragment {
    private static final String[] MODES = {"system", "light", "dark"};
    private TextView themeSummary;
    private static final java.util.concurrent.ExecutorService CACHE_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        ScrollView scroll = new ScrollView(requireContext());
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root);

        addSection(root, R.string.settings_account);
        addRow(root, R.string.settings_accounts, R.string.settings_accounts_summary,
                () -> startActivity(new Intent(requireContext(), AccountActivity.class)));
        addRow(root, R.string.settings_logout, 0, this::confirmLogout);

        addSection(root, R.string.settings_appearance);
        themeSummary = addRow(root, R.string.settings_theme, 0, this::chooseTheme);
        updateThemeSummary();

        addSection(root, R.string.settings_feed);
        addSwitch(root, R.string.settings_stories, R.string.settings_stories_summary,
                UserSettings.showStories(requireContext()), enabled -> UserSettings.setShowStories(requireContext(), enabled));
        addSwitch(root, R.string.settings_new_posts, R.string.settings_new_posts_summary,
                UserSettings.checkNewPosts(requireContext()), enabled -> UserSettings.setCheckNewPosts(requireContext(), enabled));

        addSection(root, R.string.settings_storage);
        addRow(root, R.string.settings_clear_feed_cache, R.string.settings_clear_feed_cache_summary, this::clearFeedCache);
        TextView imageCacheSummary = addRow(root, R.string.settings_clear_image_cache,
                R.string.settings_clear_image_cache_summary, null);
        View imageCacheRow = (View) imageCacheSummary.getParent();
        TypedValue ripple = new TypedValue();
        if (requireContext().getTheme().resolveAttribute(androidx.appcompat.R.attr.selectableItemBackground, ripple, true)
                && ripple.resourceId != 0) imageCacheRow.setBackgroundResource(ripple.resourceId);
        imageCacheRow.setOnClickListener(v -> clearImageCache(imageCacheRow, imageCacheSummary));

        addSection(root, R.string.settings_about);
        TextView version = addRow(root, R.string.settings_version, 0, null);
        version.setText(BuildConfig.VERSION_NAME);
        return scroll;
    }

    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private void addSection(LinearLayout root, int title) {
        TextView view = new TextView(requireContext());
        view.setText(title);
        view.setTextSize(13);
        TypedValue secondary = new TypedValue();
        if (requireContext().getTheme().resolveAttribute(android.R.attr.textColorSecondary, secondary, true)) {
            if (secondary.resourceId != 0) view.setTextColor(androidx.core.content.ContextCompat.getColorStateList(requireContext(), secondary.resourceId));
            else view.setTextColor(secondary.data);
        }
        view.setPadding(dp(16), dp(20), dp(16), dp(8));
        root.addView(view);
    }

    /** Returns the secondary label so callers can update its value. */
    private TextView addRow(LinearLayout root, int title, int summary, @Nullable Runnable click) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        TextView name = new TextView(requireContext());
        name.setText(title);
        name.setTextSize(16);
        row.addView(name);
        TextView detail = new TextView(requireContext());
        detail.setTextSize(13);
        if (summary != 0) detail.setText(summary);
        row.addView(detail);
        if (click != null) {
            TypedValue ripple = new TypedValue();
            if (requireContext().getTheme().resolveAttribute(androidx.appcompat.R.attr.selectableItemBackground, ripple, true)
                    && ripple.resourceId != 0) row.setBackgroundResource(ripple.resourceId);
            row.setOnClickListener(v -> click.run());
        }
        root.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return detail;
    }

    private interface Toggle { void set(boolean enabled); }

    private void addSwitch(LinearLayout root, int title, int summary, boolean checked, Toggle toggle) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout labels = new LinearLayout(requireContext());
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(requireContext());
        name.setText(title);
        name.setTextSize(16);
        labels.addView(name);
        TextView detail = new TextView(requireContext());
        detail.setText(summary);
        detail.setTextSize(13);
        labels.addView(detail);
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        SwitchCompat control = new SwitchCompat(requireContext());
        control.setContentDescription(getString(title));
        control.setChecked(checked);
        control.setOnCheckedChangeListener((button, enabled) -> toggle.set(enabled));
        row.addView(control);
        row.setOnClickListener(v -> control.toggle());
        root.addView(row);
    }

    private void chooseTheme() {
        int selected = modeIndex(AppearanceSettings.getMode(requireContext()));
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_theme)
                .setSingleChoiceItems(R.array.settings_theme_options, selected, (dialog, which) -> {
                    dialog.dismiss();
                    AppearanceSettings.setMode(requireContext(), MODES[which]);
                    updateThemeSummary();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateThemeSummary() {
        themeSummary.setText(getResources().getStringArray(R.array.settings_theme_options)[modeIndex(AppearanceSettings.getMode(requireContext()))]);
    }

    private int modeIndex(String mode) {
        for (int i = 0; i < MODES.length; i++) if (MODES[i].equals(mode)) return i;
        return 0;
    }

    private void clearFeedCache() {
        boolean cleared = Session.clearFeedCache(requireContext());
        Toast.makeText(requireContext(), cleared ? R.string.settings_cache_cleared : R.string.settings_cache_error, Toast.LENGTH_SHORT).show();
    }

    private void clearImageCache(View row, TextView summary) {
        android.content.Context application = requireContext().getApplicationContext();
        row.setEnabled(false);
        summary.setText(R.string.settings_image_cache_clearing);
        try {
            com.bumptech.glide.Glide.get(application).clearMemory();
        } catch (RuntimeException failure) {
            row.setEnabled(true);
            summary.setText(R.string.settings_image_cache_error);
            return;
        }
        Handler main = new Handler(Looper.getMainLooper());
        CACHE_EXECUTOR.execute(() -> {
            boolean success;
            try {
                com.bumptech.glide.Glide.get(application).clearDiskCache();
                success = true;
            } catch (RuntimeException failure) {
                success = false;
            }
            final boolean cleared = success;
            main.post(() -> {
                if (!isAdded() || getView() == null || !row.isAttachedToWindow()) return;
                row.setEnabled(true);
                summary.setText(cleared ? R.string.settings_image_cache_cleared : R.string.settings_image_cache_error);
            });
        });
    }

    private void confirmLogout() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_logout_confirm)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    Session.clear(requireContext());
                    Intent intent = new Intent(requireContext(), Session.hasSavedAccounts(requireContext())
                            ? AccountActivity.class : AuthActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
