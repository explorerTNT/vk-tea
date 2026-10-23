package com.vktea.msg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

/** Synthetic dialog metadata only; no requests or private conversations. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DialogsParserTest {
    @Test
    public void legacyChatIdWinsOverSenderAndKeepsItsOwnPhoto() throws Exception {
        JSONObject message = object("user_id", 41, "chat_id", 9, "title", "Synthetic chat",
                "photo_200", " ", "photo_100", "https://images.invalid/chat?synthetic=1", "text", "Synthetic excerpt", "date", 12);
        JSONObject data = object("items", array(object("message", message)), "profiles", array(
                object("id", 41, "first_name", "Synthetic", "last_name", "Sender", "photo_200", "https://images.invalid/sender")));
        Dialog dialog = DialogsParser.parse(object("response", data)).get(0);

        assertEquals(2_000_000_009L, dialog.peerId);
        assertEquals("Synthetic chat", dialog.title);
        assertEquals("https://images.invalid/chat?synthetic=1", dialog.photo);
        assertEquals("Synthetic excerpt", dialog.excerpt);
        assertEquals(12, dialog.date);
    }

    @Test
    public void embeddedProfilesSupplyNamesAndAvatarsEvenWhenLegacyTitleExists() throws Exception {
        JSONObject data = object("items", array(object("message", object("user_id", 41, "title", "...", "body", "Synthetic body")),
                        object("message", object("user_id", 42, "title", "Already known title"))),
                "profiles", array(object("id", 41, "first_name", "Synthetic", "last_name", "Person",
                                "photo_200", "https://images.invalid/200", "photo_100", "https://images.invalid/100"),
                        object("id", 42, "photo_200", JSONObject.NULL, "photo_100", "  ", "photo_50", "https://images.invalid/50")));
        List<Dialog> dialogs = DialogsParser.parse(data);

        assertEquals(41, dialogs.get(0).peerId);
        assertEquals("Synthetic Person", dialogs.get(0).title);
        assertEquals("https://images.invalid/200", dialogs.get(0).photo);
        assertEquals("Synthetic body", dialogs.get(0).excerpt);
        assertEquals("Already known title", dialogs.get(1).title);
        assertEquals("https://images.invalid/50", dialogs.get(1).photo);
    }

    @Test
    public void negativeCommunityUsesGroupMetadataRatherThanSenderProfile() throws Exception {
        JSONObject data = object("items", array(object("message", object("user_id", -7, "from_id", 41, "title", "..."))),
                "profiles", array(object("id", 41, "first_name", "Synthetic sender", "photo_200", "https://images.invalid/sender")),
                "groups", array(object("id", 7, "name", "Synthetic community", "photo_200", "https://images.invalid/group")));
        Dialog dialog = DialogsParser.parse(data).get(0);
        assertEquals(-7, dialog.peerId);
        assertEquals("Synthetic community", dialog.title);
        assertEquals("https://images.invalid/group", dialog.photo);
    }

    @Test
    public void modernConversationPeerAndChatSettingsPhotoAreSupportedWithoutProtocolChange() throws Exception {
        JSONObject personal = object("conversation", object("peer", object("id", 41, "type", "user"), "unread_count", 3),
                "last_message", object("user_id", 999, "text", "Synthetic personal", "out", 1, "read_state", 1));
        JSONObject chat = object("conversation", object("peer", object("id", 2_000_000_010L, "type", "chat"),
                        "unread_count", 0, "chat_settings", object("title", "Synthetic modern chat",
                                "photo", object("photo_200", "https://images.invalid/modern-chat?sig=synthetic", "photo_100", "https://images.invalid/smaller"))),
                "last_message", object("from_id", 42, "peer_id", 2_000_000_010L, "body", "Synthetic chat body", "out", 0, "read_state", 0));
        List<Dialog> dialogs = DialogsParser.parse(object("items", array(personal, chat),
                "profiles", array(object("id", 41, "first_name", "Synthetic person", "photo_100", "https://images.invalid/person"))));

        assertEquals(41, dialogs.get(0).peerId);
        assertTrue(dialogs.get(0).unread);
        assertEquals("https://images.invalid/person", dialogs.get(0).photo);
        assertEquals(2_000_000_010L, dialogs.get(1).peerId);
        assertEquals("Synthetic modern chat", dialogs.get(1).title);
        assertEquals("https://images.invalid/modern-chat?sig=synthetic", dialogs.get(1).photo);
        assertFalse(dialogs.get(1).unread);
    }

    @Test
    public void missingAvatarDoesNotBorrowChatMembersPhotoAndReadFallbackMatchesLegacy() throws Exception {
        JSONObject first = object("message", object("chat_id", 12, "user_id", 41, "out", 0, "read_state", 0));
        JSONObject second = object("unread", 2, "message", object("user_id", 42, "out", 1, "read_state", 1,
                "title", "Known title", "photo_50", "https://images.invalid/legacy-person"));
        List<Dialog> dialogs = DialogsParser.parse(object("items", array(first, second),
                "profiles", array(object("id", 41, "photo_200", "https://images.invalid/member"))));

        assertEquals("", dialogs.get(0).photo);
        assertEquals("Беседа", dialogs.get(0).title);
        assertTrue(dialogs.get(0).unread);
        assertEquals("Known title", dialogs.get(1).title);
        assertEquals("https://images.invalid/legacy-person", dialogs.get(1).photo);
        assertTrue(dialogs.get(1).unread);
    }

    @Test
    public void malformedRowsAreSkippedAndCoffeeWrappedProfilesRemainCompatible() throws Exception {
        assertTrue(DialogsParser.parse(null).isEmpty());
        assertTrue(DialogsParser.parse(new JSONObject()).isEmpty());
        JSONObject valid = object("message", object("user_id", -7));
        JSONObject data = object("p", array(object("id", -7, "first_name", "Synthetic legacy community",
                        "photo_100", "https://images.invalid/legacy-group")),
                "a", object("items", array(JSONObject.NULL, "Synthetic bad row", object("message", new JSONObject()), valid)));
        List<Dialog> dialogs = DialogsParser.parse(object("response", data));
        assertEquals(1, dialogs.size());
        assertEquals(-7, dialogs.get(0).peerId);
        assertEquals("Synthetic legacy community", dialogs.get(0).title);
        assertEquals("https://images.invalid/legacy-group", dialogs.get(0).photo);
    }

    private static JSONObject object(Object... values) throws Exception {
        JSONObject object = new JSONObject();
        for (int index = 0; index < values.length; index += 2) object.put((String) values[index], values[index + 1]);
        return object;
    }

    private static JSONArray array(Object... values) {
        JSONArray array = new JSONArray();
        for (Object value : values) array.put(value);
        return array;
    }
}
