package com.vktea.msg;

import android.os.Looper;

import org.robolectric.RuntimeEnvironment;
import com.vktea.api.VKApi;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.Shadows;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Synthetic protocol cases; these tests do not access an account or the network. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
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

    @Test public void syntheticConnectionReplaysEventsAndRecoversCursors() throws Exception {
        FakeTransport transport = new FakeTransport();
        MessageLongPoll poll = MessageLongPoll.forTesting(RuntimeEnvironment.getApplication(), transport);
        int[] updates = {0};
        MessageLongPoll.Listener listener = () -> updates[0]++;
        poll.addListener(listener);
        transport.acquisitions.get(0).success(server("10"));
        assertEquals("10", transport.cursors.get(0));
        advance(500);
        assertEquals(1, updates[0]);

        transport.polls.get(0).success(new JSONObject("{\"ts\":\"11\",\"updates\":[[4,101,0,42],[8,-42,0,0],[80,2]]}"));
        advance(0);
        assertEquals("11", transport.cursors.get(1));
        advance(500);
        assertEquals(2, updates[0]);

        transport.polls.get(1).success(new JSONObject("{\"failed\":2}"));
        advance(0);
        transport.acquisitions.get(1).success(server("20"));
        assertEquals("11", transport.cursors.get(2));
        transport.polls.get(2).success(new JSONObject("{\"failed\":3}"));
        advance(0);
        assertEquals(2, transport.acquisitions.size());
        advance(2000);
        transport.acquisitions.get(2).success(server("30"));
        assertEquals("30", transport.cursors.get(3));
        advance(500);
        assertEquals(3, updates[0]);
        poll.removeListener(listener);
        assertTrue(transport.cancelled);
    }

    @Test public void repeatedExpiredCursorUsesBackoffAndStopsWhenUnsubscribed() throws Exception {
        FakeTransport transport = new FakeTransport();
        MessageLongPoll poll = MessageLongPoll.forTesting(RuntimeEnvironment.getApplication(), transport);
        MessageLongPoll.Listener listener = () -> { };
        poll.addListener(listener);
        transport.acquisitions.get(0).success(server("10"));

        transport.polls.get(0).success(new JSONObject("{\"failed\":1,\"ts\":\"11\"}"));
        advance(0);
        assertEquals("11", transport.cursors.get(1));
        transport.polls.get(1).success(new JSONObject("{\"failed\":1,\"ts\":\"12\"}"));
        advance(0);
        assertEquals(2, transport.polls.size());
        advance(1999);
        assertEquals(2, transport.polls.size());
        advance(1);
        assertEquals("12", transport.cursors.get(2));

        transport.polls.get(2).success(new JSONObject("{\"failed\":1,\"ts\":\"13\"}"));
        advance(0);
        poll.removeListener(listener);
        advance(4000);
        assertEquals(3, transport.polls.size());
    }

    @Test public void staleNotificationAndResponseDoNotReachNewListener() throws Exception {
        FakeTransport transport = new FakeTransport();
        MessageLongPoll poll = MessageLongPoll.forTesting(RuntimeEnvironment.getApplication(), transport);
        int[] oldUpdates = {0};
        int[] newUpdates = {0};
        MessageLongPoll.Listener old = () -> oldUpdates[0]++;
        MessageLongPoll.Listener replacement = () -> newUpdates[0]++;
        poll.addListener(old);
        transport.acquisitions.get(0).success(server("1"));
        poll.removeListener(old);
        poll.addListener(replacement);
        transport.polls.get(0).success(new JSONObject("{\"ts\":\"2\",\"updates\":[[4,101,0,42]]}"));
        advance(500);
        assertEquals(0, oldUpdates[0]);
        assertEquals(0, newUpdates[0]);
        transport.acquisitions.get(1).success(server("3"));
        advance(500);
        assertEquals(1, newUpdates[0]);
        poll.removeListener(replacement);
    }

    private static JSONObject server(String ts) throws Exception {
        return new JSONObject("{\"response\":{\"server\":\"lp.vk.com\",\"key\":\"synthetic\",\"ts\":\"" + ts + "\"}}");
    }
    private static void advance(long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(millis, TimeUnit.MILLISECONDS);
    }
    private static final class FakeTransport implements MessageLongPoll.Transport {
        final List<VKApi.Callback> acquisitions = new ArrayList<>();
        final List<MessageLongPoll.PollCallback> polls = new ArrayList<>();
        final List<String> cursors = new ArrayList<>();
        boolean cancelled;
        @Override public void acquire(VKApi.Callback callback) { acquisitions.add(callback); }
        @Override public MessageLongPoll.Cancel poll(String server, String key, String ts, MessageLongPoll.PollCallback callback) {
            cursors.add(ts);
            polls.add(callback);
            return () -> cancelled = true;
        }
    }
}
