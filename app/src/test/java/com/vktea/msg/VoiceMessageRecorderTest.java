package com.vktea.msg;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.json.JSONObject;

import okhttp3.HttpUrl;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class VoiceMessageRecorderTest {
    @Test public void uploadEndpointRequiresVkHttpsAndNoCredentials() {
        assertTrue(VoiceMessageRecorder.allowedUpload(HttpUrl.parse("https://upload.vk.com/upload")));
        assertTrue(VoiceMessageRecorder.allowedUpload(HttpUrl.parse("https://ps1.userapi.com/upload")));
        assertFalse(VoiceMessageRecorder.allowedUpload(HttpUrl.parse("http://upload.vk.com/upload")));
        assertFalse(VoiceMessageRecorder.allowedUpload(HttpUrl.parse("https://vk.com.example.org/upload")));
        assertFalse(VoiceMessageRecorder.allowedUpload(HttpUrl.parse("https://vk.com@evil.example/upload")));
        assertFalse(VoiceMessageRecorder.allowedUpload(HttpUrl.parse("https://vk.com:8443/upload")));
    }

    @Test public void savedVoiceSupportsModernAndLegacyVoiceButRejectsOrdinaryDocs() throws Exception {
        assertEquals(42, VoiceMessageRecorder.savedVoice(new JSONObject("{\"response\":{\"audio_message\":{\"id\":42}}}")).getInt("id"));
        assertEquals(43, VoiceMessageRecorder.savedVoice(new JSONObject("{\"response\":[{\"id\":43,\"preview\":{\"audio_msg\":{}}}]}")).getInt("id"));
        assertNull(VoiceMessageRecorder.savedVoice(new JSONObject("{\"response\":[{\"id\":44}]}")));
    }
}
