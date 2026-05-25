package com.visolearn;

import com.visolearn.data.model.Doctor;

/**
 * Holds the currently logged-in doctor for the
 * entire application session.
 * Set once after successful login.
 * Never null after login succeeds.
 */
public final class SessionManager {

    private static Doctor currentDoctor;

    private SessionManager() {}

    public static void setCurrentDoctor(Doctor d) {
        currentDoctor = d;
    }

    public static Doctor getCurrentDoctor() {
        return currentDoctor;
    }

    public static int getCurrentDoctorId() {
        return currentDoctor != null
            ? currentDoctor.id : -1;
    }

    public static String getCurrentDoctorName() {
        return currentDoctor != null
            ? currentDoctor.fullName : "Unknown";
    }

    public static void logout() {
        currentDoctor = null;
    }
}
