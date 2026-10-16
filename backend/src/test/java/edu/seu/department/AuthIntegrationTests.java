package edu.seu.department;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;
    @Autowired edu.seu.department.auth.LoginAttempts attempts;

    @BeforeEach
    void seed() {
        attempts.succeeded("member");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM group_member");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("INSERT INTO app_user (username, password_hash, display_name) VALUES (?, ?, ?)",
            "member", encoder.encode("test-password-123"), "测试成员");
        jdbc.update("INSERT INTO user_role (user_id, role_code) SELECT id, 'MEMBER' FROM app_user WHERE username = 'member'");
    }

    @Test
    void anonymousAndCsrfBoundaries() throws Exception {
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").param("username", "member")
            .param("password", "test-password-123")).andExpect(status().isForbidden());
    }

    @Test
    void realSessionLoginAndLogout() throws Exception {
        var token = token(new MockHttpSession());
        var result = mvc.perform(post("/api/v1/auth/login").session(token.session())
            .header(token.header(), token.value()).param("username", "member")
            .param("password", "test-password-123"))
            .andExpect(status().isNoContent()).andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("member"))
            .andExpect(jsonPath("$.authorities[0]").value("ROLE_MEMBER"));
        mvc.perform(post("/api/v1/auth/logout").session(session)).andExpect(status().isForbidden());
        var refreshed = token(session);
        mvc.perform(post("/api/v1/auth/logout").session(session)
            .header(refreshed.header(), refreshed.value())).andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordAndDisabledAccountCannotLogin() throws Exception {
        var token = token(new MockHttpSession());
        mvc.perform(post("/api/v1/auth/login").session(token.session())
            .header(token.header(), token.value()).param("username", "member")
            .param("password", "wrong")).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE app_user SET enabled = FALSE WHERE username = 'member'");
        mvc.perform(post("/api/v1/auth/login").session(token.session())
            .header(token.header(), token.value()).param("username", "member")
            .param("password", "test-password-123")).andExpect(status().isUnauthorized());
        var stored = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE username = 'member'", String.class);
        assertThat(stored).isNotEqualTo("test-password-123");
        assertThat(encoder.matches("test-password-123", stored)).isTrue();
    }

    @Test
    void disablingLoggedInAccountInvalidatesItsSession() throws Exception {
        var session = login();
        jdbc.update("UPDATE app_user SET enabled = FALSE WHERE username = 'member'");
        mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void removingRoleRevokesExistingSession() throws Exception {
        var session = login();
        jdbc.update("DELETE FROM user_role");
        mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void deletingAccountRevokesExistingSession() throws Exception {
        var session = login();
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM app_user");
        mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void repeatedFailuresBlockEvenCorrectPassword() throws Exception {
        var token = token(new MockHttpSession());
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/auth/login").session(token.session())
                .header(token.header(), token.value()).param("username", "member")
                .param("password", "wrong")).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/auth/login").session(token.session())
            .header(token.header(), token.value()).param("username", "member")
            .param("password", "test-password-123"))
            .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        mvc.perform(get("/api/v1/auth/me").session(token.session())).andExpect(status().isUnauthorized());
    }

    private MockHttpSession login() throws Exception {
        var token = token(new MockHttpSession());
        var result = mvc.perform(post("/api/v1/auth/login").session(token.session())
            .header(token.header(), token.value()).param("username", "member")
            .param("password", "test-password-123"))
            .andExpect(status().isNoContent()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
    @Test
    void sessionVersionChangeRevokesOldSession() throws Exception {
        var session=login();
        jdbc.update("UPDATE app_user SET session_version=session_version+1 WHERE username='member'");
        mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
    }
    @Test
    void changingPasswordRequiresCurrentPasswordAndRevokesSession() throws Exception {
        var session=login(); var token=token(session);
        mvc.perform(post("/api/v1/auth/password").session(session).header(token.header(),token.value())
            .contentType("application/json").content("{\"currentPassword\":\"wrong\",\"newPassword\":\"replacement-password\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/password").session(session).header(token.header(),token.value())
            .contentType("application/json").content("{\"currentPassword\":\"test-password-123\",\"newPassword\":\"replacement-password\"}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isUnauthorized());
        assertThat(encoder.matches("replacement-password",jdbc.queryForObject("SELECT password_hash FROM app_user WHERE username='member'",String.class))).isTrue();
    }

    private Token token(MockHttpSession session) throws Exception {
        var result = mvc.perform(get("/api/v1/auth/csrf").session(session))
            .andExpect(status().isOk()).andReturn();
        var body = json.readTree(result.getResponse().getContentAsString());
        return new Token(session, body.get("headerName").asText(), body.get("token").asText());
    }

    record Token(MockHttpSession session, String header, String value) {}
}
