package com.vktea.notifications;

/** Safe error classification, without response content or credentials. */
public final class NotificationsError extends Exception {
    public enum Kind { AUTH, ACCESS, RATE_LIMITED, NETWORK, HTTP, MALFORMED, API }
    public final Kind kind;
    public final int apiCode, httpStatus;
    public NotificationsError(Kind kind) { this(kind, 0, 0); }
    public NotificationsError(Kind kind, int apiCode, int httpStatus) {
        super("Notifications request failed: " + kind.name());
        this.kind = kind; this.apiCode = apiCode; this.httpStatus = httpStatus;
    }
}
