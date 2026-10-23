package com.vktea.api;

public class User {

    public final long id;
    public final String firstName;
    public final String lastName;
    public final String photo200;
    public final String status;
    public final Counters counters;
    public final String coverUrl;

    public User(long id, String firstName, String lastName, String photo200, String status, Counters counters) {
        this(id, firstName, lastName, photo200, status, counters, null);
    }

    public User(long id, String firstName, String lastName, String photo200, String status, Counters counters, String coverUrl) {
        this.id = id;
        this.firstName = firstName;
        this.lastName = lastName;
        this.photo200 = photo200;
        this.status = status;
        this.counters = counters;
        this.coverUrl = coverUrl;
    }

    public String fullName() {
        return firstName + " " + lastName;
    }

    public static class Counters {
        public final int friends;
        public final int messages;
        public final int notifications;
        public final int groups;

        public Counters(int friends, int messages, int notifications, int groups) {
            this.friends = friends;
            this.messages = messages;
            this.notifications = notifications;
            this.groups = groups;
        }
    }
}
