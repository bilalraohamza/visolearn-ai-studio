package com.visolearn.data.model;

/**
 * Immutable data model representing a registered patient in VisoLearn AI Studio.
 *
 * <p>Instances are constructed by {@link com.visolearn.data.PatientDAO}
 * from result set rows and are displayed in the {@code ListView<Patient>}
 * on the history tab.</p>
 *
 * <p>The class exposes public fields so that JavaFX
 * {@link javafx.scene.control.cell.PropertyValueFactory} implementations can bind
 * directly without requiring additional accessor code.</p>
 */
public class Patient {

    /** Primary key — assigned by SQLite AUTOINCREMENT on insert. */
    public final int    id;

    /** Patient's full name as recorded at registration. */
    public final String name;

    /** Date of birth in ISO 8601 format ({@code YYYY-MM-DD}). */
    public final String dob;

    /**
     * Constructs a new {@link Patient} record.
     *
     * @param id   SQLite-generated primary key.
     * @param name Patient's full name.
     * @param dob  Date of birth string.
     */
    public Patient(int id, String name, String dob) {
        this.id   = id;
        this.name = name;
        this.dob  = dob;
    }

    /**
     * Returns the patient's display name.
     * Used as the default text for {@code ListCell<Patient>} rendering.
     */
    @Override
    public String toString() {
        return name + (dob != null && !dob.isBlank() ? "  (" + dob + ")" : "");
    }
}