package com.vktea.groups;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class GroupTest {
    @Test public void parserRetainsExtendedCommunityDetailsAndLargestNonblankAvatar() throws Exception {
        Group row = Group.parse(new JSONObject("{\"id\":3000000001,\"name\":\" Синтетическое сообщество \","
                + "\"screen_name\":\"synthetic_group\",\"activity\":\"Synthetic activity\",\"type\":\"page\","
                + "\"is_closed\":1,\"is_admin\":true,\"admin_level\":2,\"members_count\":3000000002,\"verified\":1,"
                + "\"photo_200\":\" \",\"photo_100\":\"file:///avatar\"}"));
        assertEquals(3000000001L, row.id); assertEquals(3000000002L, row.membersCount);
        assertEquals("Синтетическое сообщество", row.name); assertEquals("file:///avatar", row.photo);
        assertEquals("synthetic_group", row.screenName); assertEquals("Synthetic activity", row.activity);
        assertEquals("page", row.type); assertEquals(1, row.isClosed);
        assertEquals(2, row.adminLevel); assertTrue(row.isAdmin && row.isManaged() && row.verified);
    }

    @Test public void managementUsesActualRolesIncludingModeratorWithoutInventedRights() throws Exception {
        assertTrue(Group.parse(new JSONObject("{\"id\":1,\"admin_level\":1}")).isManaged());
        assertTrue(Group.parse(new JSONObject("{\"id\":2,\"is_admin\":1}")).isManaged());
        Group member = Group.parse(new JSONObject("{\"id\":3,\"is_member\":1,\"can_post\":1,\"type\":\"event\"}"));
        assertFalse(member.isManaged()); assertEquals("event", member.type);
    }

    @Test public void invalidOrDeactivatedRowsAreSkippedAndMissingDetailsStayNeutral() throws Exception {
        assertNull(Group.parse(null)); assertNull(Group.parse(new JSONObject("{\"id\":0}")));
        assertNull(Group.parse(new JSONObject("{\"id\":-7}")));
        assertNull(Group.parse(new JSONObject("{\"id\":7,\"deactivated\":\"banned\"}")));
        Group row = Group.parse(new JSONObject("{\"id\":7,\"name\":{},\"activity\":null,\"members_count\":-1,\"admin_level\":-2}"));
        assertEquals("Сообщество", row.name); assertEquals("", row.photo); assertEquals("", row.activity);
        assertEquals(-1, row.membersCount); assertEquals(0, row.adminLevel); assertFalse(row.isManaged());
        assertEquals(-1, Group.parse(new JSONObject("{\"id\":8}")).membersCount);
        assertEquals(0, Group.parse(new JSONObject("{\"id\":9,\"members_count\":0}")).membersCount);
    }
}
