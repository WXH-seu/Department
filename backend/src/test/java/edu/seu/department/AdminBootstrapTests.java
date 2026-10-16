package edu.seu.department;

import static org.assertj.core.api.Assertions.*;

import edu.seu.department.auth.AdminBootstrapService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest
class AdminBootstrapTests {
    @Autowired AdminBootstrapService bootstrap;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM group_member");
        jdbc.update("DELETE FROM app_user");
    }

    @Test
    void initializesHashedAdministratorOnlyOnce() {
        assertThat(bootstrap.initialize("admin", "a-long-test-password")).isTrue();
        String hash = jdbc.queryForObject("SELECT password_hash FROM app_user", String.class);
        assertThat(passwords.matches("a-long-test-password", hash)).isTrue();
        assertThat(jdbc.queryForObject("SELECT role_code FROM user_role", String.class)).isEqualTo("ADMIN");
        assertThat(bootstrap.initialize("another-admin", "a-different-password")).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM app_user", String.class)).isEqualTo(hash);
    }

    @Test
    void refusesToPromoteExistingMember() {
        jdbc.update("INSERT INTO app_user (username, password_hash, display_name) VALUES (?, ?, ?)",
            "member", passwords.encode("member-test-password"), "Member");
        assertThat(bootstrap.initialize("member", "a-long-test-password")).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_role", Integer.class)).isZero();
    }

    @Test
    void rejectsMissingWeakOrOversizedCredentialsWithoutWriting() {
        assertThatIllegalArgumentException().isThrownBy(() -> bootstrap.initialize(null, "long-test-password"));
        assertThatIllegalArgumentException().isThrownBy(() -> bootstrap.initialize("bad name", "long-test-password"));
        assertThatIllegalArgumentException().isThrownBy(() -> bootstrap.initialize("admin", null));
        assertThatIllegalArgumentException().isThrownBy(() -> bootstrap.initialize("admin", "short"));
        assertThatIllegalArgumentException().isThrownBy(() -> bootstrap.initialize("admin", " ".repeat(12)));
        assertThatIllegalArgumentException().isThrownBy(() -> bootstrap.initialize("admin", "密".repeat(25)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class)).isZero();
    }
}
