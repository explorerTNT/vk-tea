package com.vktea.feed;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowContentResolver;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Exercises the real worker and helper through owned content fixtures, with no network or account. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class PhotoExportViewModelTest {
    private static final String SOURCE = "content://synthetic.photo.viewmodel/photo";
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==");
    private final ViewModelStore store = new ViewModelStore();
    private Application app;
    private BlockingProvider provider;
    private PhotoExportViewModel model;

    @Before
    public void setup() throws Exception {
        app = RuntimeEnvironment.getApplication();
        provider = new BlockingProvider();
        provider.file = File.createTempFile("synthetic-viewmodel-source", ".png", app.getCacheDir());
        try (FileOutputStream output = new FileOutputStream(provider.file)) { output.write(PNG); }
        ProviderInfo info = new ProviderInfo();
        info.authority = "synthetic.photo.viewmodel";
        provider.attachInfo(app, info);
        ShadowContentResolver.registerProviderInternal(info.authority, provider);
        model = models().get(PhotoExportViewModel.class);
    }

    @After
    public void cleanup() {
        provider.gate.countDown();
        store.clear();
        provider.file.delete();
        File[] files = new File(app.getCacheDir(), "photo-share").listFiles();
        if (files != null) for (File file : files) file.delete();
    }

    @Test
    public void retainedBusyRequestCompletesOnceAndHandledResultDoesNotReplayToReplacementObserver() throws Exception {
        provider.gate = new CountDownLatch(1);
        AtomicInteger events = new AtomicInteger();
        Observer<PhotoExportViewModel.State> first = state -> consume(state, events);
        model.state().observeForever(first);
        model.start(SOURCE, PhotoExportViewModel.Kind.SHARE);
        assertTrue(provider.opened.await(3, TimeUnit.SECONDS));
        assertTrue(model.isBusy());
        model.start(SOURCE, PhotoExportViewModel.Kind.SHARE);
        PhotoExportViewModel retained = models().get(PhotoExportViewModel.class);
        assertSame(model, retained);
        assertEquals(1, provider.requests.get());
        provider.gate.countDown();
        await(() -> !model.isBusy());

        PhotoExportViewModel.State completed = model.state().getValue();
        assertEquals(PhotoExportViewModel.Kind.SHARE, completed.kind);
        assertNotNull(completed.photo);
        assertArrayEquals(PNG, Files.readAllBytes(completed.photo.file.toPath()));
        assertEquals(1, events.get());
        model.state().removeObserver(first);
        Observer<PhotoExportViewModel.State> replacement = state -> consume(state, events);
        retained.state().observeForever(replacement);
        assertEquals(1, events.get());
        retained.state().removeObserver(replacement);
    }

    @Test
    public void errorHasNoPayloadAndWaitsForAnExplicitNewOperation() throws Exception {
        model.start("content://synthetic.photo.viewmodel/missing", PhotoExportViewModel.Kind.SHARE);
        await(() -> !model.isBusy());
        assertEquals(PhotoExportViewModel.Kind.ERROR, model.state().getValue().kind);
        assertNull(model.state().getValue().photo);
        assertEquals(1, provider.requests.get());
        assertSame(model, models().get(PhotoExportViewModel.class));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, provider.requests.get());

        model.start(SOURCE, PhotoExportViewModel.Kind.DOCUMENT);
        await(() -> !model.isBusy());
        assertEquals(PhotoExportViewModel.Kind.DOCUMENT, model.state().getValue().kind);
        assertNotNull(model.state().getValue().photo);
        assertFalse(model.state().getValue().handled);
        assertEquals(2, provider.requests.get());
    }

    @Test
    public void clearingOwnerInterruptsPendingSourceAndRemovesPartialFile() throws Exception {
        provider.gate = new CountDownLatch(1);
        model.start(SOURCE, PhotoExportViewModel.Kind.SHARE);
        assertTrue(provider.opened.await(3, TimeUnit.SECONDS));
        store.clear();
        await(() -> provider.interrupted.get() && shareCount() == 0);
        assertEquals(1, provider.requests.get());
        assertArrayEquals(PNG, Files.readAllBytes(provider.file.toPath()));
    }

    private ViewModelProvider models() {
        return new ViewModelProvider(store, new ViewModelProvider.AndroidViewModelFactory(app));
    }

    private static void consume(PhotoExportViewModel.State state, AtomicInteger events) {
        if (state != null && !state.busy && !state.handled) {
            state.handled = true;
            events.incrementAndGet();
        }
    }

    private int shareCount() {
        File[] files = new File(app.getCacheDir(), "photo-share").listFiles();
        return files == null ? 0 : files.length;
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(1);
        }
        assertTrue("Offline export worker did not complete", condition.getAsBoolean());
    }

    public static final class BlockingProvider extends ContentProvider {
        File file;
        CountDownLatch gate = new CountDownLatch(0);
        final CountDownLatch opened = new CountDownLatch(1);
        final AtomicInteger requests = new AtomicInteger();
        final AtomicBoolean interrupted = new AtomicBoolean();
        @Override public boolean onCreate() { return true; }
        @Override public String getType(Uri uri) { return "image/png"; }
        @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int update(Uri uri, ContentValues values, String s, String[] a) { return 0; }
        @Override public int delete(Uri uri, String s, String[] a) { return 0; }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            requests.incrementAndGet();
            opened.countDown();
            try {
                if (!gate.await(5, TimeUnit.SECONDS)) throw new FileNotFoundException("Synthetic timeout");
            } catch (InterruptedException ignored) {
                interrupted.set(true);
                throw new FileNotFoundException("Synthetic cancellation");
            }
            if (!"photo".equals(uri.getLastPathSegment())) throw new FileNotFoundException("SYNTHETIC_PRIVATE_SOURCE");
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
    }
}
