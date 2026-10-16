package edu.seu.department.work;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {
    private final JdbcTemplate jdbc;
    private final WorkAccess access;
    public TaskController(JdbcTemplate jdbc, WorkAccess access) { this.jdbc=jdbc; this.access=access; }

    public record Task(long id,String title,String description,long groupId,long assigneeId,long creatorId,
        String status,int progress,LocalDate dueDate,int version) {}
    private static final String SELECT = "SELECT * FROM work_task";
    private List<Task> query(String sql,Object... args) {
        return jdbc.query(sql,(rs,n)->new Task(rs.getLong("id"),rs.getString("title"),rs.getString("description"),
            rs.getLong("group_id"),rs.getLong("assignee_id"),rs.getLong("creator_id"),rs.getString("status"),
            rs.getInt("progress"),rs.getObject("due_date",LocalDate.class),rs.getInt("version")),args);
    }
    public Task visible(Authentication actor,long id) {
        var found=query(SELECT + " WHERE id=?",id);
        if(found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"任务不存在");
        var task=found.getFirst();
        access.require(task.assigneeId()==access.id(actor) || task.creatorId()==access.id(actor) || access.manages(actor,task.groupId()));
        return task;
    }
    @GetMapping
    public List<Task> list(Authentication actor) {
        if(access.global(actor)) return query(SELECT + " ORDER BY id DESC");
        long id=access.id(actor);
        return query(SELECT + " WHERE assignee_id=? OR creator_id=? OR (group_id IN (SELECT group_id FROM group_member WHERE user_id=? AND is_leader=TRUE) AND ?=TRUE) ORDER BY id DESC",id,id,id,access.role(actor,"LEADER"));
    }
    public record NewTask(@NotBlank @Size(max=200) String title,@NotNull @Size(max=10000) String description,
        @Positive long groupId,@Positive long assigneeId,LocalDate dueDate) {}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) @Transactional
    public Map<String,Long> create(Authentication actor,@Valid @RequestBody NewTask input) {
        access.require(access.manages(actor,input.groupId()));
        if(jdbc.queryForObject("SELECT COUNT(*) FROM group_member m JOIN app_user u ON u.id=m.user_id WHERE m.group_id=? AND m.user_id=? AND u.enabled=TRUE",Long.class,input.groupId(),input.assigneeId())==0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"负责人必须是该组的启用成员");
        var key=new GeneratedKeyHolder();
        jdbc.update(connection->{
            var statement=connection.prepareStatement("INSERT INTO work_task(title,description,group_id,assignee_id,creator_id,due_date) VALUES (?,?,?,?,?,?)",new String[]{"id"});
            statement.setString(1,input.title().trim()); statement.setString(2,input.description());
            statement.setLong(3,input.groupId()); statement.setLong(4,input.assigneeId());
            statement.setLong(5,access.id(actor)); statement.setObject(6,input.dueDate()); return statement;
        },key);
        long id=key.getKey().longValue(); access.audit(actor,"TASK_CREATED",id);
        jdbc.update("INSERT INTO task_status_event(task_id,actor,action,from_status,to_status,progress) VALUES (?,?,'CREATE',NULL,'TODO',0)",id,actor.getName());
        jdbc.update("INSERT INTO notification(user_id,task_id,message) VALUES (?,?,?)",input.assigneeId(),id,"你有新的任务："+input.title().trim());
        return Map.of("id",id);
    }
    public record Update(@NotBlank String action,@Min(0) @Max(100) int progress,@Min(0) int version) {}
    @PatchMapping("/{id}") @Transactional
    public void update(Authentication actor,@PathVariable long id,@Valid @RequestBody Update input) {
        access.require(!access.role(actor,"GUEST"));
        var task=visible(actor,id);
        String status=task.status(); int progress=task.progress();
        boolean manager=access.manages(actor,task.groupId());
        switch(input.action()) {
            case "PROGRESS" -> {
                access.require(task.assigneeId()==access.id(actor) || manager);
                if(!status.equals("TODO") && !status.equals("IN_PROGRESS")) invalidState();
                status="IN_PROGRESS"; progress=input.progress();
            }
            case "SUBMIT" -> {
                access.require(task.assigneeId()==access.id(actor) || manager);
                if(!status.equals("TODO") && !status.equals("IN_PROGRESS")) invalidState();
                status="REVIEW"; progress=100;
            }
            case "APPROVE", "RETURN" -> {
                access.require(manager);
                if(!status.equals("REVIEW")) invalidState();
                status=input.action().equals("APPROVE") ? "DONE" : "IN_PROGRESS";
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"不支持的任务操作");
        }
        if(jdbc.update("UPDATE work_task SET status=?,progress=?,version=version+1 WHERE id=? AND version=?",status,progress,id,input.version())==0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"任务已被更新，请刷新后重试");
        access.audit(actor,"TASK_" + input.action(),id);
        jdbc.update("INSERT INTO task_status_event(task_id,actor,action,from_status,to_status,progress) VALUES (?,?,?,?,?,?)",id,actor.getName(),input.action(),task.status(),status,progress);
        long recipient=input.action().equals("SUBMIT") ? task.creatorId() : task.assigneeId();
        if(recipient != access.id(actor)) jdbc.update("INSERT INTO notification(user_id,task_id,message) VALUES (?,?,?)",recipient,id,"任务状态已更新："+task.title());
    }
    private void invalidState() { throw new ResponseStatusException(HttpStatus.CONFLICT,"当前状态不允许此操作"); }
    @GetMapping("/{id}/comments")
    public List<Map<String,Object>> comments(Authentication actor,@PathVariable long id) {
        visible(actor,id);
        return jdbc.queryForList("SELECT c.id,c.body,c.created_at,u.display_name FROM task_comment c JOIN app_user u ON u.id=c.author_id WHERE c.task_id=? ORDER BY c.id",id);
    }
    public record Comment(@NotBlank @Size(max=2000) String body) {}
    @PostMapping("/{id}/comments") @ResponseStatus(HttpStatus.CREATED) @Transactional
    public void comment(Authentication actor,@PathVariable long id,@Valid @RequestBody Comment input) {
        visible(actor,id);
        access.require(!access.role(actor,"GUEST"));
        jdbc.update("INSERT INTO task_comment(task_id,author_id,body) VALUES (?,?,?)",id,access.id(actor),input.body().trim());
        access.audit(actor,"TASK_COMMENT",id);
    }
}
