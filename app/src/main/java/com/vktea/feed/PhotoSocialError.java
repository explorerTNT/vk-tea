package com.vktea.feed;

/** No response text, request parameters or nested transport cause reaches the UI. */
public final class PhotoSocialError extends Exception {
    public enum Kind { AUTH, ACCESS, RATE_LIMITED, NETWORK, API, UNAVAILABLE, INVALID }
    public final Kind kind;
    public final int code;
    public PhotoSocialError(Kind kind) { this(kind, 0); }
    public PhotoSocialError(Kind kind, int code) {
        super("Photo request failed: " + kind.name());
        this.kind = kind; this.code = code;
    }
}
