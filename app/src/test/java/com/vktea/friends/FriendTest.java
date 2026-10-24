package com.vktea.friends;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class FriendTest {
    @Test public void parserRetainsActualPresenceDetailsAndUsesNonblankLargestAvatar() throws Exception {
        Friend row = Friend.parse(new JSONObject("{\"id\":3000000001,\"first_name\":\" Ёгор \",\"last_name\":\" Синтетический \","
                + "\"photo_200\":\" \",\"photo_100\":\"file:///avatar\",\"online\":1,\"online_mobile\":true,"
                + "\"last_seen\":{\"time\":1700000000,\"platform\":4},\"status\":\"Synthetic status\"}"));
        assertEquals(3000000001L, row.id);
        assertEquals("Ёгор Синтетический", row.name);
        assertEquals("file:///avatar", row.photo);
        assertTrue(row.online && row.onlineMobile);
        assertEquals(1700000000, row.lastSeen);
        assertEquals(4, row.platform);
        assertEquals("Synthetic status", row.status);
        Friend offline = Friend.parse(new JSONObject("{\"id\":8,\"online\":0,\"last_seen\":{\"time\":9999999999},\"status\":{}}"));
        assertFalse(offline.online);
        assertEquals("", offline.status);
        assertEquals("", offline.photo);
    }
    @Test public void invalidAndDeactivatedRowsAreSkippedWithoutInventedIdentityOrPresence() throws Exception {
        assertNull(Friend.parse(null));
        assertNull(Friend.parse(new JSONObject("{\"id\":0}")));
        assertNull(Friend.parse(new JSONObject("{\"id\":-7}")));
        assertNull(Friend.parse(new JSONObject("{\"id\":7,\"deactivated\":\"deleted\"}")));
        Friend row = Friend.parse(new JSONObject("{\"id\":7,\"first_name\":null,\"last_name\":{},\"online\":2}"));
        assertEquals("Пользователь", row.name);
        assertFalse(row.online || row.onlineMobile);
        assertEquals(0, row.lastSeen);
    }
}
