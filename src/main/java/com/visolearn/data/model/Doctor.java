package com.visolearn.data.model;

public class Doctor {
    public final int    id;
    public final String username;
    public final String fullName;
    public final String hashedPassword;

    public Doctor(int id, String username,
                  String fullName, String hashedPassword) {
        this.id             = id;
        this.username       = username;
        this.fullName       = fullName;
        this.hashedPassword = hashedPassword;
    }

    @Override
    public String toString() {
        return fullName + " (@" + username + ")";
    }
}
