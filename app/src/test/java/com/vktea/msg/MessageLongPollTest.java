package com.vktea.msg;

import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/** Synthetic protocol cases; these tests do not access an account or the network. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MessageLongPollTest {
    @Test public void acceptsOnlySecureVkEndpoints() {
        assertNotNull(MessageLongPoll.endpoint("lp.vk.com/path"));
        assertNotNull(MessageLongPoll.endpoint("https://lp.vk.com/path"));
        assertNull(MessageLongPoll.endpoint("http://lp.vk.com/path"));
        assertNull(MessageLongPoll.endpoint("https://vk.com.evil.example/path"));
        assertNull(MessageLongPoll.endpoint("https://evil.example/path"));
        assertNull(MessageLongPoll.endpoint("https://user:pass@lp.vk.com/path"));
        assertNull(MessageLongPoll.endpoint("https://lp.vk.com:8443/path"));
    }

    @Test public void messageReadOnlineAndCounterEventsInvalidateStateButTypingDoesNot() throws Exception {
        assertTrue(MessageLongPoll.hasRelevantUpdates(new JSONArray("[[4,1,0,42],[7,42,1]]")));
        assertTrue(MessageLongPoll.hasRelevantUpdates(new JSONArray("[[8,-42,0,0]]")));
        assertTrue(MessageLongPoll.hasRelevantUpdates(new JSONArray("[[80,2]]")));
        assertFalse(MessageLongPoll.hasRelevantUpdates(new JSONArray("[[61,42,0],[62,42,3]]")));
        assertFalse(MessageLongPoll.hasRelevantUpdates(new JSONArray("[[],null]")));
    }
}
