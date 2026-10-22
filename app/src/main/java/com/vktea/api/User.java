package com.vktea.api;

public class User {

    public final long id;
    public final String firstName;
    public final String lastName;
    public final String photo200;
    public final String status;

    public User(long id, String firstName, String lastName, String photo200, String status) {
        this.id = id;
        this.firstName = firstName;
        this.lastName = lastName;
        this.photo200 = photo200;
        this.status = status;
    }

    public String fullName() {
        return firstName + " " + lastName;
    }
}
