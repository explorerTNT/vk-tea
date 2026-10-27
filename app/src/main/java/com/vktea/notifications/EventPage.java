package com.vktea.notifications;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class EventPage {
    public final List<NotificationEvent> items;
    public final String nextFrom;
    public final long lastViewed;
    public EventPage(List<NotificationEvent> items, String nextFrom, long lastViewed) {
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.nextFrom = nextFrom == null ? "" : nextFrom;
        this.lastViewed = lastViewed;
    }
}
