package edu.seu.department.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {
    private final JdbcTemplate jdbc;

    public DatabaseUserDetailsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        var users = jdbc.query("SELECT id, username, password_hash, enabled, session_version, must_change_password FROM app_user WHERE username = ?",
            (rs, row) -> {
                var roles = jdbc.queryForList("SELECT role_code FROM user_role WHERE user_id = ?",
                    String.class, rs.getLong("id"));
                return new AccountPrincipal(rs.getLong("id"),rs.getLong("session_version"),rs.getBoolean("must_change_password"),
                    rs.getString("username"),rs.getString("password_hash"),rs.getBoolean("enabled"),
                    roles.stream().map(role -> new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role)).toList());
            }, username);
        if (users.isEmpty()) {
            throw new UsernameNotFoundException("Invalid credentials");
        }
        return users.getFirst();
    }
}
