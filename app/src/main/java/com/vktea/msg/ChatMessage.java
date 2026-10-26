package com.vktea.msg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Display data for one history message, independent of its recycled view. */
public final class ChatMessage {
    public final long id;
    public final boolean out;
    public final long fromId;
    public final String author;
    public final String avatar;
    public final String body;
    public final long date;
    public final List<ChatAttachment> attachments;

    public ChatMessage(long id, boolean out, long fromId, String author, String avatar,
                       String body, long date, List<ChatAttachment> attachments) {
        this.id = id;
        this.out = out;
        this.fromId = fromId;
        this.author = author == null ? "" : author;
        this.avatar = avatar == null ? "" : avatar;
        this.body = body == null ? "" : body;
        this.date = date;
        this.attachments = Collections.unmodifiableList(new ArrayList<>(
                attachments == null ? Collections.emptyList() : attachments));
    }
}
