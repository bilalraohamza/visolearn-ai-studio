package com.visolearn.data;

import com.visolearn.data.model.Doctor;
import org.mindrot.jbcrypt.BCrypt;

import java.sql.*;

public class DoctorDAO {

    /**
     * Registers a new doctor. Hashes the password
     * with BCrypt before storing. Returns the new
     * doctor's generated id, or -1 on failure.
     */
    public int register(String username,
                        String fullName,
                        String plainPassword)
            throws SQLException {
        String hash = BCrypt.hashpw(
            plainPassword, BCrypt.gensalt(12));
        String sql =
            "INSERT INTO Doctors " +
            "(username, full_name, password_hash) " +
            "VALUES (?, ?, ?)";
        try (Connection c = DatabaseUtil.getConnection();
             PreparedStatement ps =
                 c.prepareStatement(sql,
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, fullName);
            ps.setString(3, hash);
            ps.executeUpdate();
            var keys = ps.getGeneratedKeys();
            return keys.next() ? keys.getInt(1) : -1;
        }
    }

    /**
     * Attempts login. Returns the Doctor object if
     * username exists and password matches the hash.
     * Returns null if login fails for any reason.
     * Never throws on wrong password — only on DB error.
     */
    public Doctor login(String username,
                        String plainPassword)
            throws SQLException {
        String sql =
            "SELECT id, username, full_name, " +
            "password_hash FROM Doctors " +
            "WHERE username = ?";
        try (Connection c = DatabaseUtil.getConnection();
             PreparedStatement ps =
                 c.prepareStatement(sql)) {
            ps.setString(1, username);
            var rs = ps.executeQuery();
            if (!rs.next()) return null;

            String hash = rs.getString("password_hash");
            if (!BCrypt.checkpw(plainPassword, hash)) {
                return null;
            }
            return new Doctor(
                rs.getInt("id"),
                rs.getString("username"),
                rs.getString("full_name"),
                hash
            );
        }
    }

    /**
     * Returns true if the Doctors table has zero rows.
     * Used on first launch to decide whether to show
     * the registration form instead of login.
     */
    public boolean hasNoDoctors() throws SQLException {
        String sql = "SELECT COUNT(*) FROM Doctors";
        try (Connection c = DatabaseUtil.getConnection();
             PreparedStatement ps =
                 c.prepareStatement(sql)) {
            var rs = ps.executeQuery();
            return rs.next() && rs.getInt(1) == 0;
        }
    }
}
