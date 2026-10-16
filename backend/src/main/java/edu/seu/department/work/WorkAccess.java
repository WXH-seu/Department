package edu.seu.department.work;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class WorkAccess {
    private final JdbcTemplate jdbc;
    public WorkAccess(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public boolean role(Authentication actor, String role) {
        return actor.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
    }
    public boolean global(Authentication actor) { return role(actor, "ADMIN") || role(actor, "MINISTER"); }
    public long id(Authentication actor) {
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, actor.getName());
    }
    public boolean manages(Authentication actor, long group) {
        return global(actor) || (role(actor, "LEADER") && jdbc.queryForObject(
            "SELECT COUNT(*) FROM group_member WHERE user_id = ? AND group_id = ? AND is_leader = TRUE",
            Long.class, id(actor), group) > 0);
    }
    public void require(boolean allowed) {
        if (!allowed) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "没有此操作的权限");
    }
    public void audit(Authentication actor, String action, Object target) {
        jdbc.update("INSERT INTO audit_event (actor, action, target) VALUES (?, ?, ?)", actor.getName(), action, String.valueOf(target));
    }
}
