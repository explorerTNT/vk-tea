package com.vktea.msg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Display data for one history message, independent of its recycled view. */
public final class ChatMessage {
    public enum SendStatus { NONE, SENDING, UNCONFIRMED }

    public final long id;
    public final boolean out;
    public final long fromId;
    public final String author;
    public final String avatar;
    public final String body;
    public final long date;
    /** -1 when the API does not provide a read state, 0 unread, 1 read. */
    public final int readState;
    public final SendStatus sendStatus;
    public final List<ChatAttachment> attachments;
    /** Forwarded messages kept as structured rows the way Coffee nests them. */
    public final List<ChatMessage> forwarded;
    /** Quoted message when VK returns reply_message; null otherwise. */
    public final ChatMessage replyTo;
    /** False when the author row was not part of the API response yet. */
    public final boolean authorKnown;

    public ChatMessage(long id, boolean out, long fromId, String author, String avatar,
                       String body, long date, List<ChatAttachment> attachments) {
        this(id, out, fromId, author, avatar, body, date, attachments, -1);
    }

    public ChatMessage(long id, boolean out, long fromId, String author, String avatar,
                       String body, long date, List<ChatAttachment> attachments, int readState) {
        this(id, out, fromId, author, avatar, body, date, attachments, readState,
                Collections.emptyList(), null);
    }

    public ChatMessage(long id, boolean out, long fromId, String author, String avatar,
                       String body, long date, List<ChatAttachment> attachments, int readState,
                       List<ChatMessage> forwarded, ChatMessage replyTo) {
        this(id, out, fromId, author, avatar, body, date, attachments, readState,
                forwarded, replyTo, true);
    }

    ChatMessage(long id, boolean out, long fromId, String author, String avatar,
                String body, long date, List<ChatAttachment> attachments, int readState,
                List<ChatMessage> forwarded, ChatMessage replyTo, boolean authorKnown) {
        this(id, out, fromId, author, avatar, body, date, attachments, readState,
                forwarded, replyTo, authorKnown, SendStatus.NONE);
    }

    private ChatMessage(long id, boolean out, long fromId, String author, String avatar,
                        String body, long date, List<ChatAttachment> attachments, int readState,
                        List<ChatMessage> forwarded, ChatMessage replyTo, boolean authorKnown,
                        SendStatus sendStatus) {
        this.id = id;
        this.out = out;
        this.fromId = fromId;
        this.author = author == null ? "" : author;
        this.avatar = avatar == null ? "" : avatar;
        this.body = body == null ? "" : body;
        this.date = date;
        this.readState = readState == 0 || readState == 1 ? readState : -1;
        this.sendStatus = sendStatus;
        this.attachments = Collections.unmodifiableList(new ArrayList<>(
                attachments == null ? Collections.emptyList() : attachments));
        this.forwarded = Collections.unmodifiableList(new ArrayList<>(
                forwarded == null ? Collections.emptyList() : forwarded));
        this.replyTo = replyTo;
        this.authorKnown = authorKnown;
    }

    public ChatMessage withSendStatus(SendStatus status) {
        return new ChatMessage(id, out, fromId, author, avatar, body, date, attachments,
                readState, forwarded, replyTo, authorKnown, status);
    }

    /** Returns a copy with a resolved author identity after an explicit lookup. */
    public ChatMessage withIdentity(String author, String avatar) {
        return new ChatMessage(id, out, fromId,
                author == null || author.isEmpty() ? this.author : author,
                avatar == null ? this.avatar : avatar,
                body, date, attachments, readState, forwarded, replyTo, true, sendStatus);
    }
}
