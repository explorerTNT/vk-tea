package com.vktea.photos;

import android.content.Context;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.os.ParcelFileDescriptor;
import org.robolectric.shadows.ShadowContentResolver;
import java.io.FileNotFoundException;
import android.net.Uri;
import com.vktea.auth.Session;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import okhttp3.FormBody;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okio.Buffer;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class PhotoUploadProtocolTest {
    private Context context;
    private Uri photo;
    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        Session.save(context, "test-token", "42", "test-secret");
        File directory = new File(context.getCacheDir(), "photo-share"); assertTrue(directory.isDirectory() || directory.mkdirs());
        File source = new File(directory, "protocol-test.jpg");
        try (FileOutputStream output = new FileOutputStream(source)) { output.write("synthetic-photo".getBytes(StandardCharsets.UTF_8)); }
        SyntheticProvider provider = new SyntheticProvider(); provider.file = source;
        ProviderInfo info = new ProviderInfo(); info.authority = "photo.protocol.test"; info.exported = false;
        provider.attachInfo(context, info); ShadowContentResolver.registerProviderInternal(info.authority, provider);
        photo = Uri.parse("content://photo.protocol.test/image");
    }
    public static final class SyntheticProvider extends ContentProvider {
        File file;
        @Override public boolean onCreate() { return true; }
        @Override public String getType(Uri uri) { return "image/jpeg"; }
        @Override public Cursor query(Uri uri, String[] columns, String selection, String[] args, String order) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int update(Uri uri, ContentValues values, String where, String[] args) { return 0; }
        @Override public int delete(Uri uri, String where, String[] args) { return 0; }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
    }
    private static String field(Request request, String name) {
        FormBody body = (FormBody) request.body();
        for (int i = 0; i < body.size(); i++) if (name.equals(body.name(i))) return body.value(i);
        return null;
    }
    private static final class Fake implements PhotoWrites.Transport {
        final List<Request> requests = new ArrayList<>(); int failAt = -1; int errorAt = -1; boolean invalidHost; Context changeAccount; boolean invalidSaved;
        @Override public JSONObject execute(Request request) throws Exception {
            requests.add(request); int step = requests.size();
            if (step == failAt) throw new IOException("synthetic transport interruption");
            if (step == errorAt) return new JSONObject("{\"error\":{\"error_code\":7}}");
            if (step == 1) return new JSONObject("{\"response\":{\"upload_url\":\"" + (invalidHost ? "https://evil.example/upload" : "https://pu.vk.com/upload") + "\"}}");
            if (step == 2) {
                if (changeAccount != null) Session.save(changeAccount, "another-token", "43");
                return new JSONObject("{\"server\":123,\"photos_list\":\"[{\\\"photo\\\":\\\"a\\\"}]\",\"hash\":\"hash-value\"}");
            }
            String owner = "55".equals(field(request, "group_id")) ? "-55" : "42";
            return new JSONObject(invalidSaved ? "{\"response\":[{}]}" : "{\"response\":[{\"id\":1,\"owner_id\":" + owner + ",\"album_id\":17}]}");
        }
    }
    @Test public void personalAlbumSendsServerMultipartAndSaveInOrder() throws Exception {
        Fake fake = new Fake(); PhotoWrites writes = new PhotoWrites(context, fake);
        assertNull(writes.runOnce(() -> writes.upload(42, 17, photo)));
        assertEquals(3, fake.requests.size());
        Request server = fake.requests.get(0), upload = fake.requests.get(1), save = fake.requests.get(2);
        assertEquals("/method/photos.getUploadServer", server.url().encodedPath());
        assertEquals("17", field(server, "album_id")); assertNull(field(server, "group_id"));
        assertEquals("test-token", field(server, "access_token")); assertNotNull(field(server, "sig"));
        assertEquals("pu.vk.com", upload.url().host()); assertEquals("POST", upload.method());
        assertNull(upload.header("Authorization")); assertNull(upload.url().queryParameter("access_token"));
        assertTrue(upload.body() instanceof MultipartBody);
        Buffer bytes = new Buffer(); upload.body().writeTo(bytes); String multipart = bytes.readUtf8();
        assertTrue(multipart.contains("name=\"file1\"")); assertTrue(multipart.contains("synthetic-photo"));
        assertFalse(multipart.contains("test-token")); assertFalse(multipart.contains("test-secret"));
        assertEquals("/method/photos.save", save.url().encodedPath());
        assertEquals("17", field(save, "album_id")); assertEquals("123", field(save, "server"));
        assertEquals("[{\"photo\":\"a\"}]", field(save, "photos_list")); assertEquals("hash-value", field(save, "hash"));
    }
    @Test public void communityAlbumUsesGroupIdInBothVkMethods() {
        Fake fake = new Fake(); PhotoWrites writes = new PhotoWrites(context, fake);
        assertNull(writes.runOnce(() -> writes.upload(-55, 17, photo)));
        assertEquals("55", field(fake.requests.get(0), "group_id"));
        assertEquals("55", field(fake.requests.get(2), "group_id"));
    }
    @Test public void networkInterruptionAfterSaveIsIndeterminateAndNeverRetried() {
        Fake fake = new Fake(); fake.failAt = 3; PhotoWrites writes = new PhotoWrites(context, fake);
        String error = writes.runOnce(() -> writes.upload(42, 17, photo));
        assertNotNull(error); assertTrue(error.contains("Проверьте изменения")); assertEquals(3, fake.requests.size());
    }
    @Test public void incompleteSaveResponseIsNotReportedAsSuccess() {
        Fake fake = new Fake(); fake.invalidSaved = true; PhotoWrites writes = new PhotoWrites(context, fake);
        String error = writes.runOnce(() -> writes.upload(42, 17, photo));
        assertNotNull(error); assertTrue(error.contains("Проверьте изменения")); assertEquals(3, fake.requests.size());
    }
    @Test public void explicitDenialStopsBeforeUploadAndIsReported() {
        Fake fake = new Fake(); fake.errorAt = 1; PhotoWrites writes = new PhotoWrites(context, fake);
        String error = writes.runOnce(() -> writes.upload(42, 17, photo));
        assertTrue(error.contains("не разрешил")); assertEquals(1, fake.requests.size());
    }
    @Test public void untrustedUploadHostAndAccountSwitchStopBeforeSave() {
        Fake invalid = new Fake(); invalid.invalidHost = true; PhotoWrites first = new PhotoWrites(context, invalid);
        assertNotNull(first.runOnce(() -> first.upload(42, 17, photo))); assertEquals(1, invalid.requests.size());
        Fake changed = new Fake(); changed.changeAccount = context; PhotoWrites second = new PhotoWrites(context, changed);
        String error = second.runOnce(() -> second.upload(42, 17, photo));
        assertTrue(error.contains("Аккаунт изменился")); assertEquals(2, changed.requests.size());
    }
}
