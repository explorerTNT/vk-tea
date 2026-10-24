package com.vktea.feed;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.content.pm.PackageManager;
import android.content.res.XmlResourceParser;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.Bundle;
import android.provider.MediaStore;

import com.vktea.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Owned synthetic files/providers only. No test opens a network connection. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PhotoExportsTest {
    private static final String AUTHORITY = "synthetic.photo.exports";
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==");
    private Context context;
    private SyntheticProvider provider;
    private SyntheticProvider media;

    @Before
    public void setup() {
        context = RuntimeEnvironment.getApplication();
        provider = register(AUTHORITY);
        media = register("media");
    }

    @After
    public void removeTestFiles() {
        File directory = new File(context.getCacheDir(), "photo-share");
        File[] shares = directory.listFiles();
        if (shares != null) for (File file : shares) file.delete();
        for (File file : provider.files.values()) file.delete();
        for (File file : media.files.values()) file.delete();
    }

    @Test
    public void sharePreservesOriginalBytesAndInfersMimeFromDataInsteadOfFileName() throws Exception {
        File source = fixture("synthetic-misnamed", ".jpg", PNG);
        PhotoExports.SavedPhoto photo = PhotoExports.prepareShare(context, sourceUri(source));

        assertEquals("image/png", photo.mimeType);
        assertTrue(photo.file.getName().endsWith(".png"));
        assertEquals(new File(context.getCacheDir(), "photo-share"), photo.file.getParentFile());
        assertArrayEquals(PNG, Files.readAllBytes(photo.file.toPath()));
        assertArrayEquals(PNG, Files.readAllBytes(source.toPath()));
    }

    @Test
    public void contentSourcesUseDetectedImageFormatAndKeepEveryByte() throws Exception {
        File original = fixture("synthetic-content", ".bin", PNG);
        provider.files.put("source", original);
        PhotoExports.SavedPhoto photo = PhotoExports.prepareShare(context,
                "content://" + AUTHORITY + "/source");
        assertEquals("image/png", photo.mimeType);
        assertArrayEquals(PNG, Files.readAllBytes(photo.file.toPath()));
        // The provider intentionally declares image/jpeg for every source.
        assertEquals("image/jpeg", context.getContentResolver().getType(Uri.parse("content://" + AUTHORITY + "/source")));
    }

    @Test
    public void supportedHeaderFormatsAreNotConvertedToJpeg() throws Exception {
        byte[][] formats = {
                {(byte) 255, (byte) 216, (byte) 255, (byte) 224, 0, 2, 0, 0, 0, 0, (byte) 255, (byte) 217},
                "GIF89a\u0001\u0000\u0001\u0000\u0000\u0000\u0000;".getBytes(StandardCharsets.US_ASCII),
                {'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'E', 'B', 'P'}
        };
        String[] mime = {"image/jpeg", "image/gif", "image/webp"};
        String[] extension = {".jpg", ".gif", ".webp"};
        for (int index = 0; index < formats.length; index++) {
            File source = fixture("synthetic-header", ".bin", formats[index]);
            PhotoExports.SavedPhoto photo = PhotoExports.prepareShare(context, sourceUri(source));
            assertEquals(mime[index], photo.mimeType);
            assertTrue(photo.file.getName().endsWith(extension[index]));
            assertArrayEquals(formats[index], Files.readAllBytes(photo.file.toPath()));
        }
    }

    @Test
    public void malformedPrivateTextAndForbiddenNetworkInputsFailWithoutLeakingSourceOrCause() throws Exception {
        File privateText = fixture("synthetic-private-text", ".xml",
                "SYNTHETIC_PRIVATE_PREFS_AND_TOKEN".getBytes(StandardCharsets.UTF_8));
        assertSafeFailure(() -> PhotoExports.prepareShare(context, sourceUri(privateText)));
        assertSafeFailure(() -> PhotoExports.prepareShare(context, "http://synthetic.invalid/private?token=synthetic"));
        assertSafeFailure(() -> PhotoExports.prepareShare(context, "https://synthetic-user:synthetic-password@synthetic.invalid/private"));
        assertEquals(0, shareCount());
    }

    @Test
    public void byteLimitRemovesPartialCacheFile() throws Exception {
        File source = fixture("synthetic-limited", ".png", PNG);
        assertSafeFailure(() -> PhotoExports.prepareShare(context, sourceUri(source), 16));
        assertEquals(0, shareCount());
        assertArrayEquals(PNG, Files.readAllBytes(source.toPath()));
    }

    @Test
    public void cacheCleanupDeletesOnlyOwnedExpiredFilesAndKeepsFreshShares() throws Exception {
        File source = fixture("synthetic-cleanup-source", ".png", PNG);
        PhotoExports.SavedPhoto first = PhotoExports.prepareShare(context, sourceUri(source));
        File old = new File(first.file.getParentFile(), "photo-synthetic-expired.png");
        File unrelated = new File(first.file.getParentFile(), "synthetic-other-expired.png");
        try (FileOutputStream output = new FileOutputStream(old)) { output.write(PNG); }
        try (FileOutputStream output = new FileOutputStream(unrelated)) { output.write(PNG); }
        long expired = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2);
        assertTrue(old.setLastModified(expired));
        assertTrue(unrelated.setLastModified(expired));
        PhotoExports.prepareShare(context, sourceUri(source));
        assertFalse(old.exists());
        assertTrue(first.file.exists());
        assertTrue(unrelated.exists());
    }

    @Test
    public void safCopiesIntoEmptyDocumentWithoutTruncatingExistingOrAliasedSource() throws Exception {
        File source = fixture("synthetic-source", ".png", PNG);
        File destination = fixture("synthetic-destination", ".png", new byte[0]);
        provider.files.put("destination", destination);
        provider.files.put("source", source);
        Uri target = Uri.parse("content://" + AUTHORITY + "/destination");
        PhotoExports.copyTo(context, sourceUri(source), target);
        assertEquals("rw", provider.lastOpenMode);
        assertArrayEquals(PNG, Files.readAllBytes(destination.toPath()));
        assertSafeFailure(() -> PhotoExports.copyTo(context, sourceUri(source), target));
        assertSafeFailure(() -> PhotoExports.copyTo(context, "content://" + AUTHORITY + "/source",
                Uri.parse("content://" + AUTHORITY + "/source")));
        assertArrayEquals(PNG, Files.readAllBytes(source.toPath()));
        assertArrayEquals(PNG, Files.readAllBytes(destination.toPath()));
        assertEquals(0, shareCount());
    }

    @Test
    public void failedSafWriteDeletesOnlyItsPreviouslyVerifiedEmptyDocument() throws Exception {
        File source = fixture("synthetic-write-failure-source", ".png", PNG);
        File destination = fixture("synthetic-empty-failure-target", ".png", new byte[0]);
        provider.files.put("destination", destination);
        provider.readOnlyTarget = true;
        assertSafeFailure(() -> PhotoExports.copyTo(context, sourceUri(source),
                Uri.parse("content://" + AUTHORITY + "/document/destination")));
        assertTrue(provider.deleted);
        assertFalse(destination.exists());
        assertArrayEquals(PNG, Files.readAllBytes(source.toPath()));
        assertEquals(0, shareCount());
    }

    @Test
    @Config(sdk = 29)
    public void galleryPublishesOnlyAfterOriginalBytesAreWritten() throws Exception {
        File source = fixture("synthetic-gallery", ".png", PNG);
        Uri saved = PhotoExports.saveGallery(context, sourceUri(source));

        assertEquals("media", saved.getAuthority());
        assertEquals("image/png", media.inserted.getAsString(MediaStore.Images.Media.MIME_TYPE));
        assertEquals("Pictures/VK Tea", media.inserted.getAsString(MediaStore.Images.Media.RELATIVE_PATH));
        assertEquals(Integer.valueOf(1), media.inserted.getAsInteger(MediaStore.Images.Media.IS_PENDING));
        assertEquals(Integer.valueOf(0), media.updated.getAsInteger(MediaStore.Images.Media.IS_PENDING));
        assertTrue(media.bytesPresentAtPublish);
        assertArrayEquals(PNG, Files.readAllBytes(media.files.get(saved.getLastPathSegment()).toPath()));
        assertFalse(media.deleted);
        assertEquals(0, shareCount());
    }

    @Test
    @Config(sdk = 29)
    public void galleryFailureDeletesPendingEntryAndPreparedCacheWithoutExposingProviderError() throws Exception {
        File source = fixture("synthetic-gallery-failure", ".png", PNG);
        media.failOpen = true;
        assertSafeFailure(() -> PhotoExports.saveGallery(context, sourceUri(source)));
        assertTrue(media.deleted);
        assertNull(media.updated);
        assertEquals(0, shareCount());
    }

    @Test
    public void configuredFileProviderSharesOnlyDedicatedPhotoCache() throws Exception {
        File source = fixture("synthetic-provider-source", ".png", PNG);
        PhotoExports.SavedPhoto photo = PhotoExports.prepareShare(context, sourceUri(source));
        String authority = context.getPackageName() + ".photo-files";
        ProviderInfo info = context.getPackageManager().resolveContentProvider(authority, PackageManager.GET_META_DATA);
        assertNotNull(info);
        assertEquals(authority, info.authority);
        assertFalse(info.exported);
        assertTrue(info.grantUriPermissions);
        assertEquals(R.xml.photo_export_paths, info.metaData.getInt("android.support.FILE_PROVIDER_PATHS"));
        assertEquals(new File(context.getCacheDir(), "photo-share"), photo.file.getParentFile());
        // Test our provider declaration portably. AndroidX's runtime strategy
        // uses Android '/' boundaries, unlike Windows-host canonical paths;
        // actual content-URI sharing is verified on the Android emulator.
        XmlResourceParser paths = context.getResources().getXml(R.xml.photo_export_paths);
        try {
            int entries = 0;
            while (paths.next() != XmlPullParser.END_DOCUMENT) {
                if (paths.getEventType() != XmlPullParser.START_TAG || "paths".equals(paths.getName())) continue;
                entries++;
                assertEquals("cache-path", paths.getName());
                assertEquals("photo_share", paths.getAttributeValue(null, "name"));
                assertEquals("photo-share/", paths.getAttributeValue(null, "path"));
            }
            assertEquals(1, entries);
        } finally { paths.close(); }
    }

    private SyntheticProvider register(String authority) {
        SyntheticProvider provider = new SyntheticProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = authority;
        info.exported = false;
        provider.attachInfo(context, info);
        ShadowContentResolver.registerProviderInternal(authority, provider);
        return provider;
    }

    private File fixture(String prefix, String suffix, byte[] bytes) throws IOException {
        File file = File.createTempFile(prefix, suffix, context.getCacheDir());
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); }
        return file;
    }

    private String sourceUri(File file) {
        String id = "synthetic-source-" + provider.files.size();
        provider.files.put(id, file);
        return "content://" + AUTHORITY + "/" + id;
    }

    private int shareCount() {
        File[] files = new File(context.getCacheDir(), "photo-share").listFiles();
        return files == null ? 0 : files.length;
    }

    private interface Operation { void run() throws IOException; }
    private static void assertSafeFailure(Operation operation) throws Exception {
        try {
            operation.run();
            fail("Expected a safe export failure");
        } catch (IOException failure) {
            assertEquals("Unable to export image", failure.getMessage());
            assertNull(failure.getCause());
        }
    }

    public static final class SyntheticProvider extends ContentProvider {
        final Map<String, File> files = new HashMap<>();
        ContentValues inserted;
        ContentValues updated;
        boolean failOpen;
        boolean readOnlyTarget;
        boolean deleted;
        boolean bytesPresentAtPublish;
        String lastOpenMode;
        @Override public boolean onCreate() { return true; }
        @Override public String getType(Uri uri) { return "image/jpeg"; }
        @Override public Cursor query(Uri uri, String[] columns, String selection, String[] args, String order) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) {
            inserted = new ContentValues(values);
            try {
                File file = File.createTempFile("synthetic-media-row", ".bin", getContext().getCacheDir());
                String id = Integer.toString(files.size() + 1);
                files.put(id, file);
                return uri.buildUpon().appendPath(id).build();
            } catch (IOException ignored) { return null; }
        }
        @Override public int update(Uri uri, ContentValues values, String where, String[] args) {
            updated = new ContentValues(values);
            File file = files.get(uri.getLastPathSegment());
            bytesPresentAtPublish = file != null && file.length() == PNG.length;
            return 1;
        }
        @Override public int delete(Uri uri, String where, String[] args) {
            deleted = true;
            File file = files.get(uri.getLastPathSegment());
            if (file != null) file.delete();
            return 1;
        }
        @Override public Bundle call(String method, String arg, Bundle extras) {
            if ("android:deleteDocument".equals(method)) {
                Uri target = extras.getParcelable("uri");
                delete(target, null, null);
                return new Bundle();
            }
            return null;
        }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            lastOpenMode = mode;
            File file = files.get(uri.getLastPathSegment());
            if (failOpen || file == null) throw new FileNotFoundException("SYNTHETIC_PRIVATE_PROVIDER_URI");
            return ParcelFileDescriptor.open(file, "r".equals(mode) || readOnlyTarget ? ParcelFileDescriptor.MODE_READ_ONLY
                    : "rw".equals(mode) ? ParcelFileDescriptor.MODE_READ_WRITE
                    : ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_TRUNCATE);
        }
    }
}
