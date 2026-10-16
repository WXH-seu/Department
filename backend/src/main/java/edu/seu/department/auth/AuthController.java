package edu.seu.department.auth;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final org.springframework.security.crypto.password.PasswordEncoder passwords;
    public AuthController(org.springframework.jdbc.core.JdbcTemplate jdbc, org.springframework.security.crypto.password.PasswordEncoder passwords) {
        this.jdbc=jdbc;this.passwords=passwords;
    }
    public record PasswordChange(@jakarta.validation.constraints.NotBlank String currentPassword,
        @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(min=12,max=72) String newPassword) {}
    @org.springframework.web.bind.annotation.PostMapping("/password")
    @org.springframework.transaction.annotation.Transactional
    public void password(Authentication actor,@jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody PasswordChange input) {
        if(input.newPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"密码最多 72 个 UTF-8 字节");
        String hash=jdbc.queryForObject("SELECT password_hash FROM app_user WHERE username=? FOR UPDATE",String.class,actor.getName());
        if(!passwords.matches(input.currentPassword(),hash))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"当前密码不正确");
        jdbc.update("UPDATE app_user SET password_hash=?,must_change_password=FALSE,session_version=session_version+1 WHERE username=?",passwords.encode(input.newPassword()),actor.getName());
        jdbc.update("INSERT INTO audit_event(actor,action,target) VALUES (?,'PASSWORD_CHANGED',?)",actor.getName(),actor.getName());
    }
    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @GetMapping("/me")
    public CurrentUser me(Authentication authentication) {
        boolean mustChange = authentication.getPrincipal() instanceof AccountPrincipal principal && principal.mustChangePassword();
        String displayName = jdbc.queryForObject("SELECT display_name FROM app_user WHERE username=?",String.class,authentication.getName());
        return new CurrentUser(authentication.getName(), displayName, authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority()).toList(), mustChange);
    }

    public record CsrfResponse(String headerName, String token) {}
    public record CurrentUser(String username, String displayName, List<String> authorities, boolean mustChangePassword) {}
}
