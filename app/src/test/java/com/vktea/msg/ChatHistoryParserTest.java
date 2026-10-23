package com.vktea.msg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Entirely synthetic history fixtures; this parser has no network or session dependency. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ChatHistoryParserTest {
    @Test
    public void chronologicalHistoryResolvesProfilesGroupsAndLegacyOutgoingAuthor() throws Exception {
        JSONObject newest = message(3, 300, 91).put("text", "Synthetic newest");
        JSONObject oldest = message(1, 100, -7).put("text", "Synthetic group message");
        JSONObject middle = object("id", 2, "date", 200, "out", 1, "uid", 91, "body", "Synthetic legacy body");
        JSONObject response = history(newest, oldest, middle);
        response.put("profiles", array(object("id", 91, "first_name", "Synthetic", "last_name", "Person",
                "photo_100", "https://images.invalid/profile?synthetic=1")));
        response.put("groups", array(object("id", 7, "name", "Synthetic community",
                "photo_100", "https://images.invalid/group")));

        List<ChatMessage> rows = ChatHistoryParser.parse(object("response", response), 91);

        assertEquals(Arrays.asList(1L, 2L, 3L), Arrays.asList(rows.get(0).id, rows.get(1).id, rows.get(2).id));
        assertEquals("Synthetic community", rows.get(0).author);
        assertEquals("https://images.invalid/group", rows.get(0).avatar);
        assertEquals(-7, rows.get(0).fromId);
        assertTrue(rows.get(1).out);
        assertEquals("Вы", rows.get(1).author);
        assertEquals("Synthetic legacy body", rows.get(1).body);
        assertEquals("Synthetic Person", rows.get(2).author);
        assertEquals("https://images.invalid/profile?synthetic=1", rows.get(2).avatar);
    }

    @Test
    public void reverseOrderRemainsStableWithinTheSameSecondAndModelsAreSnapshots() throws Exception {
        List<ChatMessage> rows = ChatHistoryParser.parse(history(message(3, 100, 91),
                message(2, 100, 91), message(1, 99, 91)), 91);
        assertEquals(1, rows.get(0).id);
        assertEquals(2, rows.get(1).id);
        assertEquals(3, rows.get(2).id);
        try {
            rows.clear();
            fail("History must be immutable");
        } catch (UnsupportedOperationException expected) { /* snapshot */ }

        ArrayList<ChatAttachment> source = new ArrayList<>();
        source.add(new ChatAttachment("doc", "Synthetic doc", "", "", "", 0, 0, 0, 8, 91, ""));
        ChatMessage message = new ChatMessage(1, false, 91, "", "", "", 100, source);
        source.clear();
        assertEquals(1, message.attachments.size());
        try {
            message.attachments.clear();
            fail("Attachment snapshot must be immutable");
        } catch (UnsupportedOperationException expected) { /* snapshot */ }
    }

    @Test
    public void everyAttachmentRemainsVisibleIncludingUnknownAndUnavailablePayloads() throws Exception {
        JSONObject row = message(1, 100, 91).put("text", "Synthetic text")
                .put("attachments", array(attachment("photo", object("id", 1)),
                        attachment("video", object("id", 2, "title", "Synthetic video")),
                        attachment("audio", object("id", 3, "artist", "Synthetic artist", "title", "Synthetic audio")),
                        attachment("new_attachment_type", object("id", 4)),
                        object("type", "doc"), "Synthetic malformed attachment"));
        ChatMessage message = only(row);

        assertEquals("Synthetic text", message.body);
        assertEquals(6, message.attachments.size());
        assertEquals("photo", message.attachments.get(0).type);
        assertEquals("video", message.attachments.get(1).type);
        assertEquals("audio", message.attachments.get(2).type);
        assertEquals("new_attachment_type", message.attachments.get(3).type);
        for (ChatAttachment part : message.attachments) assertFalse(part.title.trim().isEmpty());
        assertTrue(message.attachments.get(4).title.contains("недоступно"));
        assertEquals("unknown", message.attachments.get(5).type);
    }

    @Test
    public void photoChoosesLargestUncroppedSizeByDimensionsAndPreservesSignedUrl() throws Exception {
        String exactUrl = "https://images.invalid/photo?synthetic=1&cs=1200x1600&sig=synthetic-signature";
        JSONObject photo = object("id", 8, "owner_id", 91, "access_key", "synthetic-key", "sizes", array(
                size("q", "https://images.invalid/crop", 2500, 2500),
                size("y", exactUrl, 1200, 1600),
                size("x", "https://images.invalid/medium", 600, 800),
                size("m", "https://images.invalid/small", 90, 120)));
        ChatAttachment parsed = only(message(1, 100, 91)
                .put("attachments", array(attachment("photo", photo)))).attachments.get(0);

        assertEquals(exactUrl, parsed.previewUrl);
        assertEquals(exactUrl, parsed.url);
        assertEquals(1200, parsed.width);
        assertEquals(1600, parsed.height);
        assertEquals(8, parsed.id);
        assertEquals(91, parsed.ownerId);
        assertEquals("synthetic-key", parsed.accessKey);
    }

    @Test
    public void photoSupportsLegacyFieldsAndUnorderedCropOnlyOrSrcSizes() throws Exception {
        JSONObject legacy = object("photo_130", "https://images.invalid/small",
                "photo_1280", "https://images.invalid/legacy?cs=1280x960&synthetic=1", "width", 4000, "height", 3000);
        JSONObject cropOnly = object("sizes", array(size("p", "https://images.invalid/crop-medium", 400, 300),
                size("r", "https://images.invalid/crop-big", 800, 600), size("o", "https://images.invalid/crop-small", 100, 75)));
        JSONObject srcSize = object("sizes", array(object("type", "x", "src", "https://images.invalid/src", "width", 700, "height", 350)));
        List<ChatAttachment> attachments = only(message(1, 100, 91).put("attachments", array(
                attachment("photo", legacy), attachment("photo", cropOnly), attachment("photo", srcSize)))).attachments;

        assertEquals("https://images.invalid/legacy?cs=1280x960&synthetic=1", attachments.get(0).previewUrl);
        assertEquals(4000, attachments.get(0).width);
        assertEquals(3000, attachments.get(0).height);
        assertEquals("https://images.invalid/crop-big", attachments.get(1).previewUrl);
        assertEquals(800, attachments.get(1).width);
        assertEquals("https://images.invalid/src", attachments.get(2).previewUrl);
        assertEquals(350, attachments.get(2).height);
    }

    @Test
    public void videoSupportsModernImageFirstFrameAndLegacyWithoutSquareAssumptions() throws Exception {
        JSONObject modern = object("id", 8, "owner_id", -7, "title", "Synthetic landscape video", "duration", 65,
                "player", "https://player.invalid/embed?synthetic=1", "access_key", "synthetic-key", "image", array(
                        size("", "https://images.invalid/320", 320, 180),
                        size("", "https://images.invalid/1280?cs=1280x720", 1280, 720),
                        size("", "https://images.invalid/160", 160, 90)));
        JSONObject firstFrame = object("id", 9, "owner_id", 91, "first_frame", array(
                size("", "https://images.invalid/frame-big", 1080, 1920),
                size("", "https://images.invalid/frame-small", 180, 320)));
        JSONObject legacy = object("id", 10, "owner_id", 91, "photo_320", "https://images.invalid/legacy-small",
                "photo_800", "https://images.invalid/legacy-big", "width", 1600, "height", 900);
        List<ChatAttachment> parts = only(message(1, 100, 91).put("attachments", array(
                attachment("video", modern), attachment("video", firstFrame), attachment("video", legacy)))).attachments;

        assertEquals("https://images.invalid/1280?cs=1280x720", parts.get(0).previewUrl);
        assertEquals(1280, parts.get(0).width);
        assertEquals(720, parts.get(0).height);
        assertEquals(65, parts.get(0).duration);
        assertEquals("Видео · 1:05", parts.get(0).subtitle);
        assertEquals("https://player.invalid/embed?synthetic=1", parts.get(0).url);
        assertEquals(-7, parts.get(0).ownerId);
        assertEquals("synthetic-key", parts.get(0).accessKey);
        assertEquals("https://images.invalid/frame-big", parts.get(1).previewUrl);
        assertEquals(1080, parts.get(1).width);
        assertEquals(1920, parts.get(1).height);
        assertEquals("https://images.invalid/legacy-big", parts.get(2).previewUrl);
        assertEquals(1600, parts.get(2).width);
        assertEquals(900, parts.get(2).height);
    }

    @Test
    public void stickerUsesActualUnorderedSizeAndBackgroundOrLegacyFallback() throws Exception {
        JSONObject modern = object("sticker_id", 51, "images", array(
                size("", "https://images.invalid/sticker-wide", 512, 256),
                size("", "https://images.invalid/sticker-small", 64, 32)));
        JSONObject background = object("sticker_id", 52, "images_with_background", array(
                size("", "https://images.invalid/sticker-background", 256, 512)));
        JSONObject legacy = object("id", 53, "photo_128", "https://images.invalid/sticker128",
                "photo_256", "https://images.invalid/sticker256", "width", 256, "height", 128);
        List<ChatAttachment> parts = only(message(1, 100, 91).put("attachments", array(
                attachment("sticker", modern), attachment("sticker", background), attachment("sticker", legacy)))).attachments;
        assertEquals(51, parts.get(0).id);
        assertEquals("https://images.invalid/sticker-wide", parts.get(0).previewUrl);
        assertEquals(512, parts.get(0).width);
        assertEquals(256, parts.get(0).height);
        assertEquals(512, parts.get(1).height);
        assertEquals("https://images.invalid/sticker256", parts.get(2).previewUrl);
        assertEquals(128, parts.get(2).height);
    }

    @Test
    public void documentLinkAndVoiceKeepUsefulMetadataAndProperPreviewDimensions() throws Exception {
        JSONObject document = object("id", 21, "owner_id", 91, "title", "Synthetic.pdf", "ext", "pdf", "size", 2048,
                "url", "https://docs.invalid/file?synthetic=1", "preview", object("photo", object("sizes", array(
                        size("x", "https://images.invalid/document", 1200, 800),
                        size("q", "https://images.invalid/document-crop", 1600, 1600)))));
        JSONObject link = object("title", "Synthetic site", "url", "https://site.invalid/?synthetic=1",
                "description", "Synthetic description", "photo", object("sizes", array(size("x", "https://images.invalid/link", 600, 300))));
        JSONObject voice = object("id", 22, "owner_id", 91, "duration", 9, "link_mp3", "https://audio.invalid/voice?synthetic=1");
        List<ChatAttachment> parts = only(message(1, 100, 91).put("attachments", array(
                attachment("doc", document), attachment("link", link), attachment("audio_message", voice)))).attachments;

        assertEquals("Synthetic.pdf", parts.get(0).title);
        assertEquals("PDF · 2.0 КБ", parts.get(0).subtitle);
        assertEquals("https://images.invalid/document", parts.get(0).previewUrl);
        assertEquals(1200, parts.get(0).width);
        assertEquals(800, parts.get(0).height);
        assertEquals("https://docs.invalid/file?synthetic=1", parts.get(0).url);
        assertEquals("https://site.invalid/?synthetic=1", parts.get(1).url);
        assertEquals("Synthetic description", parts.get(1).subtitle);
        assertEquals(600, parts.get(1).width);
        assertEquals(300, parts.get(1).height);
        assertEquals("Голосовое сообщение", parts.get(2).title);
        assertEquals("0:09", parts.get(2).subtitle);
        assertEquals("https://audio.invalid/voice?synthetic=1", parts.get(2).url);
    }

    @Test
    public void repliesAndForwardedMessagesIncludeAuthorAndContentOrAttachmentSummary() throws Exception {
        JSONObject reply = message(4, 90, 91).put("text", "Synthetic quoted text");
        JSONObject forward = message(5, 80, -7).put("attachments", array(attachment("doc", object("title", "Synthetic report"))));
        JSONObject nested = message(6, 70, 91).put("fwd_messages", array(message(7, 60, 91).put("text", "Synthetic nested text")));
        JSONObject row = message(8, 100, 91).put("text", "Synthetic current text")
                .put("reply_message", reply).put("fwd_messages", array(forward, nested));
        JSONObject data = history(row).put("profiles", array(object("id", 91, "first_name", "Synthetic", "last_name", "Person")))
                .put("groups", array(object("id", 7, "name", "Synthetic community")));
        ChatMessage parsed = ChatHistoryParser.parse(data, 91).get(0);

        assertTrue(parsed.body.startsWith("Synthetic current text"));
        assertTrue(parsed.body.contains("Ответ · Synthetic Person\nSynthetic quoted text"));
        assertTrue(parsed.body.contains("Переслано · Synthetic community\nSynthetic report"));
        assertTrue(parsed.body.contains("Synthetic nested text"));
        assertEquals(Collections.emptyList(), parsed.attachments);
    }

    @Test
    public void malformedRowsDeletedMessagesAndUnknownServiceActionsNeverBecomeBlankBubbles() throws Exception {
        assertTrue(ChatHistoryParser.parse(null, 91).isEmpty());
        assertTrue(ChatHistoryParser.parse(object("error", object("error_code", 1)), 91).isEmpty());
        JSONObject deleted = message(1, 100, 91).put("deleted", true);
        JSONObject action = message(2, 101, 91).put("action", object("type", "synthetic_new_action"));
        JSONObject unavailableVideo = message(3, 102, 91).put("attachments", array(
                attachment("video", object("id", 9, "content_restricted", 24, "url", "https://video.invalid/restricted"))));
        JSONObject data = object("items", array("Synthetic invalid item", JSONObject.NULL, deleted, action, unavailableVideo));
        List<ChatMessage> rows = ChatHistoryParser.parse(data, 91);

        assertEquals(3, rows.size());
        assertEquals("Сообщение удалено", rows.get(0).body);
        assertEquals("Событие беседы", rows.get(1).body);
        assertTrue(rows.get(2).attachments.get(0).title.contains("недоступно"));
        assertEquals("", rows.get(2).attachments.get(0).url);
    }

    private static ChatMessage only(JSONObject row) throws Exception {
        return ChatHistoryParser.parse(history(row), 91).get(0);
    }

    private static JSONObject message(long id, long date, long from) throws Exception {
        return object("id", id, "date", date, "from_id", from, "out", 0, "text", "");
    }

    private static JSONObject history(JSONObject... messages) throws Exception {
        return object("items", array((Object[]) messages));
    }

    private static JSONObject attachment(String type, JSONObject payload) throws Exception {
        return object("type", type, type, payload);
    }

    private static JSONObject size(String type, String url, int width, int height) throws Exception {
        return object("type", type, "url", url, "width", width, "height", height);
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
