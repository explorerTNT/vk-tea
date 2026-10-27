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

    public ChatMessage(long id, boolean out, long fromId, String author, String avatar,
                       String body, long date, List<ChatAttachment> attachments) {
        this(id, out, fromId, author, avatar, body, date, attachments, -1);
    }

    public ChatMessage(long id, boolean out, long fromId, String author, String avatar,
                       String body, long date, List<ChatAttachment> attachments, int readState) {
        this(id, out, fromId, author, avatar, body, date, attachments, readState, SendStatus.NONE);
    }

    private ChatMessage(long id, boolean out, long fromId, String author, String avatar,
                        String body, long date, List<ChatAttachment> attachments, int readState, SendStatus sendStatus) {
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
    }

    public ChatMessage withSendStatus(SendStatus status) {
        return new ChatMessage(id, out, fromId, author, avatar, body, date, attachments, readState, status);
    }
}
