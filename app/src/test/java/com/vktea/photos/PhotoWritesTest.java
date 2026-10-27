package com.vktea.photos;

import org.junit.Test;
import static org.junit.Assert.*;
import okhttp3.HttpUrl;

public class PhotoWritesTest {
    @Test public void unknownAlbumDescriptionIsNotOverwritten() {
        java.util.Map<String, String> omitted = PhotoWrites.editAlbumParameters(42, 7, "Title", null);
        assertEquals("42", omitted.get("owner_id")); assertEquals("7", omitted.get("album_id"));
        assertEquals("Title", omitted.get("title")); assertFalse(omitted.containsKey("description"));
        assertEquals("", PhotoWrites.editAlbumParameters(42, 7, "Title", "").get("description"));
        assertEquals("New", PhotoWrites.editAlbumParameters(-42, 7, "Title", "New").get("description"));
    }
    @Test public void dnsRejectsPrivateAddressesEvenForAllowedHostname() throws Exception {
        for (String ip : new String[]{"127.0.0.1", "10.0.0.1", "192.168.1.2", "169.254.0.3", "100.64.0.1", "::1", "fc00::1"}) {
            okhttp3.Dns fake = hostname -> java.util.Collections.singletonList(java.net.InetAddress.getByName(ip));
            try { PhotoWrites.publicDns(fake).lookup("pu.vk.com"); fail(ip); }
            catch (java.net.UnknownHostException expected) { }
        }
        okhttp3.Dns publicFake = hostname -> java.util.Collections.singletonList(java.net.InetAddress.getByName("1.1.1.1"));
        assertEquals(1, PhotoWrites.publicDns(publicFake).lookup("pu.vk.com").size());
        okhttp3.Dns mixed = hostname -> java.util.Arrays.asList(java.net.InetAddress.getByName("1.1.1.1"), java.net.InetAddress.getByName("127.0.0.1"));
        try { PhotoWrites.publicDns(mixed).lookup("pu.vk.com"); fail(); }
        catch (java.net.UnknownHostException expected) { }
    }
    @Test public void uploadUrlRequiresVkHttpsOriginWithoutCredentialsOrAlternatePort() {
        assertTrue(PhotoWrites.validUploadUrl(HttpUrl.parse("https://pu.vk.com/upload")));
        assertTrue(PhotoWrites.validUploadUrl(HttpUrl.parse("https://sun9-1.userapi.com/upload")));
        assertTrue(PhotoWrites.validUploadUrl(HttpUrl.parse("https://upload.vk.ru/upload")));
        assertFalse(PhotoWrites.validUploadUrl(HttpUrl.parse("http://pu.vk.com/upload")));
        assertFalse(PhotoWrites.validUploadUrl(HttpUrl.parse("https://pu.vk.com.evil.test/upload")));
        assertFalse(PhotoWrites.validUploadUrl(HttpUrl.parse("https://user@pu.vk.com/upload")));
        assertFalse(PhotoWrites.validUploadUrl(HttpUrl.parse("https://pu.vk.com:8443/upload")));
        assertFalse(PhotoWrites.validUploadUrl(HttpUrl.parse("https://127.0.0.1/upload")));
        assertFalse(PhotoWrites.validUploadUrl(HttpUrl.parse("https://192.168.1.1/upload")));
        assertFalse(PhotoWrites.validUploadUrl(HttpUrl.parse("https://localhost/upload")));
        assertFalse(PhotoWrites.validUploadUrl(null));
    }
}
