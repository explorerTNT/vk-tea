package com.vktea.notifications;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class NotificationsParserTest {
    @Test public void parsesCommentsMentionsAndGroupedFeedbackWithPeopleAndCursor() throws Exception {
        JSONObject data = new JSONObject("{\"response\":{\"count\":3,\"last_viewed\":100,\"next_from\":\"next/1\","
                + "\"profiles\":[{\"id\":5,\"first_name\":\"Иван\",\"last_name\":\"Тест\",\"photo_100\":\"https://example.test/a\"}],"
                + "\"groups\":[{\"id\":7,\"name\":\"Сообщество\"}],\"items\":["
                + "{\"type\":\"comment_post\",\"date\":123,\"feedback\":{\"from_id\":5,\"text\":\"Комментарий\"},\"parent\":{\"owner_id\":-7,\"id\":42,\"text\":\"Пост\"}},"
                + "{\"type\":\"friend_accepted\",\"date\":122,\"feedback\":{\"count\":3,\"items\":[{\"from_id\":5},{\"from_id\":6}]}},"
                + "{\"type\":\"mention\",\"feedback\":{\"owner_id\":-7,\"id\":23,\"text\":\"Упоминание\"}}]}}");
        EventPage page = NotificationsParser.parse(data);
        assertEquals(3, page.items.size()); assertEquals("next/1", page.nextFrom); assertEquals(100, page.lastViewed);
        NotificationEvent comment = page.items.get(0);
        assertEquals("Иван Тест", comment.actorName); assertEquals("Комментарий", comment.text);
        assertEquals("Пост", comment.parentText); assertEquals("https://vk.com/wall-7_42", comment.url);
        assertEquals("Иван Тест и ещё 2", page.items.get(1).actorName);
        assertEquals("https://vk.com/id5", page.items.get(1).url);
        assertEquals("Сообщество", page.items.get(2).actorName); assertEquals("https://vk.com/wall-7_23", page.items.get(2).url);
        try { page.items.clear(); fail(); } catch (UnsupportedOperationException expected) { }
    }
    @Test public void unknownAndCommentParentsNeverBecomeInventedLinks() throws Exception {
        EventPage page = NotificationsParser.parse(new JSONObject("{\"response\":{\"items\":["
                + "{\"type\":\"reply_comment\",\"feedback\":{\"from_id\":44,\"text\":\"Ответ\"},\"parent\":{\"owner_id\":-1,\"id\":7}},"
                + "{\"type\":\"unknown\",\"url\":\"https://evil.invalid\"},"
                + "{\"type\":\"comment_photo\",\"parent\":{\"owner_id\":-1,\"id\":8},\"reply\":{\"text\":\"Текст\"}}]}}"));
        assertEquals("", page.items.get(0).url); assertEquals("", page.items.get(1).url);
        assertEquals("https://vk.com/photo-1_8", page.items.get(2).url); assertEquals("Текст", page.items.get(2).text);
    }
    @Test public void avatarUsesOnlyHttpsAndFallsBackToSafeImage() throws Exception {
        EventPage page = NotificationsParser.parse(new JSONObject("{\"response\":{\"profiles\":["
                + "{\"id\":1,\"first_name\":\"A\",\"photo_100\":\"file:///private\",\"photo_50\":\"https://example.test/avatar\"},"
                + "{\"id\":2,\"first_name\":\"B\",\"photo_100\":\"https://user:password@example.test/avatar\"}],"
                + "\"items\":[{\"type\":\"follow\",\"feedback\":{\"items\":[{\"from_id\":1}]}},"
                + "{\"type\":\"follow\",\"feedback\":{\"items\":[{\"from_id\":2}]}}]}}"));
        assertEquals("https://example.test/avatar", page.items.get(0).actorPhoto);
        assertEquals("", page.items.get(1).actorPhoto);
    }
}
