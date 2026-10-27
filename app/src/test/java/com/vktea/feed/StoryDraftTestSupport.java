package com.vktea.feed;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import static org.junit.Assert.assertTrue;

/** Synthetic encrypted storage, shared by offline Activity and ViewModel tests. */
final class StoryDraftTestSupport {
    final MemoryStorage storage = new MemoryStorage();
    final StoryDraftStore store;
    StoryDraftTestSupport() throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance("AES"); generator.init(256);
        SecretKey key = generator.generateKey();
        store = new StoryDraftStore(storage, create -> key);
    }
    static void drain() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        StoryDraftCoordinator.execute(done::countDown);
        assertTrue("Draft operations did not finish", done.await(5, TimeUnit.SECONDS));
    }
    static final class MemoryStorage implements StoryDraftStore.Storage {
        final Map<String, byte[]> values = new HashMap<>();
        boolean fail;
        @Override public byte[] read(String name) throws IOException { if (fail) throw new IOException(); return values.get(name); }
        @Override public void write(String name, byte[] bytes) throws IOException { if (fail) throw new IOException(); values.put(name, bytes.clone()); }
        @Override public void delete(String name) throws IOException { if (fail) throw new IOException(); values.remove(name); }
        @Override public List<String> list(String prefix) throws IOException {
            if (fail) throw new IOException();
            List<String> names = new ArrayList<>();
            for (String name : values.keySet()) if (name.startsWith(prefix)) names.add(name);
            return names;
        }
    }
}
