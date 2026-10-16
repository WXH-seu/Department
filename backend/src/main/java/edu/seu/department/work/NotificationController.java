package edu.seu.department.work;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final JdbcTemplate jdbc;
    private final WorkAccess access;
    public NotificationController(JdbcTemplate jdbc,WorkAccess access) { this.jdbc=jdbc;this.access=access; }
    @GetMapping public List<Map<String,Object>> list(Authentication actor) {
        return jdbc.queryForList("SELECT id,task_id,message,is_read,created_at FROM notification WHERE user_id=? ORDER BY id DESC LIMIT 200",access.id(actor));
    }
    @PostMapping("/{id}/read") public void read(Authentication actor,@PathVariable long id) {
        jdbc.update("UPDATE notification SET is_read=TRUE WHERE id=? AND user_id=?",id,access.id(actor));
    }
}
