package com.vktea.search;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class SearchParserTest {
    @Test public void usersAndCommunitiesKeepCorrectIdsAndPagination() throws Exception {
        SearchParser.Page users = SearchParser.parse(SearchParser.Kind.USERS, new JSONObject("{\"response\":{\"count\":31,\"items\":[{\"id\":12,\"first_name\":\"Анна\",\"last_name\":\"Иванова\",\"city\":{\"title\":\"Москва\"}}]}}"), 0);
        assertEquals("Анна Иванова", users.items.get(0).title);
        assertEquals("Москва", users.items.get(0).subtitle);
        assertEquals("1", users.next);
        SearchParser.Page groups = SearchParser.parse(SearchParser.Kind.GROUPS, new JSONObject("{\"response\":{\"count\":1,\"items\":[{\"id\":13,\"name\":\"Клуб\"}]}}"), 0);
        assertEquals(-13, groups.items.get(0).id);
        assertEquals("", groups.next);
    }
    @Test public void postsUseOwnerAndPostIdsAndCursor() throws Exception {
        JSONObject body = new JSONObject("{\"response\":{\"next_from\":\"abc/1\",\"groups\":[{\"id\":42,\"name\":\"Новости\"}],\"items\":[{\"owner_id\":-42,\"from_id\":-42,\"id\":9,\"text\":\"Текст\",\"likes\":{\"count\":5}}]}}");
        SearchParser.Page page = SearchParser.parse(SearchParser.Kind.POSTS, body, 0);
        assertEquals("abc/1", page.next);
        assertEquals("Новости", page.items.get(0).title);
        assertEquals(-42, page.items.get(0).post.likeOwnerId);
        assertEquals(9, page.items.get(0).post.likeItemId);
        assertEquals(5, page.items.get(0).post.likes);
    }
    @Test public void malformedResponseFailsInsteadOfPretendingEmpty() throws Exception {
        try { SearchParser.parse(SearchParser.Kind.USERS, new JSONObject("{\"response\":{}}"), 0); fail(); }
        catch (org.json.JSONException expected) { }
    }
    @Test public void errorsDoNotExposeServerText() {
        assertTrue(SearchRepository.errorFor(7).contains("ограничил"));
        assertTrue(SearchRepository.errorFor(6).contains("запросов"));
    }
    @Test public void searchQueryAndCursorRemainSingleParameters() {
        okhttp3.HttpUrl url = SearchRepository.requestUrl(SearchParser.Kind.POSTS, "кот & x=1 #тег", "a/b&c", "test-token", "test-secret");
        assertEquals("кот & x=1 #тег", url.queryParameter("q"));
        assertEquals("a/b&c", url.queryParameter("start_from"));
        assertNull(url.queryParameter("x"));
        assertNotNull(url.queryParameter("sig"));
        assertEquals("/method/newsfeed.search", url.encodedPath());
    }
    @Test public void videoSearchParsesAndAdvancesByRawRows() throws Exception {
        JSONObject response = new JSONObject("{\"response\":{\"count\":8,\"items\":[{\"owner_id\":-8,\"id\":77,\"title\":\"Клип\",\"access_key\":\"key&x\",\"duration\":125,\"image\":[{\"url\":\"https://example.test/s.jpg\",\"width\":10,\"height\":10},{\"url\":\"https://example.test/l.jpg\",\"width\":100,\"height\":100}]},{\"owner_id\":0,\"id\":9}]}}");
        SearchParser.Page page = SearchParser.parse(SearchParser.Kind.VIDEOS, response, 2);
        assertEquals(1, page.items.size());
        SearchParser.Item video = page.items.get(0);
        assertEquals(-8, video.id); assertEquals(77, video.videoId);
        assertEquals("Клип", video.title); assertEquals("2:05", video.subtitle);
        assertEquals("https://example.test/l.jpg", video.photo);
        assertEquals("key&x", video.videoAccessKey);
        assertEquals("4", page.next);
        okhttp3.HttpUrl url = SearchRepository.requestUrl(SearchParser.Kind.VIDEOS, "кот & чай", page.next, "token", "secret");
        assertEquals("/method/video.search", url.encodedPath());
        assertEquals("4", url.queryParameter("offset"));
        assertNull(url.queryParameter("start_from"));
        assertEquals("кот & чай", url.queryParameter("q"));
    }
    @Test public void videoNavigationUsesIdentifiersAndAccessKeyOnly() {
        SearchParser.Item video = new SearchParser.Item();
        video.id = -8; video.videoId = 77; video.videoAccessKey = "key&x";
        android.content.Intent intent = SearchActivity.videoIntent(org.robolectric.RuntimeEnvironment.getApplication(), video);
        assertEquals(com.vktea.feed.VideoPlayerActivity.class.getName(), intent.getComponent().getClassName());
        assertEquals(-8, intent.getLongExtra("owner_id", 0));
        assertEquals(77, intent.getLongExtra("video_id", 0));
        assertEquals("key&x", intent.getStringExtra("access_key"));
        assertFalse(intent.hasExtra("direct_url"));
        video.id = 0;
        assertNull(SearchActivity.videoIntent(org.robolectric.RuntimeEnvironment.getApplication(), video));
    }
}
