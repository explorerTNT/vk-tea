package com.vktea.photos;

import static org.junit.Assert.*;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class PhotoCatalogTest {
    @Test public void albumsIncludeSystemIdsAndCoverSizes() throws Exception {
        PhotoCatalog.Page page = PhotoCatalog.parse(new JSONObject("{\"response\":{\"count\":2,\"items\":["
                + "{\"id\":-6,\"title\":\"Со стены\",\"size\":3,\"thumb_src\":\"https://img/a\"},"
                + "{\"id\":7,\"title\":\"Путешествие\",\"size\":5,\"sizes\":[{\"type\":\"m\",\"url\":\"https://img/b\"}]}]}}"), true);
        assertEquals(2, page.total); assertEquals(2, page.consumed); assertEquals(-6, page.albums.get(0).id);
        assertEquals("https://img/a", page.albums.get(0).cover); assertEquals("https://img/b", page.albums.get(1).cover);
    }
    @Test public void albumDescriptionIsAvailableForEditing() throws Exception {
        PhotoCatalog.Page page = PhotoCatalog.parse(new JSONObject("{\"response\":{\"count\":1,\"items\":[{\"id\":1,\"title\":\"A\",\"description\":\"Existing description\"}]}}"), true);
        assertEquals("Existing description", page.albums.get(0).description);
        PhotoCatalog.Page unknown = PhotoCatalog.parse(new JSONObject("{\"response\":{\"count\":1,\"items\":[{\"id\":1}]}}"), true);
        assertNull(unknown.albums.get(0).description); assertFalse(unknown.albums.get(0).titleKnown);
        assertTrue(page.albums.get(0).titleKnown);
    }
    @Test public void photosKeepIdentityPermissionsAndAccessKey() throws Exception {
        PhotoCatalog.Page page = PhotoCatalog.parse(new JSONObject("{\"response\":{\"count\":1,\"items\":[{\"id\":42,\"owner_id\":-3,\"access_key\":\"key\","
                + "\"sizes\":[{\"url\":\"https://img/photo\",\"type\":\"x\"}],\"likes\":{\"count\":4,\"user_likes\":1,\"can_like\":1}}]}}"), false);
        assertEquals(1, page.photos.size()); assertEquals(-3, page.photos.get(0).ownerId);
        assertEquals(42, page.photos.get(0).id); assertEquals("key", page.photos.get(0).accessKey);
        assertTrue(page.photos.get(0).liked); assertTrue(page.photos.get(0).canLike);
    }
    @Test public void missingPhotoDescriptionsRemainUnknown() throws Exception {
        PhotoCatalog.Page page = PhotoCatalog.parse(new JSONObject("{\"response\":{\"count\":2,\"items\":[" +
                "{\"id\":1,\"owner_id\":42,\"text\":\"\",\"sizes\":[{\"url\":\"https://img/1\"}]}," +
                "{\"id\":2,\"owner_id\":42,\"sizes\":[{\"url\":\"https://img/2\"}]}]}}"), false);
        assertTrue(page.knownPhotoDescriptions.contains("42:1")); assertFalse(page.knownPhotoDescriptions.contains("42:2"));
    }
    @Test public void consumedUsesRawRowsAndRejectsMalformedPages() throws Exception {
        PhotoCatalog.Page page = PhotoCatalog.parse(new JSONObject("{\"response\":{\"count\":2,\"items\":[{},null]}}"), false);
        assertEquals(2, page.consumed); assertTrue(page.photos.isEmpty());
        try { PhotoCatalog.parse(new JSONObject("{\"response\":{\"items\":[]}}"), true); fail(); }
        catch (JSONException expected) { }
    }
    @Test public void emptyAndClosedAlbumsAreNotConfused() throws Exception {
        PhotoCatalog.Page page = PhotoCatalog.parse(new JSONObject("{\"response\":{\"count\":0,\"items\":[]}}"), false);
        assertEquals(0, page.total); assertEquals(0, page.consumed); assertTrue(page.photos.isEmpty());
        assertEquals("Нет доступа к фотографиям", PhotoCatalog.errorMessage(15));
        assertEquals("Нет доступа к фотографиям", PhotoCatalog.errorMessage(30));
        assertEquals("Нет доступа к фотографиям", PhotoCatalog.errorMessage(200));
        assertNotEquals(PhotoCatalog.errorMessage(15), PhotoCatalog.errorMessage(6));
    }
    @Test public void systemCardsDoNotConsumeAlbumPageOffset() throws Exception {
        JSONObject response = new JSONObject("{\"response\":{\"count\":2,\"items\":[{\"id\":8,\"title\":\"Альбом\"}],"
                + "\"all_photos\":{\"size\":50,\"sizes\":[{\"type\":\"m\",\"src\":\"https://img/all\"}]},"
                + "\"user_photos\":{\"size\":4,\"last_photos\":[{\"sizes\":[{\"type\":\"m\",\"url\":\"https://img/user\"}]}]}}}");
        PhotoCatalog.Page first = PhotoCatalog.parse(response, true);
        assertEquals(2, first.total); assertEquals(1, first.consumed); assertEquals(3, first.albums.size());
        assertEquals(PhotoCatalog.Kind.ALL, first.albums.get(0).kind); assertEquals("https://img/all", first.albums.get(0).cover);
        assertEquals(PhotoCatalog.Kind.USER, first.albums.get(1).kind); assertEquals("https://img/user", first.albums.get(1).cover);
        assertNotEquals(first.albums.get(0).identity(), first.albums.get(1).identity());
        PhotoCatalog.Page second = PhotoCatalog.parse(response, true);
        assertEquals(first.albums.get(0).identity(), second.albums.get(0).identity());
        assertEquals(first.albums.get(1).identity(), second.albums.get(1).identity());
    }
    @Test public void systemCardsUseTheirOwnMethodsAndPaginationParameters() {
        assertEquals("photos.getAlbums", PhotoCatalog.method(null, PhotoCatalog.Kind.ALBUM));
        assertEquals("photos.get", PhotoCatalog.method(8L, PhotoCatalog.Kind.ALBUM));
        assertEquals("photos.getAll", PhotoCatalog.method(0L, PhotoCatalog.Kind.ALL));
        assertEquals("photos.getUserPhotos", PhotoCatalog.method(0L, PhotoCatalog.Kind.USER));
        java.util.Map<String, String> all = PhotoCatalog.parameters(42, 0L, PhotoCatalog.Kind.ALL, 100, "token");
        assertEquals("42", all.get("owner_id")); assertEquals("100", all.get("offset"));
        assertEquals("1", all.get("need_hidden")); assertFalse(all.containsKey("album_id"));
        java.util.Map<String, String> user = PhotoCatalog.parameters(42, 0L, PhotoCatalog.Kind.USER, 200, "token");
        assertEquals("42", user.get("user_id")); assertEquals("200", user.get("offset"));
        assertEquals("desc", user.get("sort")); assertFalse(user.containsKey("owner_id")); assertFalse(user.containsKey("album_id"));
        java.util.Map<String, String> albums = PhotoCatalog.parameters(42, null, PhotoCatalog.Kind.ALBUM, 0, "token");
        assertEquals("1", albums.get("need_system")); assertEquals("1", albums.get("need_covers"));
    }
}
