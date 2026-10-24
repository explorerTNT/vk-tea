package com.vktea.feed;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.DocumentsContract;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;

/** Blocking original-byte exports. Call on a worker; no account credentials or image re-encoding. */
public final class PhotoExports {
    static final long MAX_BYTES = 50L * 1024 * 1024;
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .protocols(Collections.singletonList(Protocol.HTTP_1_1)).retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS).addNetworkInterceptor(chain -> {
                Response response = chain.proceed(chain.request());
                return response.code() == 503 ? response.newBuilder()
                        .header("Retry-After", "2147483647").build() : response;
            }).build();

    private PhotoExports() {}

    public static final class SavedPhoto {
        public final File file;
        public final String mimeType;
        private SavedPhoto(File file, String mimeType) { this.file = file; this.mimeType = mimeType; }
    }

    public static SavedPhoto prepareShare(Context context, String source) throws IOException {
        return prepareShare(context, source, MAX_BYTES);
    }

    static SavedPhoto prepareShare(Context context, String source, long limit) throws IOException {
        File partial = null;
        try {
            File directory = new File(context.getCacheDir(), "photo-share");
            if (!directory.isDirectory() && !directory.mkdirs()) throw failure();
            purgeExpired(directory);
            partial = File.createTempFile("photo-", ".part", directory);
            try (InputStream input = open(context, source); OutputStream output = new FileOutputStream(partial)) {
                copy(input, output, limit);
            }
            String mime = imageType(partial);
            String extension = "image/jpeg".equals(mime) ? ".jpg" : "image/png".equals(mime) ? ".png"
                    : "image/gif".equals(mime) ? ".gif" : ".webp";
            File ready = new File(directory, partial.getName().replace(".part", extension));
            if (ready.exists() || !partial.renameTo(ready)) throw failure();
            partial = null;
            return new SavedPhoto(ready, mime);
        } catch (Exception ignored) {
            // Provider/network exceptions may contain a private URI or a signed CDN URL.
            throw failure();
        } finally {
            delete(partial);
        }
    }

    public static Uri saveGallery(Context context, String source) throws IOException {
        if (Build.VERSION.SDK_INT < 29) throw failure();
        SavedPhoto photo = prepareShare(context, source);
        ContentResolver resolver = context.getContentResolver();
        Uri target = null;
        boolean published = false;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, photo.file.getName());
            values.put(MediaStore.Images.Media.MIME_TYPE, photo.mimeType);
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/VK Tea");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            target = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
            if (target == null) throw failure();
            try (InputStream input = new FileInputStream(photo.file); OutputStream output = resolver.openOutputStream(target, "w")) {
                if (output == null) throw failure();
                copy(input, output, MAX_BYTES);
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            if (resolver.update(target, values, null, null) <= 0) throw failure();
            published = true;
            return target;
        } catch (Exception ignored) {
            throw failure();
        } finally {
            delete(photo.file);
            if (!published && target != null) {
                try { resolver.delete(target, null, null); } catch (Exception ignored) { }
            }
        }
    }

    /** Writes only to an empty, seekable SAF document without opening it in truncating mode. */
    public static void copyTo(Context context, String source, Uri target) throws IOException {
        if (target == null || !"content".equals(target.getScheme()) || sameDocument(source, target)) throw failure();
        SavedPhoto photo = prepareShare(context, source);
        boolean started = false;
        try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(target, "rw")) {
            if (descriptor == null || descriptor.getStatSize() != 0) throw failure();
            Os.lseek(descriptor.getFileDescriptor(), 0, OsConstants.SEEK_SET);
            try (InputStream input = new FileInputStream(photo.file);
                 OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
                started = true;
                copy(input, output, MAX_BYTES);
            }
        } catch (Exception ignored) {
            if (started) {
                try { DocumentsContract.deleteDocument(context.getContentResolver(), target); }
                catch (Exception cleanupIgnored) { }
            }
            throw failure();
        } finally {
            delete(photo.file);
        }
    }

    private static InputStream open(Context context, String source) throws Exception {
        if (source == null || source.isEmpty()) throw failure();
        Uri uri = Uri.parse(source);
        if ("content".equals(uri.getScheme())) {
            InputStream input = context.getContentResolver().openInputStream(uri);
            if (input == null) throw failure();
            return input;
        }
        if ("file".equals(uri.getScheme())) {
            if (uri.getAuthority() != null && !uri.getAuthority().isEmpty()) throw failure();
            String path = uri.getPath();
            if (path == null) throw failure();
            return path.startsWith("/android_asset/") ? context.getAssets().open(path.substring(15))
                    : new FileInputStream(path);
        }
        HttpUrl url = https(source);
        for (int redirects = 0; redirects <= 5; redirects++) {
            Response response = HTTP.newCall(new okhttp3.Request.Builder().url(url).get().build()).execute();
            if (response.isRedirect()) {
                String location = response.header("Location");
                HttpUrl next = location == null ? null : url.resolve(location);
                response.close();
                if (next == null || redirects == 5) throw failure();
                url = https(next.toString());
            } else {
                if (!response.isSuccessful() || response.body() == null || response.body().contentLength() > MAX_BYTES) {
                    response.close();
                    throw failure();
                }
                // Closing this stream also closes the response body and releases its connection.
                return response.body().byteStream();
            }
        }
        throw failure();
    }

    private static HttpUrl https(String value) throws Exception {
        if (new URI(value).getRawUserInfo() != null) throw failure();
        HttpUrl url = HttpUrl.parse(value);
        if (url == null || !url.isHttps() || !url.username().isEmpty() || !url.password().isEmpty()) throw failure();
        return url;
    }

    private static void copy(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int length;
        while ((length = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted() || (total += length) > limit) throw failure();
            output.write(buffer, 0, length);
        }
        output.flush();
    }

    private static String imageType(File file) throws IOException {
        byte[] header = new byte[12];
        int length;
        try (InputStream input = new FileInputStream(file)) { length = input.read(header); }
        if (length >= 3 && (header[0] & 255) == 255 && (header[1] & 255) == 216 && (header[2] & 255) == 255) return "image/jpeg";
        if (length >= 8 && Arrays.equals(Arrays.copyOf(header, 8), new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10})) return "image/png";
        if (length >= 6 && header[0] == 'G' && header[1] == 'I' && header[2] == 'F'
                && header[3] == '8' && (header[4] == '7' || header[4] == '9') && header[5] == 'a') return "image/gif";
        if (length >= 12 && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') return "image/webp";
        throw failure();
    }

    private static IOException failure() { return new IOException("Unable to export image"); }

    private static boolean sameDocument(String source, Uri target) {
        if (source == null) return false;
        Uri original = Uri.parse(source);
        if (original.equals(target)) return true;
        try {
            return java.util.Objects.equals(original.getAuthority(), target.getAuthority())
                    && DocumentsContract.getDocumentId(original).equals(DocumentsContract.getDocumentId(target));
        } catch (IllegalArgumentException ignored) { return false; }
    }

    private static void purgeExpired(File directory) {
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1);
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            try {
                if (file.getName().startsWith("photo-") && file.isFile() && file.lastModified() > 0
                        && file.lastModified() < cutoff && directory.getCanonicalFile()
                        .equals(file.getCanonicalFile().getParentFile())) delete(file);
            } catch (IOException | SecurityException ignored) { }
        }
    }

    private static void delete(File file) {
        try { if (file != null) file.delete(); } catch (SecurityException ignored) { }
    }
}
