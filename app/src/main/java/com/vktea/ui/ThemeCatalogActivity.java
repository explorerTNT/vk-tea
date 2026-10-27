package com.vktea.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.vktea.R;
import com.vktea.auth.Session;
import com.vktea.auth.TokenRevealActivity;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Local, data-only palette selection and Android document-provider exchange. */
public class ThemeCatalogActivity extends TeaActivity {
    private static final int IMPORT = 101;
    private static final int EXPORT = 102;
    // No queue: stalled document providers cannot create unlimited threads or pending operations.
    private static final ThreadPoolExecutor IO = boundedPool();
    private static final ThreadPoolExecutor ABORT = boundedPool();
    private static final ScheduledThreadPoolExecutor TIMEOUTS = timeoutExecutor();
    private static final long TRANSFER_TIMEOUT_SECONDS = 15;

    private static ThreadPoolExecutor boundedPool() {
        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new SynchronousQueue<>());
    }

    private static ScheduledThreadPoolExecutor timeoutExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    @Override protected void onCreate(Bundle state) {
        boolean pending = Session.hasPendingReveal(this);
        super.onCreate(pending ? null : state);
        if (TokenRevealActivity.redirectIfPending(this)) return;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
        TextView back = row(getString(R.string.accounts_back));
        back.setOnClickListener(v -> finish());
        root.addView(back);
        TextView title = row(getString(R.string.theme_catalog_title));
        title.setTextSize(22);
        root.addView(title);
        TextView description = row(getString(R.string.theme_catalog_description));
        description.setTextSize(14);
        root.addView(description);
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        CoffeePalette selected = AppearanceSettings.getPalette(this);
        for (CoffeePalette palette : CoffeePalette.builtIns()) {
            String text = palette.name;
            if (selected != null && selected.id.equals(palette.id)) text += " · " + getString(R.string.theme_catalog_selected);
            TextView item = row(text);
            item.setOnClickListener(v -> preview(palette));
            list.addView(item);
        }
        if (selected != null && "custom".equals(selected.id)) {
            TextView item = row(selected.name + " · " + getString(R.string.theme_catalog_selected));
            item.setOnClickListener(v -> preview(selected));
            list.addView(item);
        }
        TextView createRow = row(getString(R.string.theme_catalog_create));
        createRow.setOnClickListener(v -> editPalette());
        list.addView(createRow);
        TextView importRow = row(getString(R.string.theme_catalog_import));
        importRow.setOnClickListener(v -> pick(false));
        list.addView(importRow);
        TextView exportRow = row(getString(R.string.theme_catalog_export));
        exportRow.setOnClickListener(v -> pick(true));
        list.addView(exportRow);
        setContentView(root);
        ViewCompat.requestApplyInsets(root);
    }

    private TextView row(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(16);
        int p = (int) (16 * getResources().getDisplayMetrics().density + .5f);
        view.setPadding(p, p, p, p);
        TypedValue ripple = new TypedValue();
        if (getTheme().resolveAttribute(androidx.appcompat.R.attr.selectableItemBackground, ripple, true)
                && ripple.resourceId != 0) view.setBackgroundResource(ripple.resourceId);
        return view;
    }

    private void preview(CoffeePalette palette) {
        LinearLayout preview = new LinearLayout(this);
        preview.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density + .5f);
        preview.setPadding(pad, pad, pad, pad);
        preview.setBackgroundColor(palette.colors.get("root_background"));
        TextView toolbar = new TextView(this);
        toolbar.setText(palette.name);
        toolbar.setTextColor(android.graphics.Color.WHITE);
        toolbar.setBackgroundColor(palette.colors.get("brand"));
        toolbar.setPadding(pad, pad, pad, pad);
        preview.addView(toolbar);
        TextView sample = new TextView(this);
        sample.setText(R.string.theme_catalog_preview_text);
        sample.setTextSize(16);
        sample.setTextColor(palette.colors.get("main_text"));
        sample.setBackgroundColor(palette.colors.get("card_background"));
        sample.setPadding(pad, pad, pad, pad);
        preview.addView(sample);
        TextView accent = new TextView(this);
        accent.setText(R.string.theme_catalog_preview);
        accent.setTextColor(palette.colors.get("accent"));
        accent.setBackgroundColor(palette.colors.get("card_background"));
        accent.setPadding(pad, 0, pad, pad);
        accent.setGravity(Gravity.START);
        preview.addView(accent);
        boolean readableChrome = palette.hasReadableChrome();
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(preview);
        if (!readableChrome) {
            TextView warning = new TextView(this);
            warning.setText(R.string.theme_catalog_chrome_contrast);
            warning.setPadding(pad, pad, pad, pad);
            content.addView(warning);
        }
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(palette.name).setView(content)
                .setPositiveButton(R.string.theme_catalog_apply, (d, w) -> {
                    try { AppearanceSettings.setPalette(this, palette); }
                    catch (UnsupportedOperationException ex) { Toast.makeText(this, AppearanceSettings.supportsCustomPalettes() ? R.string.theme_catalog_custom_apply_error : R.string.theme_catalog_custom_unavailable, Toast.LENGTH_LONG).show(); }
                    catch (IllegalArgumentException ex) { Toast.makeText(this, R.string.theme_catalog_chrome_contrast, Toast.LENGTH_LONG).show(); }
                }).setNegativeButton(android.R.string.cancel, null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(readableChrome);
    }

    private void pick(boolean export) {
        Intent intent = new Intent(export ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("application/json");
        if (export) intent.putExtra(Intent.EXTRA_TITLE, getString(R.string.theme_catalog_file_title));
        try { startActivityForResult(intent, export ? EXPORT : IMPORT); }
        catch (ActivityNotFoundException ex) { Toast.makeText(this, R.string.theme_catalog_picker_unavailable, Toast.LENGTH_LONG).show(); }
    }

    private void editPalette() {
        if (!AppearanceSettings.supportsCustomPalettes()) {
            Toast.makeText(this, R.string.theme_catalog_custom_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        CoffeePalette current = AppearanceSettings.getPalette(this);
        CoffeePalette base = current != null ? current : CoffeePalette.builtIns().get(0);
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density + .5f);
        fields.setPadding(pad, 0, pad, 0);
        TextView contrastHint = new TextView(this);
        contrastHint.setText(R.string.theme_catalog_chrome_contrast);
        fields.addView(contrastHint);
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint(R.string.theme_catalog_name);
        name.setText(current != null && "custom".equals(current.id) ? current.name : getString(R.string.theme_catalog_custom_name));
        fields.addView(name);
        String[] labels = getResources().getStringArray(R.array.theme_catalog_color_names);
        java.util.List<EditText> inputs = new java.util.ArrayList<>();
        for (int i = 0; i < CoffeePalette.KEYS.size(); i++) {
            TextView label = new TextView(this);
            label.setText(labels[i]);
            fields.addView(label);
            EditText color = new EditText(this);
            color.setSingleLine(true);
            color.setHint(R.string.theme_catalog_color_hint);
            color.setText(String.format(java.util.Locale.ROOT, "#%06X", base.colors.get(CoffeePalette.KEYS.get(i)) & 0xffffff));
            fields.addView(color);
            inputs.add(color);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(fields);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.theme_catalog_create).setView(scroll)
                .setPositiveButton(R.string.theme_catalog_preview, null)
                .setNegativeButton(android.R.string.cancel, null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                java.util.Map<String, Integer> colors = new java.util.LinkedHashMap<>();
                for (int i = 0; i < CoffeePalette.KEYS.size(); i++) {
                    String text = inputs.get(i).getText().toString().trim();
                    if (!text.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException();
                    colors.put(CoffeePalette.KEYS.get(i), (int) (0xff000000L | Long.parseLong(text.substring(1), 16)));
                }
                String paletteName = name.getText().toString().trim();
                // Apply the same name and color rules as the portable format before preview.
                ThemeExchange.encode(paletteName, "custom", colors);
                CoffeePalette palette = CoffeePalette.create("custom", paletteName, colors);
                dialog.dismiss();
                preview(palette);
            } catch (IllegalArgumentException ex) {
                Toast.makeText(this, R.string.theme_catalog_bad_color, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if ((request != IMPORT && request != EXPORT) || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (!"content".equalsIgnoreCase(uri.getScheme())) {
            Toast.makeText(this, request == EXPORT ? R.string.theme_catalog_save_error : R.string.theme_catalog_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        byte[] bytes = null;
        if (request == EXPORT) {
            try { bytes = exportCurrent(); }
            catch (IllegalArgumentException ex) { Toast.makeText(this, R.string.theme_catalog_save_error, Toast.LENGTH_LONG).show(); return; }
        }
        new Transfer(this, uri, request == EXPORT, bytes).start();
    }

    /** An operation has one result; a timed-out provider cannot deliver a late import or success. */
    private static final class Transfer implements Runnable {
        private final android.content.Context app;
        private final WeakReference<ThemeCatalogActivity> owner;
        private final Uri uri;
        private final boolean export;
        private final byte[] bytes;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final CancellationSignal signal = new CancellationSignal();
        private final AtomicReference<ParcelFileDescriptor> descriptor = new AtomicReference<>();
        private final AtomicBoolean finished = new AtomicBoolean();
        private ScheduledFuture<?> timeout;

        Transfer(ThemeCatalogActivity activity, Uri uri, boolean export, byte[] bytes) {
            app = activity.getApplicationContext();
            owner = new WeakReference<>(activity);
            this.uri = uri;
            this.export = export;
            this.bytes = bytes;
        }

        void start() {
            timeout = TIMEOUTS.schedule(this::timedOut, TRANSFER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            try { IO.execute(this); }
            catch (RejectedExecutionException busy) { complete(null, false); }
        }

        @Override public void run() {
            ThemeExchange.Document document = null;
            boolean success = false;
            try {
                ParcelFileDescriptor fd = app.getContentResolver().openFileDescriptor(uri, export ? "wt" : "r", signal);
                if (fd == null) throw new java.io.IOException("No descriptor");
                descriptor.set(fd);
                if (finished.get()) { fd.close(); return; }
                if (export) {
                    writeDocument(new ParcelFileDescriptor.AutoCloseOutputStream(fd), bytes);
                } else {
                    try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
                        document = ThemeExchange.decode(input);
                    }
                }
                success = true; // Includes successful close.
            } catch (Exception ignored) { }
            finally { descriptor.set(null); }
            complete(document, success);
        }

        private void timedOut() {
            if (!finished.compareAndSet(false, true)) return;
            timeout.cancel(false);
            report(null, false);
            // Both Binder cancellation and descriptor closure may block in a hostile provider.
            // Each is bounded and neither runs on the main thread or the timeout thread.
            try { ABORT.execute(() -> { try { signal.cancel(); } catch (RuntimeException ignored) { } }); }
            catch (RejectedExecutionException ignored) { }
            ParcelFileDescriptor fd = descriptor.get();
            if (fd != null) {
                try { ABORT.execute(() -> { try { fd.close(); } catch (Exception ignored) { } }); }
                catch (RejectedExecutionException ignored) { }
            }
        }

        private void complete(ThemeExchange.Document document, boolean success) {
            if (!finished.compareAndSet(false, true)) return;
            timeout.cancel(false);
            report(document, success);
        }

        private void report(ThemeExchange.Document document, boolean success) {
            main.post(() -> {
                ThemeCatalogActivity activity = owner.get();
                if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                if (!export) activity.showImported(success ? document : null);
                else Toast.makeText(activity, success ? R.string.theme_catalog_saved : R.string.theme_catalog_save_error, Toast.LENGTH_SHORT).show();
            });
        }
    }

    // Package-private so failures during close can be verified without a real document provider.
    static void writeDocument(OutputStream stream, byte[] bytes) throws java.io.IOException {
        try (OutputStream out = stream) {
            out.write(bytes);
            out.flush();
        }
    }

    private void showImported(ThemeExchange.Document document) {
        if (document == null) { Toast.makeText(this, R.string.theme_catalog_invalid, Toast.LENGTH_LONG).show(); return; }
        if ("system".equals(document.getPalette()) || "light".equals(document.getPalette())) {
            int modeLabel = "system".equals(document.getPalette()) ? 0 : 1;
            new AlertDialog.Builder(this).setTitle(document.getName())
                    .setMessage(getResources().getStringArray(R.array.settings_theme_options)[modeLabel])
                    .setPositiveButton(R.string.theme_catalog_apply, (d, w) -> {
                        AppearanceSettings.setMode(this, document.getPalette()); recreate();
                    }).setNegativeButton(android.R.string.cancel, null).show();
            return;
        }
        if ("custom".equals(document.getPalette())) {
            try { preview(CoffeePalette.create("custom", document.getName(), document.getColors())); }
            catch (IllegalArgumentException ex) { Toast.makeText(this, R.string.theme_catalog_invalid, Toast.LENGTH_LONG).show(); }
            return;
        }
        for (CoffeePalette palette : CoffeePalette.builtIns()) {
            if (palette.id.equals(document.getPalette())) { preview(palette); return; }
        }
        Toast.makeText(this, R.string.theme_catalog_invalid, Toast.LENGTH_LONG).show();
    }

    private byte[] exportCurrent() {
        CoffeePalette selected = AppearanceSettings.getPalette(this);
        if (selected == null) {
            String mode = AppearanceSettings.getMode(this);
            return ThemeExchange.encode("VK Tea", mode);
        }
        if ("custom".equals(selected.id)) return ThemeExchange.encode(selected.name, "custom", selected.colors);
        return ThemeExchange.encode("Coffee " + selected.id.replace('_', ' '), selected.id);
    }
}
