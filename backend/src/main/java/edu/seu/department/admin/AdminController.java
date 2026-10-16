package edu.seu.department.admin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import edu.seu.department.work.WorkAccess;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAnyRole('ADMIN','MINISTER')")
public class AdminController {
    private static final Set<String> ROLES = Set.of("ADMIN","MINISTER","LEADER","MEMBER","GUEST");
    private static final Set<String> STATUSES = Set.of("TODO","IN_PROGRESS","REVIEW","DONE");
    private static final char[] PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%".toCharArray();
    private final JdbcTemplate jdbc;
    private final WorkAccess access;
    private final PasswordEncoder passwords;
    private final SecureRandom random = new SecureRandom();
    private final Path storage;
    private final String version;

    public AdminController(JdbcTemplate jdbc, WorkAccess access, PasswordEncoder passwords,
            @Value("${department.storage.upload-directory:./data/uploads}") String storageDirectory,
            ObjectProvider<BuildProperties> buildProvider) {
        this.jdbc=jdbc; this.access=access; this.passwords=passwords;
        this.storage=Path.of(storageDirectory).toAbsolutePath().normalize();
        BuildProperties build=buildProvider.getIfAvailable();
        this.version=build == null ? "development" : build.getVersion();
    }

    public record Page<T>(List<T> items,int page,int size,long total) {}
    private int page(Integer value) { return value == null ? 1 : Math.max(1,value); }
    private int size(Integer value) { return value == null ? 20 : Math.min(100,Math.max(1,value)); }
    private boolean admin(Authentication actor) { return access.role(actor,"ADMIN"); }
    private void adminOnly(Authentication actor) { access.require(admin(actor)); }
    private long count(String sql,Object... args) { return jdbc.queryForObject(sql,Long.class,args); }
    private List<Map<String,Object>> rows(String sql,Object... args) {
        return jdbc.queryForList(sql,args).stream().map(row->{
            var normalized=new LinkedHashMap<String,Object>();
            row.forEach((key,value)->normalized.put(key.toLowerCase(),value));
            return (Map<String,Object>)normalized;
        }).toList();
    }

    @GetMapping("/overview")
    public Map<String,Object> overview() {
        var result=new LinkedHashMap<String,Object>();
        result.put("accounts",Map.of("total",count("SELECT COUNT(*) FROM app_user"),"enabled",count("SELECT COUNT(*) FROM app_user WHERE enabled=TRUE"),"disabled",count("SELECT COUNT(*) FROM app_user WHERE enabled=FALSE")));
        result.put("roles",rows("SELECT r.code,r.name,COUNT(ur.user_id) AS count FROM app_role r LEFT JOIN user_role ur ON ur.role_code=r.code GROUP BY r.code,r.name ORDER BY r.code"));
        result.put("groups",Map.of("total",count("SELECT COUNT(*) FROM department_group"),"ungrouped",count("SELECT COUNT(*) FROM app_user u WHERE NOT EXISTS (SELECT 1 FROM group_member gm WHERE gm.user_id=u.id)"),"withoutLeader",count("SELECT COUNT(*) FROM department_group g WHERE NOT EXISTS (SELECT 1 FROM group_member gm WHERE gm.group_id=g.id AND gm.is_leader=TRUE)")));
        result.put("tasks",Map.of("total",count("SELECT COUNT(*) FROM work_task"),"active",count("SELECT COUNT(*) FROM work_task WHERE status IN ('TODO','IN_PROGRESS')"),"review",count("SELECT COUNT(*) FROM work_task WHERE status='REVIEW'"),"overdue",count("SELECT COUNT(*) FROM work_task WHERE status<>'DONE' AND due_date<CURRENT_DATE"),"recentDone",count("SELECT COUNT(*) FROM task_status_event WHERE to_status='DONE' AND created_at>=?",java.time.LocalDateTime.now().minusDays(7))));
        result.put("alerts",rows("SELECT id,actor,action,target,created_at FROM audit_event WHERE action IN ('LOGIN_FAILED','ROLE_CHANGED_ADMIN','ROLE_CHANGED_MINISTER','USER_ENABLED_false') ORDER BY id DESC LIMIT 8"));
        result.put("refreshedAt",java.time.OffsetDateTime.now());
        return result;
    }

    @GetMapping("/users")
    public Page<Map<String,Object>> users(@RequestParam(required=false) String q,@RequestParam(required=false) String role,
            @RequestParam(required=false) Boolean enabled,@RequestParam(required=false) Integer page,@RequestParam(required=false) Integer size) {
        if(role!=null && !role.isBlank() && !ROLES.contains(role)) throw bad("角色无效");
        var where=new StringBuilder(" WHERE 1=1"); var args=new ArrayList<Object>();
        if(q!=null && !q.isBlank()){ where.append(" AND (LOWER(u.username) LIKE ? OR LOWER(u.display_name) LIKE ?)"); String like="%"+q.trim().toLowerCase()+"%";args.add(like);args.add(like); }
        if(role!=null && !role.isBlank()){where.append(" AND ur.role_code=?");args.add(role);}
        if(enabled!=null){where.append(" AND u.enabled=?");args.add(enabled);}
        int p=page(page),s=size(size); long total=count("SELECT COUNT(*) FROM app_user u JOIN user_role ur ON ur.user_id=u.id"+where,args.toArray());
        var queryArgs=new ArrayList<>(args);queryArgs.add(s);queryArgs.add((p-1)*s);
        String sql="SELECT u.id,u.username,u.display_name,u.enabled,u.must_change_password,u.last_login_at,ur.role_code AS role,"+
            "(SELECT COUNT(*) FROM group_member gm WHERE gm.user_id=u.id) AS group_count FROM app_user u JOIN user_role ur ON ur.user_id=u.id"+where+" ORDER BY u.id LIMIT ? OFFSET ?";
        return new Page<>(rows(sql,queryArgs.toArray()),p,s,total);
    }

    public record NewUser(@NotBlank @Pattern(regexp="[a-zA-Z0-9_.-]{3,64}") String username,@NotBlank @Size(min=12,max=72) String password,@NotBlank @Size(max=100) String displayName,@NotBlank String role) {}
    @PostMapping("/users") @ResponseStatus(HttpStatus.CREATED) @Transactional
    public Map<String,Long> createUser(Authentication actor,@Valid @RequestBody NewUser input) {
        if(!ROLES.contains(input.role())) throw bad("角色无效");
        if(!admin(actor) && Set.of("ADMIN","MINISTER").contains(input.role())) throw forbidden();
        var key=new GeneratedKeyHolder();
        jdbc.update(connection->{var statement=connection.prepareStatement("INSERT INTO app_user(username,password_hash,display_name,must_change_password) VALUES (?,?,?,TRUE)",new String[]{"id"});statement.setString(1,input.username());statement.setString(2,passwords.encode(input.password()));statement.setString(3,input.displayName().trim());return statement;},key);
        long id=key.getKey().longValue();jdbc.update("INSERT INTO user_role(user_id,role_code) VALUES (?,?)",id,input.role());access.audit(actor,"USER_CREATED",id);return Map.of("id",id);
    }

    public record UserPatch(@Size(max=100) String displayName,String role,Boolean enabled) {}
    @PatchMapping("/users/{id}") @Transactional
    public void updateUser(Authentication actor,@PathVariable long id,@Valid @RequestBody UserPatch input) {
        var rows=rows("SELECT u.username,u.enabled,ur.role_code role FROM app_user u JOIN user_role ur ON ur.user_id=u.id WHERE u.id=? FOR UPDATE",id);
        if(rows.isEmpty()) throw notFound("账号不存在");
        var target=rows.getFirst(); boolean self=id==access.id(actor); String targetRole=(String)target.get("role");
        if(self && (input.enabled()!=null || input.role()!=null)) throw forbidden();
        if(!admin(actor) && (input.role()!=null || Set.of("ADMIN","MINISTER").contains(targetRole))) throw forbidden();
        if(input.displayName()!=null){if(input.displayName().isBlank()) throw bad("姓名不能为空");jdbc.update("UPDATE app_user SET display_name=? WHERE id=?",input.displayName().trim(),id);access.audit(actor,"PROFILE_UPDATED",id);}
        if(input.enabled()!=null && input.enabled()!=(Boolean)target.get("enabled")){jdbc.update("UPDATE app_user SET enabled=?,session_version=session_version+1 WHERE id=?",input.enabled(),id);access.audit(actor,"USER_ENABLED_"+input.enabled(),id);}
        if(input.role()!=null && !input.role().equals(targetRole)){adminOnly(actor);if(!ROLES.contains(input.role())) throw bad("角色无效");jdbc.update("DELETE FROM user_role WHERE user_id=?",id);jdbc.update("INSERT INTO user_role(user_id,role_code) VALUES (?,?)",id,input.role());jdbc.update("UPDATE app_user SET session_version=session_version+1 WHERE id=?",id);access.audit(actor,"ROLE_CHANGED_"+input.role(),id);}
    }

    @PostMapping("/users/{id}/reset-password") @Transactional
    public Map<String,String> resetPassword(Authentication actor,@PathVariable long id) {
        adminOnly(actor);if(id==access.id(actor)) throw forbidden();
        if(count("SELECT COUNT(*) FROM app_user WHERE id=?",id)==0) throw notFound("账号不存在");
        String temporary=randomPassword();jdbc.update("UPDATE app_user SET password_hash=?,must_change_password=TRUE,session_version=session_version+1 WHERE id=?",passwords.encode(temporary),id);access.audit(actor,"PASSWORD_RESET",id);return Map.of("temporaryPassword",temporary);
    }

    @GetMapping("/groups")
    public List<Map<String,Object>> groups() {
        return rows("SELECT g.id,g.name,(SELECT COUNT(*) FROM group_member gm WHERE gm.group_id=g.id) member_count,(SELECT COUNT(*) FROM group_member gm WHERE gm.group_id=g.id AND gm.is_leader=TRUE) leader_count,(SELECT COUNT(*) FROM work_task t WHERE t.group_id=g.id AND t.status<>'DONE') open_task_count FROM department_group g ORDER BY g.id");
    }
    @GetMapping("/groups/{id}")
    public Map<String,Object> group(@PathVariable long id) {
        var rows=rows("SELECT id,name,created_at FROM department_group WHERE id=?",id);if(rows.isEmpty()) throw notFound("小组不存在");
        var result=new LinkedHashMap<String,Object>(rows.getFirst());result.put("members",rows("SELECT u.id,u.username,u.display_name,u.enabled,ur.role_code role,gm.position_name,gm.is_leader FROM group_member gm JOIN app_user u ON u.id=gm.user_id JOIN user_role ur ON ur.user_id=u.id WHERE gm.group_id=? ORDER BY gm.is_leader DESC,u.id",id));return result;
    }
    public record GroupMember(@Size(max=100) String position,@NotNull Boolean leader) {}
    @PutMapping("/groups/{id}/members/{userId}") @Transactional
    public void setMember(Authentication actor,@PathVariable long id,@PathVariable long userId,@Valid @RequestBody GroupMember input) {
        if(input.leader() && count("SELECT COUNT(*) FROM user_role WHERE user_id=? AND role_code='LEADER'",userId)==0) throw bad("担任负责人前请先把账号角色设为组长");
        if(count("SELECT COUNT(*) FROM department_group WHERE id=?",id)==0 || count("SELECT COUNT(*) FROM app_user WHERE id=?",userId)==0) throw notFound("小组或账号不存在");
        jdbc.update("DELETE FROM group_member WHERE group_id=? AND user_id=?",id,userId);jdbc.update("INSERT INTO group_member(group_id,user_id,position_name,is_leader) VALUES (?,?,?,?)",id,userId,input.position(),input.leader());jdbc.update("UPDATE app_user SET session_version=session_version+1 WHERE id=?",userId);access.audit(actor,"GROUP_MEMBER_SET",id+":"+userId);
    }

    @GetMapping("/tasks")
    public Page<Map<String,Object>> tasks(@RequestParam(required=false) String q,@RequestParam(required=false) Long groupId,@RequestParam(required=false) Long assigneeId,@RequestParam(required=false) String status,@RequestParam(required=false) LocalDate dueFrom,@RequestParam(required=false) LocalDate dueTo,@RequestParam(required=false) Boolean overdue,@RequestParam(required=false) Integer page,@RequestParam(required=false) Integer size) {
        if(status!=null&&!status.isBlank()&&!STATUSES.contains(status)) throw bad("状态无效");var where=new StringBuilder(" WHERE 1=1");var args=new ArrayList<Object>();
        if(q!=null&&!q.isBlank()){where.append(" AND (LOWER(t.title) LIKE ? OR LOWER(t.description) LIKE ?)");String like="%"+q.trim().toLowerCase()+"%";args.add(like);args.add(like);}if(groupId!=null){where.append(" AND t.group_id=?");args.add(groupId);}if(assigneeId!=null){where.append(" AND t.assignee_id=?");args.add(assigneeId);}if(status!=null&&!status.isBlank()){where.append(" AND t.status=?");args.add(status);}if(dueFrom!=null){where.append(" AND t.due_date>=?");args.add(dueFrom);}if(dueTo!=null){where.append(" AND t.due_date<=?");args.add(dueTo);}if(Boolean.TRUE.equals(overdue))where.append(" AND t.status<>'DONE' AND t.due_date<CURRENT_DATE");
        int p=page(page),s=size(size);long total=count("SELECT COUNT(*) FROM work_task t"+where,args.toArray());var qa=new ArrayList<>(args);qa.add(s);qa.add((p-1)*s);
        return new Page<>(rows("SELECT t.id,t.title,t.status,t.progress,t.due_date,t.version,t.created_at,g.name group_name,u.display_name assignee_name,c.display_name creator_name FROM work_task t JOIN department_group g ON g.id=t.group_id JOIN app_user u ON u.id=t.assignee_id JOIN app_user c ON c.id=t.creator_id"+where+" ORDER BY t.id DESC LIMIT ? OFFSET ?",qa.toArray()),p,s,total);
    }
    @GetMapping("/tasks/{id}")
    public Map<String,Object> task(@PathVariable long id) {
        var rows=rows("SELECT t.*,g.name group_name,u.display_name assignee_name,c.display_name creator_name FROM work_task t JOIN department_group g ON g.id=t.group_id JOIN app_user u ON u.id=t.assignee_id JOIN app_user c ON c.id=t.creator_id WHERE t.id=?",id);if(rows.isEmpty()) throw notFound("任务不存在");
        var result=new LinkedHashMap<String,Object>(rows.getFirst());result.put("comments",rows("SELECT c.id,c.body,c.created_at,u.display_name FROM task_comment c JOIN app_user u ON u.id=c.author_id WHERE c.task_id=? ORDER BY c.id",id));result.put("attachments",rows("SELECT id,original_name,size_bytes,created_at FROM task_attachment WHERE task_id=? ORDER BY id",id));result.put("events",rows("SELECT id,actor,action,from_status,to_status,progress,created_at FROM task_status_event WHERE task_id=? ORDER BY id",id));return result;
    }
    public record Assignee(@Positive long assigneeId,@Min(0) int version) {}
    @PatchMapping("/tasks/{id}/assignee") @Transactional
    public void assign(Authentication actor,@PathVariable long id,@Valid @RequestBody Assignee input) {
        var rows=rows("SELECT group_id,title,assignee_id,status,progress FROM work_task WHERE id=?",id);if(rows.isEmpty()) throw notFound("任务不存在");var task=rows.getFirst();long group=((Number)task.get("group_id")).longValue();
        if(count("SELECT COUNT(*) FROM group_member gm JOIN app_user u ON u.id=gm.user_id WHERE gm.group_id=? AND gm.user_id=? AND u.enabled=TRUE",group,input.assigneeId())==0) throw bad("负责人必须是该组的启用成员");
        if(jdbc.update("UPDATE work_task SET assignee_id=?,version=version+1 WHERE id=? AND version=?",input.assigneeId(),id,input.version())==0) throw new ResponseStatusException(HttpStatus.CONFLICT,"任务已被更新，请刷新后重试");access.audit(actor,"TASK_REASSIGNED",id);jdbc.update("INSERT INTO notification(user_id,task_id,message) VALUES (?,?,?)",input.assigneeId(),id,"你被指定为任务负责人："+task.get("title"));
    }

    @GetMapping("/audit")
    public Page<Map<String,Object>> audit(@RequestParam(required=false) String actor,@RequestParam(required=false) String action,@RequestParam(required=false) String target,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,@RequestParam(required=false) Integer page,@RequestParam(required=false) Integer size) {
        var where=new StringBuilder(" WHERE 1=1");var args=new ArrayList<Object>();if(actor!=null&&!actor.isBlank()){where.append(" AND LOWER(actor) LIKE ?");args.add("%"+actor.trim().toLowerCase()+"%");}if(action!=null&&!action.isBlank()){where.append(" AND action=?");args.add(action);}if(target!=null&&!target.isBlank()){where.append(" AND target LIKE ?");args.add("%"+target.trim()+"%");}if(from!=null){where.append(" AND created_at>=?");args.add(from.atStartOfDay());}if(to!=null){where.append(" AND created_at<?");args.add(to.plusDays(1).atStartOfDay());}int p=page(page),s=size(size);long total=count("SELECT COUNT(*) FROM audit_event"+where,args.toArray());var qa=new ArrayList<>(args);qa.add(s);qa.add((p-1)*s);return new Page<>(rows("SELECT id,actor,action,target,created_at FROM audit_event"+where+" ORDER BY id DESC LIMIT ? OFFSET ?",qa.toArray()),p,s,total);
    }

    @GetMapping("/system")
    public Map<String,Object> system() {
        boolean database=count("SELECT 1")==1;boolean writable=false;try{Files.createDirectories(storage);writable=Files.isWritable(storage);}catch(Exception ignored){}
        return Map.of("application","ok","database",database?"ok":"error","storage",writable?"ok":"error","version",version,"checkedAt",java.time.OffsetDateTime.now());
    }

    private String randomPassword(){var value=new StringBuilder(18);for(int i=0;i<18;i++)value.append(PASSWORD_CHARS[random.nextInt(PASSWORD_CHARS.length)]);return value.toString();}
    private ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private ResponseStatusException forbidden(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"没有此操作的权限");}
    private ResponseStatusException notFound(String message){return new ResponseStatusException(HttpStatus.NOT_FOUND,message);}
}
