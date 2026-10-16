package edu.seu.department.work;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class OrganizationController {
    private final JdbcTemplate jdbc;
    private final WorkAccess access;
    private final PasswordEncoder passwords;
    public OrganizationController(JdbcTemplate jdbc, WorkAccess access, PasswordEncoder passwords) {
        this.jdbc = jdbc; this.access = access; this.passwords = passwords;
    }
    @GetMapping("/users")
    public List<Map<String, Object>> users(Authentication actor) {
        if (access.global(actor)) return jdbc.queryForList("SELECT id, username, display_name, enabled FROM app_user ORDER BY id");
        return jdbc.queryForList("SELECT DISTINCT u.id, u.username, u.display_name, u.enabled FROM app_user u LEFT JOIN group_member gm ON gm.user_id = u.id WHERE u.id = ? OR gm.group_id IN (SELECT group_id FROM group_member WHERE user_id = ?) ORDER BY u.id", access.id(actor), access.id(actor));
    }
    public record NewUser(@NotBlank @Pattern(regexp="[a-zA-Z0-9_.-]{3,64}") String username,
        @NotBlank @Size(min=12,max=72) String password, @NotBlank @Size(max=100) String displayName, @NotBlank String role) {}

    @PostMapping("/users") @ResponseStatus(HttpStatus.CREATED) @Transactional
    public void createUser(Authentication actor, @Valid @RequestBody NewUser input) {
        access.require(access.global(actor));
        Set<String> roles = access.role(actor, "ADMIN") ? Set.of("ADMIN","MINISTER","LEADER","MEMBER","GUEST") : Set.of("LEADER","MEMBER","GUEST");
        access.require(roles.contains(input.role()));
        if (input.password().getBytes(StandardCharsets.UTF_8).length > 72) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码最多 72 个 UTF-8 字节");
        jdbc.update("INSERT INTO app_user (username,password_hash,display_name) VALUES (?,?,?)", input.username(), passwords.encode(input.password()), input.displayName().trim());
        jdbc.update("INSERT INTO user_role (user_id,role_code) SELECT id, ? FROM app_user WHERE username = ?", input.role(), input.username());
        access.audit(actor, "USER_CREATED", input.username());
    }
    public record Enabled(@NotNull Boolean enabled) {}
    public record Profile(@NotBlank @Size(max=100) String displayName) {}
    @PatchMapping("/users/{id}/profile") @Transactional
    public void profile(Authentication actor,@PathVariable long id,@Valid @RequestBody Profile input) {
        access.require(access.id(actor)==id || access.global(actor) || (access.role(actor,"LEADER") &&
            jdbc.queryForObject("SELECT COUNT(*) FROM group_member target JOIN group_member manager ON target.group_id=manager.group_id WHERE target.user_id=? AND manager.user_id=? AND manager.is_leader=TRUE",Long.class,id,access.id(actor))>0));
        if(jdbc.update("UPDATE app_user SET display_name=? WHERE id=?",input.displayName().trim(),id)==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"账号不存在");
        access.audit(actor,"PROFILE_UPDATED",id);
    }
    public record Role(@NotBlank String role) {}
    @PatchMapping("/users/{id}/role") @Transactional
    public void role(Authentication actor,@PathVariable long id,@Valid @RequestBody Role input) {
        access.require(access.role(actor,"ADMIN") && access.id(actor)!=id);
        if(!Set.of("ADMIN","MINISTER","LEADER","MEMBER","GUEST").contains(input.role())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"角色无效");
        if(jdbc.update("UPDATE app_user SET session_version=session_version+1 WHERE id=?",id)==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"账号不存在");
        jdbc.update("DELETE FROM user_role WHERE user_id=?",id);
        jdbc.update("INSERT INTO user_role(user_id,role_code) VALUES (?,?)",id,input.role());
        access.audit(actor,"ROLE_CHANGED_"+input.role(),id);
    }
    @PatchMapping("/users/{id}/enabled") @Transactional
    public void enabled(Authentication actor, @PathVariable long id, @Valid @RequestBody Enabled input) {
        access.require(access.global(actor) && access.id(actor) != id);
        boolean privileged = jdbc.queryForObject("SELECT COUNT(*) FROM user_role WHERE user_id = ? AND role_code IN ('ADMIN','MINISTER')", Long.class,id) > 0;
        access.require(!privileged || access.role(actor,"ADMIN"));
        if (jdbc.update("UPDATE app_user SET enabled = ?,session_version=session_version+1 WHERE id = ?",input.enabled(),id) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"账号不存在");
        access.audit(actor,"USER_ENABLED_" + input.enabled(),id);
    }
    @GetMapping("/groups")
    public List<Map<String,Object>> groups(Authentication actor) {
        if (access.global(actor)) return jdbc.queryForList("SELECT id, name FROM department_group ORDER BY id");
        return jdbc.queryForList("SELECT g.id,g.name FROM department_group g JOIN group_member m ON g.id=m.group_id WHERE m.user_id=? ORDER BY g.id",access.id(actor));
    }
    public record Group(@NotBlank @Size(max=100) String name) {}
    @PostMapping("/groups") @ResponseStatus(HttpStatus.CREATED) @Transactional
    public void createGroup(Authentication actor, @Valid @RequestBody Group input) {
        access.require(access.global(actor));
        jdbc.update("INSERT INTO department_group (name) VALUES (?)",input.name().trim());
        access.audit(actor,"GROUP_CREATED",input.name().trim());
    }
    @PatchMapping("/groups/{id}") @Transactional
    public void renameGroup(Authentication actor,@PathVariable long id,@Valid @RequestBody Group input) {
        access.require(access.manages(actor,id));
        if(jdbc.update("UPDATE department_group SET name=? WHERE id=?",input.name().trim(),id)==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"小组不存在");
        access.audit(actor,"GROUP_RENAMED",id);
    }
    @GetMapping("/groups/{id}/members")
    public List<Map<String,Object>> members(Authentication actor,@PathVariable long id) {
        access.require(access.manages(actor,id) || jdbc.queryForObject("SELECT COUNT(*) FROM group_member WHERE group_id=? AND user_id=?",Long.class,id,access.id(actor)) > 0);
        return jdbc.queryForList("SELECT u.id,u.display_name,m.position_name,m.is_leader FROM group_member m JOIN app_user u ON u.id=m.user_id WHERE m.group_id=? ORDER BY u.id",id);
    }
    public record Member(@Positive long userId,@NotNull Boolean leader,@Size(max=100) String position) {}
    @PutMapping("/groups/{id}/members") @Transactional
    public void member(Authentication actor,@PathVariable long id,@Valid @RequestBody Member input) {
        access.require(access.manages(actor,id));
        if(input.leader() && jdbc.queryForObject("SELECT COUNT(*) FROM user_role WHERE user_id=? AND role_code='LEADER'",Long.class,input.userId())==0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"担任组长的账号必须先设置为组长角色");
        if(!access.global(actor)) {
            // Group leaders may edit member positions, but cannot appoint leaders or import outsiders.
            access.require(!input.leader() && jdbc.queryForObject("SELECT COUNT(*) FROM group_member WHERE group_id=? AND user_id=? AND is_leader=FALSE",Long.class,id,input.userId())>0);
        }
        jdbc.update("DELETE FROM group_member WHERE group_id=? AND user_id=?",id,input.userId());
        jdbc.update("INSERT INTO group_member(group_id,user_id,position_name,is_leader) VALUES (?,?,?,?)",id,input.userId(),input.position(),input.leader());
        access.audit(actor,"GROUP_MEMBER_SET",id + ":" + input.userId());
    }
    @DeleteMapping("/groups/{id}/members/{userId}") @Transactional
    public void removeMember(Authentication actor,@PathVariable long id,@PathVariable long userId) {
        access.require(access.global(actor));
        if(jdbc.queryForObject("SELECT COUNT(*) FROM work_task WHERE group_id=? AND assignee_id=? AND status <> 'DONE'",Long.class,id,userId)>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"请先完成该成员在本组的未完成任务");
        jdbc.update("DELETE FROM group_member WHERE group_id=? AND user_id=?",id,userId);
        access.audit(actor,"GROUP_MEMBER_REMOVED",id+":"+userId);
    }
    @GetMapping("/audit")
    public List<Map<String,Object>> audit(Authentication actor) {
        access.require(access.global(actor));
        return jdbc.queryForList("SELECT id,actor,action,target,created_at FROM audit_event ORDER BY id DESC LIMIT 200");
    }
}
