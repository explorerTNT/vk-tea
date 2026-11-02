package com.vktea.documents;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.os.Looper;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class DocumentsRepositoryTest {
    @Test public void parserRejectsRestrictedAndInvalidRowsButKeepsRawConsumedCount() throws Exception {
        JSONObject root = new JSONObject("{\"response\":{\"count\":3,\"items\":["
                + "{\"id\":1,\"owner_id\":7,\"title\":\"Doc\",\"url\":\"https://vk.com/doc\"},"
                + "{\"id\":2,\"owner_id\":7,\"content_restricted\":1},{\"id\":0}]}}");
        DocumentsRepository.Page page = VkDocumentsRepository.parse(root);
        assertEquals(3, page.consumed); assertEquals(3, page.total); assertEquals(1, page.items.size());
    }
    @Test public void onlyTrustedHttpsDocumentLinksMayOpen() {
        assertTrue(DocumentItem.isSafeUrl("https://vk.com/doc1"));
        assertTrue(DocumentItem.isSafeUrl("https://sun1.userapi.com/file"));
        assertFalse(DocumentItem.isSafeUrl("http://vk.com/doc1"));
        assertFalse(DocumentItem.isSafeUrl("https://vk.com.evil.example/file"));
        assertFalse(DocumentItem.isSafeUrl("https://vk.com@evil.example/file"));
        assertFalse(DocumentItem.isSafeUrl("https://vk.com:444/file"));
        assertFalse(DocumentItem.isSafeUrl("javascript:alert(1)"));
        assertTrue(new DocumentItem(1, 7, "doc.pdf", "pdf", 1, "https://vk.com/doc1").canOpen());
        assertFalse(new DocumentItem(1, 7, "unsafe.html", "html", 1, "https://vk.com/doc1").canOpen());
        assertFalse(new DocumentItem(1, 7, "script.svg", "svg", 1, "https://vk.com/doc1").canOpen());
        assertFalse(new DocumentItem(1, 7, "installer.apk", "apk", 1, "https://vk.com/doc1").canOpen());
    }
    @Test public void searchRequestsOnlyOwnDocumentsAndNeverLeavesFakeTransport() throws Exception {
        AtomicReference<String> method = new AtomicReference<>(); AtomicReference<FormBody> form = new AtomicReference<>();
        AtomicReference<DocumentsRepository.Page> page = new AtomicReference<>();
        OkHttpClient fake = new OkHttpClient.Builder().addInterceptor(chain -> {
            method.set(chain.request().url().encodedPath()); form.set((FormBody) chain.request().body());
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ResponseBody.create("{\"response\":{\"count\":0,\"items\":[]}}", MediaType.parse("application/json"))).build();
        }).build();
        VkDocumentsRepository repo = new VkDocumentsRepository(fake, HttpUrl.get("https://api.vk.com/"),
                () -> new VkDocumentsRepository.Snapshot("synthetic", "", "42"));
        try {
            repo.load("report", 50, new DocumentsRepository.Callback() {
                @Override public void success(DocumentsRepository.Page result) { page.set(result); }
                @Override public void error(DocumentsRepository.Error error) { fail("Synthetic request failed"); }
            });
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (page.get() == null && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10); }
            shadowOf(Looper.getMainLooper()).idle();
            assertNotNull(page.get()); assertEquals("/method/docs.search", method.get());
            assertEquals("1", value(form.get(), "search_own")); assertEquals("report", value(form.get(), "q"));
            assertEquals("50", value(form.get(), "offset"));
        } finally { repo.close(); }
    }
    @Test public void responseAfterAccountChangeCannotExposePreviousAccountDocuments() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<VkDocumentsRepository.Snapshot> account = new AtomicReference<>(new VkDocumentsRepository.Snapshot("first", "", "1"));
        AtomicReference<DocumentsRepository.Error> failure = new AtomicReference<>();
        OkHttpClient fake = new OkHttpClient.Builder().addInterceptor(chain -> {
            started.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("Synthetic timeout"); }
            catch (InterruptedException interrupted) { throw new java.io.IOException("Interrupted"); }
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ResponseBody.create("{\"response\":{\"count\":1,\"items\":[{\"id\":1,\"owner_id\":1}]}}", MediaType.parse("application/json"))).build();
        }).build();
        VkDocumentsRepository repo = new VkDocumentsRepository(fake, HttpUrl.get("https://api.vk.com/"), account::get);
        try {
            repo.load("", 0, new DocumentsRepository.Callback() {
                @Override public void success(DocumentsRepository.Page result) { fail("Previous account data must not be shown"); }
                @Override public void error(DocumentsRepository.Error error) { failure.set(error); }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            account.set(new VkDocumentsRepository.Snapshot("second", "", "2")); release.countDown();
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (failure.get() == null && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10); }
            shadowOf(Looper.getMainLooper()).idle(); assertNotNull(failure.get());
            assertEquals(DocumentsRepository.Error.Kind.AUTH, failure.get().kind);
        } finally { release.countDown(); repo.close(); }
    }
    private static String value(FormBody body, String name) {
        for (int i = 0; i < body.size(); i++) if (body.name(i).equals(name)) return body.value(i);
        return null;
    }
}
