package com.vktea.videos;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class VideosCatalogTest {
    @Test public void parserSupportsModernPreviewsAccessKeysAndLegacyAlbumCovers() throws Exception {
        JSONObject data = new JSONObject("{\"response\":{\"count\":2,\"items\":[{\"id\":5,\"owner_id\":-7,\"title\":\"Test\",\"duration\":65,\"access_key\":\"secret-key\",\"image\":[{\"url\":\"small\",\"width\":80,\"height\":40},{\"url\":\"large\",\"width\":800,\"height\":400}]},null]}}");
        VideoEntry.Page page = VideoEntry.parsePage(data, false, 10);
        assertEquals(2, page.consumed); assertEquals(2, page.total); assertEquals(1, page.entries.size());
        VideoEntry item = page.entries.get(0);
        assertEquals(-7, item.ownerId); assertEquals(5, item.id); assertEquals("large", item.image);
        assertEquals("secret-key", item.accessKey); assertEquals(65, item.duration);
        VideoEntry album = VideoEntry.parse(new JSONObject("{\"id\":-2,\"title\":\"Added\",\"count\":4,\"photo_160\":\"cover\"}"), true, 10);
        assertNotNull(album); assertTrue(album.album); assertEquals(10, album.ownerId); assertEquals("cover", album.image);
    }
    @Test public void paginationPreservesRowsOnFailureAndRetriesTheSameOffset() {
        Fake source = new Fake(); VideosViewModel model = new VideosViewModel(source, 42);
        model.ensureLoaded(); assertEquals(42, source.owner); assertEquals(0, source.offset);
        source.complete(page(1, 1, 3), null);
        assertTrue(model.current().hasMore()); assertEquals(1, model.current().rows.size());
        model.loadMore(); assertEquals(1, source.offset);
        source.complete(null, "network"); assertEquals(1, model.current().rows.size());
        model.loadMore(); assertEquals(1, source.offset);
        source.complete(page(2, 2, 3), null);
        assertEquals(2, model.current().rows.size()); assertFalse(model.current().hasMore());
    }
    @Test public void changingTabDiscardsStaleResponsesAndAlbumUsesOwnerAndId() {
        Fake source = new Fake(); VideosViewModel model = new VideosViewModel(source, -42);
        model.ensureLoaded(); VideosViewModel.Callback old = source.callback;
        model.setMode(VideosViewModel.ALBUMS); assertTrue(source.albums);
        old.complete(page(99, 1, 1), null); assertTrue(model.current().rows.isEmpty());
        VideoEntry album = new VideoEntry(-42, 17, "Album", "", "", 0, 1, true);
        source.complete(new VideoEntry.Page(java.util.Collections.singletonList(album), 1, 1), null);
        model.openAlbum(album); assertEquals(-42, source.owner); assertEquals(17, source.album); assertFalse(source.albums);
        model.backToAlbums(); assertTrue(source.albums); assertEquals(0, source.album);
        model.search("  cats  "); assertEquals("cats", source.query); assertEquals(0, source.offset);
    }
    private static VideoEntry.Page page(long id, int consumed, int total) {
        List<VideoEntry> rows = new ArrayList<>(); rows.add(new VideoEntry(42, id, "Test", "", "", 1, 0, false));
        return new VideoEntry.Page(rows, consumed, total);
    }
    static class Fake implements VideosViewModel.Source {
        long owner, album; boolean albums; int offset; String query; VideosViewModel.Callback callback;
        @Override public void load(long owner, long album, boolean albums, String query, int offset, VideosViewModel.Callback callback) {
            this.owner = owner; this.album = album; this.albums = albums; this.query = query; this.offset = offset; this.callback = callback;
        }
        @Override public void cancel() { }
        void complete(VideoEntry.Page page, String error) { callback.complete(page, error); }
    }
}
