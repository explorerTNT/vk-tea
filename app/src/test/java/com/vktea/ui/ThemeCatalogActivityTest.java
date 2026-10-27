package com.vktea.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;

import com.vktea.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class ThemeCatalogActivityTest {
    private Context context;
    private File fixture;
    private ActivityController<ThemeCatalogActivity> controller;
    private ThemeCatalogActivity activity;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
        AppearanceSettings.apply(context);
        ShadowToast.reset();
        controller = Robolectric.buildActivity(ThemeCatalogActivity.class).setup();
        activity = controller.get();
    }

    @After public void tearDown() {
        if (ShadowDialog.getLatestDialog() != null) ShadowDialog.getLatestDialog().dismiss();
        if (fixture != null) fixture.delete();
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    @Test public void builtinPreviewAppliesOnlyAfterConfirmationAndSurvivesRecreation() {
        CoffeePalette palette = CoffeePalette.builtIns().get(1);
        TextView row = findText(activity.findViewById(android.R.id.content), palette.name);
        assertNotNull(row);
        row.performClick();
        assertNull(AppearanceSettings.getPalette(context));
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        TextView sample = findText(dialog.getWindow().getDecorView(), activity.getString(R.string.theme_catalog_preview_text));
        assertNotNull(sample);
        assertEquals((int) palette.colors.get("card_background"), ((ColorDrawable) sample.getBackground()).getColor());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        // AppCompat dispatches the positive-button listener through the main looper.
        shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(AppearanceSettings.getPalette(context));
        assertEquals(palette.id, AppearanceSettings.getPalette(context).id);
        assertEquals(AppearanceSettings.DARK, AppearanceSettings.getMode(context));
        controller.recreate();
        ThemeCatalogActivity restored = controller.get();
        assertEquals(palette.id, AppearanceSettings.getPalette(restored).id);
        assertNotNull(findText(restored.findViewById(android.R.id.content),
                palette.name + " · " + restored.getString(R.string.theme_catalog_selected)));
    }

    @Test public void invalidContentDocumentDoesNotChangeCurrentPalette() throws Exception {
        AppearanceSettings.setPalette(activity, CoffeePalette.builtIns().get(1));
        fixture = File.createTempFile("theme-invalid-synthetic", ".json", context.getCacheDir());
        try (FileOutputStream out = new FileOutputStream(fixture)) {
            out.write("{\"version\":1,\"name\":\"A\",\"palette\":\"custom\",\"url\":\"https://example.invalid\"}".getBytes(StandardCharsets.UTF_8));
        }
        SyntheticProvider provider = new SyntheticProvider();
        provider.file = fixture;
        ProviderInfo info = new ProviderInfo();
        info.authority = "synthetic.theme.test";
        provider.attachInfo(context, info);
        ShadowContentResolver.registerProviderInternal(info.authority, provider);
        // Recreate the activity before starting the asynchronous import so that a queued palette refresh cannot discard the result.
        controller.recreate();
        activity = controller.get();
        activity.onActivityResult(101, Activity.RESULT_OK, new Intent().setData(Uri.parse("content://synthetic.theme.test/document")));
        await(() -> activity.getString(R.string.theme_catalog_invalid).equals(ShadowToast.getTextOfLatestToast()));
        assertTrue(provider.opened);
        assertEquals("amoled", AppearanceSettings.getPalette(context).id);
        assertEquals(AppearanceSettings.DARK, AppearanceSettings.getMode(context));
    }

    @Test public void fileUrisAreRejectedWithoutReadingOrChangingAppearance() {
        activity.onActivityResult(101, Activity.RESULT_OK, new Intent().setData(Uri.parse("file:///nonexistent-theme.json")));
        assertEquals(activity.getString(R.string.theme_catalog_invalid), ShadowToast.getTextOfLatestToast());
        assertNull(AppearanceSettings.getPalette(context));
        activity.onActivityResult(102, Activity.RESULT_OK, new Intent().setData(Uri.parse("file:///nonexistent-theme.json")));
        assertEquals(activity.getString(R.string.theme_catalog_save_error), ShadowToast.getTextOfLatestToast());
        assertNull(AppearanceSettings.getPalette(context));
    }

    @Test public void failedOutputCloseDoesNotReportSuccessfulWrite() {
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public void close() throws IOException { throw new IOException("synthetic close failure"); }
        };
        assertThrows(IOException.class, () -> ThemeCatalogActivity.writeDocument(output, new byte[]{1, 2, 3}));
    }

    @Test public void timedOutImportIgnoresProviderResultAfterItUnblocks() throws Exception {
        fixture = File.createTempFile("theme-valid-synthetic", ".json", context.getCacheDir());
        try (FileOutputStream out = new FileOutputStream(fixture)) {
            out.write("{\"version\":1,\"name\":\"Example\",\"palette\":\"dark\"}".getBytes(StandardCharsets.UTF_8));
        }
        SyntheticProvider provider = new SyntheticProvider();
        provider.file = fixture;
        provider.gate = new CountDownLatch(1);
        ProviderInfo info = new ProviderInfo();
        info.authority = "synthetic.theme.blocking";
        provider.attachInfo(context, info);
        ShadowContentResolver.registerProviderInternal(info.authority, provider);
        Class<?> transferType = Class.forName("com.vktea.ui.ThemeCatalogActivity$Transfer");
        java.lang.reflect.Constructor<?> constructor = transferType.getDeclaredConstructor(ThemeCatalogActivity.class, Uri.class, boolean.class, byte[].class);
        constructor.setAccessible(true);
        Object transfer = constructor.newInstance(activity, Uri.parse("content://synthetic.theme.blocking/document"), false, null);
        java.lang.reflect.Method start = transferType.getDeclaredMethod("start");
        java.lang.reflect.Method timedOut = transferType.getDeclaredMethod("timedOut");
        start.setAccessible(true);
        timedOut.setAccessible(true);
        android.app.Dialog initialDialog = ShadowDialog.getLatestDialog();
        try {
            start.invoke(transfer);
            assertTrue(provider.entered.await(3, TimeUnit.SECONDS));
            timedOut.invoke(transfer);
            await(() -> activity.getString(R.string.theme_catalog_invalid).equals(ShadowToast.getTextOfLatestToast()));
        } finally { provider.gate.countDown(); }
        java.lang.reflect.Field executor = ThemeCatalogActivity.class.getDeclaredField("IO");
        executor.setAccessible(true);
        ThreadPoolExecutor io = (ThreadPoolExecutor) executor.get(null);
        await(() -> io.getActiveCount() == 0);
        assertNull(AppearanceSettings.getPalette(context));
        assertSame(initialDialog, ShadowDialog.getLatestDialog());
    }

    private static TextView findText(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView) root).getText())) return (TextView) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(1);
        }
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue("Timed out waiting for the import result", condition.getAsBoolean());
    }

    public static final class SyntheticProvider extends ContentProvider {
        File file;
        volatile boolean opened;
        CountDownLatch gate;
        final CountDownLatch entered = new CountDownLatch(1);
        @Override public boolean onCreate() { return true; }
        @Override public String getType(Uri uri) { return "application/json"; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            opened = true;
            entered.countDown();
            if (gate != null) {
                try { gate.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new FileNotFoundException("Interrupted"); }
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
    }
}
