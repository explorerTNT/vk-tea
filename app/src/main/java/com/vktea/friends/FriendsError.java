package com.vktea.friends;

/** Safe classification only: no response body, URL, credential or nested transport exception. */
public final class FriendsError extends Exception {
    public enum Kind { AUTH, ACCESS, RATE_LIMITED, NETWORK, HTTP, MALFORMED, API }
    public final Kind kind;
    public final int apiCode, httpStatus;
    public FriendsError(Kind kind) { this(kind, 0, 0); }
    public FriendsError(Kind kind, int apiCode, int httpStatus) {
        super("Friends request failed: " + kind.name());
        this.kind = kind; this.apiCode = apiCode; this.httpStatus = httpStatus;
    }
}
