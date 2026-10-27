package com.vktea.bookmarks;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class BookmarksParserTest {
    @Test public void parsesContentAndPreservesUnknownEntries() throws Exception {
        JSONObject root = new JSONObject("{\"response\":{\"count\":3,\"items\":[{\"type\":\"post\",\"post\":{\"owner_id\":-8,\"id\":42,\"text\":\"Hello\",\"access_key\":\"key\"}},{\"type\":\"link\",\"link\":{\"title\":\"Docs\",\"url\":\"https://example.org/\"}},{\"type\":\"future\"}]}}");
        BookmarksParser.Page page = BookmarksParser.parse(root, false);
        assertEquals(3, page.count); assertEquals(3, page.consumed); assertEquals(3, page.items.size());
        assertEquals(-8, page.items.get(0).owner); assertEquals(42, page.items.get(0).id);
        assertEquals("key", page.items.get(0).accessKey); assertEquals("https://example.org/", page.items.get(1).url);
        assertEquals("future", page.items.get(2).type);
    }
    @Test public void parsesUsersAndGroupsWithSignedOwnerIds() throws Exception {
        JSONObject root = new JSONObject("{\"response\":{\"count\":2,\"items\":[{\"type\":\"user\",\"user\":{\"id\":7,\"first_name\":\"A\",\"last_name\":\"B\"}},{\"type\":\"group\",\"group\":{\"id\":9,\"name\":\"Community\"}}]}}");
        BookmarksParser.Page page = BookmarksParser.parse(root, true);
        assertEquals(7, page.items.get(0).owner); assertEquals("A B", page.items.get(0).title);
        assertEquals(-9, page.items.get(1).owner); assertEquals("Community", page.items.get(1).title);
    }
    @Test(expected = org.json.JSONException.class) public void rejectsMalformedPage() throws Exception {
        BookmarksParser.parse(new JSONObject("{\"response\":{\"count\":-1,\"items\":[]}}"), false);
    }
}
