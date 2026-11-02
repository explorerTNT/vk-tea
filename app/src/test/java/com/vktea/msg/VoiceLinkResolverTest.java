package com.vktea.msg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Only synthetic JSON is parsed; these tests perform no network requests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class VoiceLinkResolverTest {
    private static ChatAttachment voice() {
        return new ChatAttachment("audio_message", "Voice", "", "", "", 0, 0, 10, 11, -42, "key");
    }
    @Test public void freshVoiceMustHaveMatchingDocumentAndHttpsLink() throws Exception {
        VoiceLinkResolver.Result value = VoiceLinkResolver.parse(new JSONObject(
                "{\"response\":[{\"id\":11,\"owner_id\":-42,\"preview\":{\"audio_msg\":{\"link_ogg\":\"https://example.invalid/a\"}}}]}"), voice());
        assertEquals(VoiceLinkResolver.Kind.FOUND, value.kind);
        assertEquals("https://example.invalid/a", value.url);
        VoiceLinkResolver.Result other = VoiceLinkResolver.parse(new JSONObject(
                "{\"response\":[{\"id\":12,\"owner_id\":-42,\"preview\":{\"audio_msg\":{\"link_ogg\":\"https://example.invalid/a\"}}}]}"), voice());
        assertEquals(VoiceLinkResolver.Kind.UNAVAILABLE, other.kind);
    }
    @Test public void rejectedOrMissingDocumentDoesNotFallBack() throws Exception {
        assertEquals(VoiceLinkResolver.Kind.FAILED, VoiceLinkResolver.parse(new JSONObject("{\"error\":{}}"), voice()).kind);
        assertEquals(VoiceLinkResolver.Kind.UNAVAILABLE, VoiceLinkResolver.parse(new JSONObject("{\"response\":[]}"), voice()).kind);
        assertEquals(VoiceLinkResolver.Kind.UNAVAILABLE, VoiceLinkResolver.parse(new JSONObject(
                "{\"response\":[{\"id\":11,\"owner_id\":-42,\"content_restricted\":1,\"preview\":{\"audio_msg\":{\"link_mp3\":\"https://example.invalid/a\"}}}]}"), voice()).kind);
        assertEquals(VoiceLinkResolver.Kind.UNAVAILABLE, VoiceLinkResolver.parse(new JSONObject(
                "{\"response\":[{\"id\":11,\"owner_id\":-42,\"preview\":{\"audio_msg\":{\"link_ogg\":\"http://example.invalid/a\"}}}]}"), voice()).kind);
    }

    @Test public void permissionExceptionBecomesSanitizedIoFailure() throws Exception {
        okhttp3.Request request = new okhttp3.Request.Builder().url("https://example.invalid/").build();
        okhttp3.Interceptor.Chain chain = (okhttp3.Interceptor.Chain) java.lang.reflect.Proxy.newProxyInstance(
                okhttp3.Interceptor.Chain.class.getClassLoader(), new Class[]{okhttp3.Interceptor.Chain.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("request")) return request;
                    if (method.getName().equals("proceed")) throw new SecurityException("synthetic-private-details");
                    return null;
                });
        try {
            VoiceLinkResolver.proceedSafely(chain);
            fail("Permission failure must become an IOException");
        } catch (java.io.IOException safe) {
            assertEquals("Voice link request failed", safe.getMessage());
            assertNull(safe.getCause());
        }
    }
}
