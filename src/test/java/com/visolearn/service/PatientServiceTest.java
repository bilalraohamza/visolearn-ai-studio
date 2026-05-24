package com.visolearn.service;

import com.visolearn.data.PatientDAO;
import com.visolearn.data.model.Patient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PatientService}.
 *
 * <p>The DAO is replaced with an in-memory stub for every test so there is
 * no database, no file system, and no JavaFX runtime required. Each stub
 * records the arguments it received so assertions can verify that the
 * service sent the correct values to the DAO.</p>
 */
@DisplayName("PatientService")
class PatientServiceTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Stubs
    // ─────────────────────────────────────────────────────────────────────────

    /** Minimal DAO stub that returns a fixed patient list and records calls. */
    private static class StubPatientDAO extends PatientDAO {
        String lastSearchQuery   = "NOT_CALLED";
        int    lastDeleteId      = -1;
        // State for insert()
        String insertedName, insertedDob, insertedGender;
        String insertedPhone, insertedSkinType, insertedNotes;
        int    insertReturnId    = 42;

        @Override
        public List<Patient> searchByName(String query) {
            lastSearchQuery = query;
            return List.of(
                    new Patient(1, "Alice", "1990-01-01", "Female", null, "Type II", null),
                    new Patient(2, "Bob",   "1985-06-15", "Male",   null, null,      null)
            );
        }

        @Override
        public int insert(String name, String dob, String gender,
                          String phone, String skinType, String doctorNotes) {
            insertedName     = name;
            insertedDob      = dob;
            insertedGender   = gender;
            insertedPhone    = phone;
            insertedSkinType = skinType;
            insertedNotes    = doctorNotes;
            return insertReturnId;
        }

        @Override
        public void deleteById(int patientId) {
            lastDeleteId = patientId;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Constructor guard
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("null DAO in constructor → IllegalArgumentException")
    void constructor_nullDao_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new PatientService(null));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getAllPatients()
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getAllPatients() calls searchByName(null) and returns full list")
    void getAllPatients_callsDaoWithNull() throws SQLException {
        StubPatientDAO stub = new StubPatientDAO();
        PatientService service = new PatientService(stub);

        List<Patient> result = service.getAllPatients();

        assertNull(stub.lastSearchQuery,
                "getAllPatients() must pass null to searchByName so the DAO returns all records");
        assertEquals(2, result.size());
        assertEquals("Alice", result.get(0).name);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // searchPatients()
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("searchPatients(query) forwards the query to the DAO unchanged")
    void searchPatients_forwardsQuery() throws SQLException {
        StubPatientDAO stub = new StubPatientDAO();
        PatientService service = new PatientService(stub);

        service.searchPatients("Alice");

        assertEquals("Alice", stub.lastSearchQuery,
                "searchPatients() must pass the query string to searchByName");
    }

    @Test
    @DisplayName("searchPatients(null) forwards null (DAO treats null as 'all')")
    void searchPatients_nullForwardsNull() throws SQLException {
        StubPatientDAO stub = new StubPatientDAO();
        PatientService service = new PatientService(stub);

        service.searchPatients(null);

        assertNull(stub.lastSearchQuery);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // registerPatient()
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("registerPatient() returns Patient with DAO-assigned id and given name")
    void registerPatient_returnsCorrectPatient() throws SQLException {
        StubPatientDAO stub = new StubPatientDAO();
        stub.insertReturnId = 99;
        PatientService service = new PatientService(stub);

        Patient patient = service.registerPatient(
                "Charlie", "1995-03-20", "Male", "+1-555-0199", "Type III", "No allergies");

        assertEquals(99,        patient.id,       "id must be the DAO-assigned key");
        assertEquals("Charlie", patient.name);
        assertEquals("Male",    patient.gender);
        assertEquals("Type III", patient.skinType);
    }

    @Test
    @DisplayName("registerPatient() passes correct values to the DAO")
    void registerPatient_delegatesCorrectly() throws SQLException {
        StubPatientDAO stub = new StubPatientDAO();
        PatientService service = new PatientService(stub);

        service.registerPatient("Diana", "2000-12-01", "Female", null, "Type I", "Asthma");

        assertEquals("Diana",       stub.insertedName);
        assertEquals("2000-12-01",  stub.insertedDob);
        assertEquals("Female",      stub.insertedGender);
        assertNull(stub.insertedPhone,  "blank/null phone should be stored as null");
        assertEquals("Type I",      stub.insertedSkinType);
        assertEquals("Asthma",      stub.insertedNotes);
    }

    @Test
    @DisplayName("registerPatient() converts blank strings to null for nullable columns")
    void registerPatient_convertsBlankToNull() throws SQLException {
        StubPatientDAO stub = new StubPatientDAO();
        PatientService service = new PatientService(stub);

        service.registerPatient("Eve", "  ", "Female", "  ", null, "  ");

        assertNull(stub.insertedDob,   "blank DOB must be stored as null");
        assertNull(stub.insertedPhone, "blank phone must be stored as null");
        assertNull(stub.insertedNotes, "blank notes must be stored as null");
    }

    @Test
    @DisplayName("registerPatient() with blank name → IllegalArgumentException (no DAO call)")
    void registerPatient_blankName_throws() {
        StubPatientDAO stub = new StubPatientDAO();
        PatientService service = new PatientService(stub);

        assertThrows(IllegalArgumentException.class,
                () -> service.registerPatient("  ", null, null, null, null, null));
        assertNull(stub.insertedName, "DAO must not be called if validation fails");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // deletePatient()
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("deletePatient(id) forwards the correct id to the DAO")
    void deletePatient_delegatesToDao() throws SQLException {
        StubPatientDAO stub = new StubPatientDAO();
        PatientService service = new PatientService(stub);

        service.deletePatient(7);

        assertEquals(7, stub.lastDeleteId,
                "deletePatient() must forward the patient id to DAO.deleteById");
    }
}
