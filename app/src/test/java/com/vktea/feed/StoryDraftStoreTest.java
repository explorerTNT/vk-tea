package com.vktea.feed;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.Before;
import org.junit.Test;

public class StoryDraftStoreTest {
    private final MemoryStorage storage = new MemoryStorage();
    private TestKeys keys;
    private StoryDraftStore store;

    @Before public void setup() throws Exception {
        keys = new TestKeys();
        store = new StoryDraftStore(storage, keys);
    }

    @Test public void persistsEncryptedDraftAcrossInstancesAndIsolatesIdentities() {
        String text = "private-example Личный ответ 🐱";
        assertTrue(store.save("456", 123, 5, text));
        assertFalse(new String(storage.values.values().iterator().next(), StandardCharsets.ISO_8859_1).contains("private-example"));
        StoryDraftStore restarted = new StoryDraftStore(storage, keys);
        assertEquals(text, restarted.load("456", 123, 5).text);
        assertEquals(StoryDraftStore.Status.MISSING, restarted.load("789", 123, 5).status);
        assertEquals(StoryDraftStore.Status.MISSING, restarted.load("456", 124, 5).status);
        assertEquals(StoryDraftStore.Status.MISSING, restarted.load("456", 123, 6).status);
    }

    @Test public void tamperingAndCrossStoryCopyFailAuthenticationAndDoNotOverwrite() {
        assertTrue(store.save("456", 123, 5, "first"));
        assertTrue(store.save("456", 123, 6, "second"));
        Map<String, byte[]> before = new HashMap<>(storage.values);
        String first = null, second = null;
        for (String name : before.keySet()) {
            // File names are intentionally opaque, so find each through its value after removal.
            storage.values.remove(name);
            if (store.load("456", 123, 5).status == StoryDraftStore.Status.MISSING) first = name;
            else second = name;
            storage.values.put(name, before.get(name));
        }
        assertNotNull(first);
        assertNotNull(second);
        storage.values.put(first, before.get(second).clone());
        assertEquals(StoryDraftStore.Status.ERROR, store.load("456", 123, 5).status);
        assertFalse(store.save("456", 123, 5, "overwrite"));
        assertArrayEquals(before.get(second), storage.values.get(first));
        storage.values.get(first)[storage.values.get(first).length - 1] ^= 1;
        assertEquals(StoryDraftStore.Status.ERROR, store.load("456", 123, 5).status);
    }

    @Test public void lostOrUnavailableKeyPreservesCiphertext() {
        assertTrue(store.save("456", 123, 5, "message"));
        byte[] before = storage.values.values().iterator().next().clone();
        keys.key = null;
        assertEquals(StoryDraftStore.Status.ERROR, store.load("456", 123, 5).status);
        assertFalse(store.save("456", 123, 5, "new"));
        assertArrayEquals(before, storage.values.values().iterator().next());
        keys.fail = true;
        assertEquals(StoryDraftStore.Status.ERROR, store.load("456", 123, 5).status);
    }

    @Test public void emptyAndExplicitClearDeleteOnlySelectedDraft() {
        assertTrue(store.save("456", 123, 5, "one"));
        assertTrue(store.save("456", 123, 6, "two"));
        assertTrue(store.save("456", 123, 5, ""));
        assertEquals(StoryDraftStore.Status.MISSING, store.load("456", 123, 5).status);
        assertEquals("two", store.load("456", 123, 6).text);
        assertTrue(store.clear("456", 123, 6));
        assertTrue(storage.values.isEmpty());
    }

    @Test public void listRestoresExpiredStoriesAndIsolatesAccounts() {
        assertTrue(store.save("456", -123, 5, "expired story"));
        assertTrue(store.save("456", 123, 6, "another story"));
        assertTrue(store.save("789", 123, 5, "other account"));
        StoryDraftStore restarted = new StoryDraftStore(storage, keys);
        StoryDraftStore.ListResult list = restarted.list("456");
        assertTrue(list.complete);
        assertEquals(2, list.drafts.size());
        assertTrue(list.drafts.stream().anyMatch(d -> d.ownerId == -123 && d.storyId == 5 && d.text.equals("expired story")));
        assertTrue(list.drafts.stream().anyMatch(d -> d.ownerId == 123 && d.storyId == 6 && d.text.equals("another story")));
        assertEquals("other account", restarted.list("789").drafts.get(0).text);
        assertTrue(restarted.list("111").drafts.isEmpty());
        assertFalse(restarted.list("").complete);
    }

    @Test public void listReportsDamagedRecordsWhileReturningOtherDrafts() {
        assertTrue(store.save("456", 123, 5, "one"));
        assertTrue(store.save("456", 123, 6, "two"));
        byte[] damaged = storage.values.values().iterator().next();
        damaged[damaged.length - 1] ^= 1;
        StoryDraftStore.ListResult list = store.list("456");
        assertFalse(list.complete);
        assertEquals(1, list.drafts.size());
    }

    @Test public void conditionalClearKeepsNewerDraft() {
        assertTrue(store.save("456", 123, 5, "old"));
        assertTrue(store.save("456", 123, 5, "new"));
        assertEquals(StoryDraftStore.ClearResult.CHANGED, store.clearIfMatches("456", 123, 5, "old"));
        assertEquals("new", store.load("456", 123, 5).text);
        assertEquals(StoryDraftStore.ClearResult.DELETED, store.clearIfMatches("456", 123, 5, "new"));
        assertEquals(StoryDraftStore.ClearResult.CHANGED, store.clearIfMatches("456", 123, 5, "new"));
        storage.fail = true;
        assertEquals(StoryDraftStore.ClearResult.ERROR, store.clearIfMatches("456", 123, 5, "new"));
    }

    @Test public void invalidIdentityAndStorageFailuresAreReportedWithoutLeaks() {
        assertFalse(store.save(null, 123, 5, "secret"));
        assertFalse(store.save("456", 0, 5, "secret"));
        assertFalse(store.save("456", 123, 0, "secret"));
        assertTrue(storage.values.isEmpty());
        storage.fail = true;
        assertFalse(store.save("456", 123, 5, "secret"));
        assertEquals(StoryDraftStore.Status.ERROR, store.load("456", 123, 5).status);
        assertFalse(store.clear("456", 123, 5));
    }

    private static final class MemoryStorage implements StoryDraftStore.Storage {
        final Map<String, byte[]> values = new HashMap<>();
        boolean fail;
        @Override public byte[] read(String name) throws IOException {
            if (fail) throw new IOException();
            return values.get(name);
        }
        @Override public void write(String name, byte[] value) throws IOException {
            if (fail) throw new IOException();
            values.put(name, value.clone());
        }
        @Override public void delete(String name) throws IOException {
            if (fail) throw new IOException();
            values.remove(name);
        }
        @Override public List<String> list(String prefix) throws IOException {
            if (fail) throw new IOException();
            List<String> result = new ArrayList<>();
            for (String key : values.keySet()) if (key.startsWith(prefix)) result.add(key);
            return result;
        }
    }

    private static final class TestKeys implements StoryDraftStore.Keys {
        SecretKey key;
        boolean fail;
        @Override public SecretKey get(boolean create) throws GeneralSecurityException {
            if (fail) throw new GeneralSecurityException();
            if (key == null && create) {
                KeyGenerator generator = KeyGenerator.getInstance("AES");
                generator.init(256);
                key = generator.generateKey();
            }
            return key;
        }
    }
}
