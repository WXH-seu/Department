package edu.seu.department.auth;

import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminBootstrapService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;

    public AdminBootstrapService(JdbcTemplate jdbc, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.passwords = passwords;
    }

    /** Only seeds an empty installation; never resets or promotes an existing account. */
    @Transactional
    public boolean initialize(String username, String password) {
        if (username == null || !username.matches("[a-zA-Z0-9_.-]{3,64}")) {
            throw new IllegalArgumentException("Bootstrap username must contain 3-64 ASCII letters, digits, dots, underscores or hyphens");
        }
        if (password == null || password.length() < 12 || password.isBlank()
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Bootstrap password must contain at least 12 characters and at most 72 UTF-8 bytes");
        }
        // The seeded role row serializes concurrent initializers, even before any users exist.
        jdbc.queryForObject("SELECT code FROM app_role WHERE code = 'ADMIN' FOR UPDATE", String.class);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Long.class) != 0) {
            return false;
        }
        jdbc.update("INSERT INTO app_user (username, password_hash, display_name) VALUES (?, ?, ?)",
            username, passwords.encode(password), "系统管理员");
        jdbc.update("INSERT INTO user_role (user_id, role_code) SELECT id, 'ADMIN' FROM app_user WHERE username = ?",
            username);
        return true;
    }
}
