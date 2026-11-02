package com.vktea.notifications;

import android.content.Context;

/** Read-only requests; callbacks run on the main thread. */
public interface NotificationsRepository extends AutoCloseable {
    interface Request { void cancel(); }
    interface Callback<T> { void success(T value); void error(NotificationsError error); }
    static NotificationsRepository create(Context context) { return new VkNotificationsRepository(context.getApplicationContext()); }
    Request load(String startFrom, Callback<EventPage> callback);
    @Override void close();
}
