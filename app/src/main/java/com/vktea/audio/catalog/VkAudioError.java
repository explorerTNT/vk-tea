package com.vktea.audio.catalog;

/** A deliberately narrow error: server text, request URLs and nested causes never escape transport. */
public final class VkAudioError extends Exception {
    public enum Kind {
        AUTH_REQUIRED, ACCESS_DENIED, RATE_LIMITED, NETWORK, HTTP,
        MALFORMED_RESPONSE, API, INVALID_REQUEST, PLAYLIST_VISIBILITY_UNKNOWN
    }

    public final Kind kind;
    public final int apiCode;
    public final int httpStatus;

    public VkAudioError(Kind kind) {
        this(kind, 0, 0);
    }

    public VkAudioError(Kind kind, int apiCode, int httpStatus) {
        super("VK audio request failed: " + kind.name());
        this.kind = kind;
        this.apiCode = apiCode;
        this.httpStatus = httpStatus;
    }
}
