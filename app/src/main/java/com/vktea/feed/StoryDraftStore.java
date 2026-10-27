package com.vktea.feed;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypted, device-local drafts. Call on a serial background executor. */
public final class StoryDraftStore {
    private static final String ALIAS = "story_reply_drafts_v1";
    private static final int NONCE_BYTES = 12;
    private static final int MAX_BYTES = 128 * 1024;
    private static final int MAX_LIST_ITEMS = 10000;
    private static final byte VERSION = 1;
    private static final Object DRAFT_LOCK = new Object();
    private final Storage storage;
    private final Keys keys;

    interface Storage {
        byte[] read(String name) throws IOException;
        void write(String name, byte[] value) throws IOException;
        void delete(String name) throws IOException;
        List<String> list(String prefix) throws IOException;
    }

    interface Keys {
        SecretKey get(boolean create) throws GeneralSecurityException, IOException;
    }

    StoryDraftStore(Storage storage, Keys keys) {
        this.storage = storage;
        this.keys = keys;
    }

    public static StoryDraftStore create(Context context) {
        return new StoryDraftStore(new FileStorage(new File(context.getNoBackupFilesDir(), "story_reply_drafts")),
                new AndroidKeys());
    }

    public enum Status { FOUND, MISSING, ERROR }

    public static final class Result {
        public final Status status;
        public final String text;
        private Result(Status status, String text) { this.status = status; this.text = text; }
    }

    public static final class Draft {
        public final long ownerId;
        public final long storyId;
        public final String text;
        private Draft(long ownerId, long storyId, String text) {
            this.ownerId = ownerId; this.storyId = storyId; this.text = text;
        }
    }

    public static final class ListResult {
        public final List<Draft> drafts;
        public final boolean complete;
        private ListResult(List<Draft> drafts, boolean complete) {
            this.drafts = Collections.unmodifiableList(drafts); this.complete = complete;
        }
    }

    public ListResult list(String accountId) {
        synchronized (DRAFT_LOCK) {
        List<Draft> drafts = new ArrayList<>();
        boolean complete = true;
        try {
            byte[] account = account(accountId);
            List<String> names = storage.list(prefix(account));
            if (names.size() > MAX_LIST_ITEMS) complete = false;
            for (String filename : names.subList(0, Math.min(names.size(), MAX_LIST_ITEMS))) {
                try {
                    Draft draft = decrypt(accountId, account, filename, storage.read(filename));
                    if (draft == null) complete = false;
                    else drafts.add(draft);
                } catch (Exception e) {
                    complete = false;
                }
            }
        } catch (Exception e) {
            complete = false;
        }
        return new ListResult(drafts, complete);
        }
    }

    public Result load(String accountId, long ownerId, long storyId) {
        synchronized (DRAFT_LOCK) {
        try {
            byte[] account = account(accountId);
            String filename = name(accountId, ownerId, storyId);
            byte[] value = storage.read(filename);
            if (value == null) return new Result(Status.MISSING, null);
            Draft draft = decrypt(accountId, account, filename, value);
            if (draft == null || draft.ownerId != ownerId || draft.storyId != storyId)
                return new Result(Status.ERROR, null);
            return new Result(Status.FOUND, draft.text);
        } catch (Exception e) {
            return new Result(Status.ERROR, null);
        }
        }
    }

    public boolean save(String accountId, long ownerId, long storyId, String text) {
        synchronized (DRAFT_LOCK) {
        if (text == null) return false;
        if (text.isEmpty()) return clear(accountId, ownerId, storyId);
        try {
            byte[] account = account(accountId);
            byte[] plaintext = text.getBytes(StandardCharsets.UTF_8);
            if (plaintext.length > MAX_BYTES - 1 - NONCE_BYTES - 16 - 16) return false;
            // Never replace an existing draft when its key has disappeared.
            String name = name(accountId, ownerId, storyId);
            boolean existing = storage.read(name) != null;
            if (existing && load(accountId, ownerId, storyId).status != Status.FOUND) return false;
            SecretKey key = keys.get(!existing);
            if (key == null) return false;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            cipher.updateAAD(account);
            byte[] payload = ByteBuffer.allocate(16 + plaintext.length)
                    .putLong(ownerId).putLong(storyId).put(plaintext).array();
            byte[] encrypted = cipher.doFinal(payload);
            byte[] nonce = cipher.getIV();
            if (nonce.length != NONCE_BYTES) return false;
            byte[] output = new byte[1 + nonce.length + encrypted.length];
            output[0] = VERSION;
            System.arraycopy(nonce, 0, output, 1, nonce.length);
            System.arraycopy(encrypted, 0, output, 1 + nonce.length, encrypted.length);
            storage.write(name, output);
            return true;
        } catch (Exception e) {
            return false;
        }
        }
    }

    public boolean clear(String accountId, long ownerId, long storyId) {
        synchronized (DRAFT_LOCK) {
        try {
            storage.delete(name(accountId, ownerId, storyId));
            return true;
        } catch (Exception e) {
            return false;
        }
        }
    }

    public enum ClearResult { DELETED, CHANGED, ERROR }

    public ClearResult clearIfMatches(String accountId, long ownerId, long storyId, String expectedText) {
        synchronized (DRAFT_LOCK) {
            if (expectedText == null) return ClearResult.ERROR;
            Result current = load(accountId, ownerId, storyId);
            if (current.status == Status.ERROR) return ClearResult.ERROR;
            if (current.status != Status.FOUND || !expectedText.equals(current.text)) return ClearResult.CHANGED;
            return clear(accountId, ownerId, storyId) ? ClearResult.DELETED : ClearResult.ERROR;
        }
    }

    private Draft decrypt(String accountId, byte[] account, String filename, byte[] value)
            throws GeneralSecurityException, IOException {
        if (value == null || value.length < 1 + NONCE_BYTES + 16 + 16 || value.length > MAX_BYTES || value[0] != VERSION)
            return null;
        SecretKey key = keys.get(false);
        if (key == null) return null;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, value, 1, NONCE_BYTES));
        cipher.updateAAD(account);
        ByteBuffer plaintext = ByteBuffer.wrap(cipher.doFinal(value, 1 + NONCE_BYTES, value.length - 1 - NONCE_BYTES));
        long ownerId = plaintext.getLong();
        long storyId = plaintext.getLong();
        if (!filename.equals(name(accountId, ownerId, storyId))) return null;
        byte[] text = new byte[plaintext.remaining()];
        plaintext.get(text);
        if (text.length == 0) return null;
        return new Draft(ownerId, storyId, new String(text, StandardCharsets.UTF_8));
    }

    private static byte[] account(String accountId) {
        if (accountId == null || !accountId.matches("[0-9]{1,20}"))
            throw new IllegalArgumentException("Invalid draft identity");
        return ("story-draft-v1:" + accountId)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String name(String accountId, long ownerId, long storyId) throws GeneralSecurityException {
        byte[] account = account(accountId);
        if (ownerId == 0 || storyId <= 0) throw new IllegalArgumentException("Invalid draft identity");
        return prefix(account) + hash((accountId + ":" + ownerId + ":" + storyId).getBytes(StandardCharsets.UTF_8));
    }

    private static String prefix(byte[] account) throws GeneralSecurityException { return hash(account) + "-"; }

    private static String hash(byte[] value) throws GeneralSecurityException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder(64);
        for (byte b : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }

    private static final class AndroidKeys implements Keys {
        private static final Object LOCK = new Object();
        @Override public SecretKey get(boolean create) throws GeneralSecurityException, IOException {
            synchronized (LOCK) {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
            if (!create) return null;
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build());
            return generator.generateKey();
            }
        }
    }

    private static final class FileStorage implements Storage {
        private final File directory;
        FileStorage(File directory) { this.directory = directory; }
        @Override public byte[] read(String name) throws IOException {
            File file = new File(directory, name);
            AtomicFile atomic = new AtomicFile(file);
            // openRead also recovers the .bak file after an interrupted write.
            if (!file.exists() && !new File(file.getPath() + ".bak").exists()) return null;
            try (FileInputStream in = atomic.openRead(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (out.size() + count > MAX_BYTES) throw new IOException("Draft too large");
                    out.write(buffer, 0, count);
                }
                return out.toByteArray();
            }
        }
        @Override public void write(String name, byte[] value) throws IOException {
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
                throw new IOException("Cannot create draft directory");
            AtomicFile atomic = new AtomicFile(new File(directory, name));
            FileOutputStream out = atomic.startWrite();
            try {
                out.write(value);
                atomic.finishWrite(out);
            } catch (IOException e) {
                atomic.failWrite(out);
                throw e;
            }
        }
        @Override public void delete(String name) throws IOException {
            File file = new File(directory, name);
            new AtomicFile(file).delete();
            if (file.exists() || new File(file.getPath() + ".bak").exists()
                    || new File(file.getPath() + ".new").exists())
                throw new IOException("Cannot remove draft");
        }
        @Override public List<String> list(String prefix) throws IOException {
            if (!directory.exists()) return Collections.emptyList();
            File[] files = directory.listFiles();
            if (files == null) throw new IOException("Cannot list drafts");
            Set<String> names = new HashSet<>();
            for (File file : files) {
                String name = file.getName();
                if (name.endsWith(".bak")) name = name.substring(0, name.length() - 4);
                if (name.startsWith(prefix) && name.length() == prefix.length() + 64
                        && name.substring(prefix.length()).matches("[0-9a-f]{64}")) names.add(name);
            }
            List<String> result = new ArrayList<>(names);
            Collections.sort(result);
            return result;
        }
    }
}
