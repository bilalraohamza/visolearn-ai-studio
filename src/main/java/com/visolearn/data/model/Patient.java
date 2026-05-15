package com.visolearn.data.model;

/**
 * Data model representing a registered patient in VisoLearn AI Studio.
 *
 * <p>Instances are constructed by {@link com.visolearn.data.PatientDAO}
 * from result set rows and are displayed in the {@code ListView<Patient>}
 * on the history tab.</p>
 *
 * <p>The class exposes public fields so that JavaFX
 * {@link javafx.scene.control.cell.PropertyValueFactory} implementations can bind
 * directly without requiring additional accessor code.</p>
 *
 * <h3>Clinical fields added for OOP completeness:</h3>
 * <ul>
 *     <li>{@link #gender} — Biological sex / gender identity for clinical context.</li>
 *     <li>{@link #phone} — Contact phone number for follow-up communications.</li>
 *     <li>{@link #skinType} — Fitzpatrick skin phototype (I–VI), critical for
 *         dermoscopy AI since melanoma risk varies significantly by skin type.</li>
 *     <li>{@link #doctorNotes} — Free-text field for the attending clinician to
 *         record patient-level observations, allergies, or medical history.</li>
 * </ul>
 */
public class Patient {

    /** Primary key — assigned by SQLite AUTOINCREMENT on insert. */
    public final int    id;

    /** Patient's full name as recorded at registration. */
    public final String name;

    /** Date of birth in ISO 8601 format ({@code YYYY-MM-DD}). */
    public final String dob;

    /**
     * Biological sex or gender identity.
     * Stored as free-text to support values like "Male", "Female", "Non-binary", etc.
     * May be {@code null} if not provided at registration.
     */
    public final String gender;

    /**
     * Contact phone number for the patient.
     * Stored as free-text to accommodate international formats.
     * May be {@code null} if not provided.
     */
    public final String phone;

    /**
     * Fitzpatrick skin phototype (I through VI).
     * This is clinically significant for dermoscopy AI because melanoma
     * prevalence and presentation vary substantially across phototypes.
     * May be {@code null} if not assessed.
     */
    public final String skinType;

    /**
     * Free-text clinical notes recorded by the attending physician.
     * Intended for medical history, allergies, ongoing treatments,
     * or any patient-level context that may inform diagnostic interpretation.
     * May be {@code null} if no notes have been entered.
     */
    public final String doctorNotes;

    /**
     * Constructs a new {@link Patient} record with all clinical fields.
     *
     * @param id          SQLite-generated primary key.
     * @param name        Patient's full name.
     * @param dob         Date of birth string (ISO 8601).
     * @param gender      Gender / biological sex.
     * @param phone       Contact phone number.
     * @param skinType    Fitzpatrick skin type (I–VI).
     * @param doctorNotes Free-text clinical notes.
     */
    public Patient(int id, String name, String dob,
                   String gender, String phone,
                   String skinType, String doctorNotes) {
        this.id          = id;
        this.name        = name;
        this.dob         = dob;
        this.gender      = gender;
        this.phone       = phone;
        this.skinType    = skinType;
        this.doctorNotes = doctorNotes;
    }

    /**
     * Returns a concise display string for list views.
     * Shows name, DOB, and gender if available.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(name);
        if (dob != null && !dob.isBlank()) {
            sb.append("  (").append(dob).append(")");
        }
        if (gender != null && !gender.isBlank()) {
            sb.append("  ").append(gender);
        }
        return sb.toString();
    }
}