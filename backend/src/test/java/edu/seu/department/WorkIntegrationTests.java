package edu.seu.department;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WorkIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    long admin,member,outsider,group,task;
    @BeforeEach void setup() {
        // Each test rolls back its fixtures and all endpoint writes.
        admin=account("work-admin","ADMIN"); member=account("work-member","MEMBER"); outsider=account("work-outsider","MEMBER");
        jdbc.update("INSERT INTO department_group(name) VALUES ('Test group')");
        group=jdbc.queryForObject("SELECT id FROM department_group WHERE name='Test group'",Long.class);
        jdbc.update("INSERT INTO group_member(group_id,user_id) VALUES (?,?)",group,member);
        jdbc.update("INSERT INTO work_task(title,description,group_id,assignee_id,creator_id) VALUES ('Test','',?,?,?)",group,member,admin);
        task=jdbc.queryForObject("SELECT id FROM work_task WHERE title='Test'",Long.class);
    }
    long account(String name,String role) {
        jdbc.update("INSERT INTO app_user(username,password_hash,display_name) VALUES (?,'unused',?)",name,name);
        long id=jdbc.queryForObject("SELECT id FROM app_user WHERE username=?",Long.class,name);
        jdbc.update("INSERT INTO user_role(user_id,role_code) VALUES (?,?)",id,role); return id;
    }
    @Test void taskLifecycleAndOptimisticConflict() throws Exception {
        mvc.perform(patch("/api/v1/tasks/"+task).with(user("work-member").roles("MEMBER")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"PROGRESS\",\"progress\":40,\"version\":0}")) .andExpect(status().isOk());
        mvc.perform(patch("/api/v1/tasks/"+task).with(user("work-member").roles("MEMBER")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"SUBMIT\",\"progress\":100,\"version\":0}")) .andExpect(status().isConflict());
        mvc.perform(patch("/api/v1/tasks/"+task).with(user("work-member").roles("MEMBER")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"SUBMIT\",\"progress\":100,\"version\":1}")) .andExpect(status().isOk());
        mvc.perform(patch("/api/v1/tasks/"+task).with(user("work-member").roles("MEMBER")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"APPROVE\",\"progress\":100,\"version\":2}")) .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/tasks/"+task).with(user("work-admin").roles("ADMIN")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"APPROVE\",\"progress\":100,\"version\":2}")) .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM work_task WHERE id=?",String.class,task)).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE actor LIKE 'work-%'",Integer.class)).isEqualTo(3);
    }
    @Test void outsidersCannotReadCommentsOrChangeTasks() throws Exception {
        mvc.perform(get("/api/v1/tasks").with(user("work-outsider").roles("MEMBER"))).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/v1/tasks/"+task+"/comments").with(user("work-outsider").roles("MEMBER"))).andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/tasks/"+task).with(user("work-outsider").roles("MEMBER")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"PROGRESS\",\"progress\":1,\"version\":0}")) .andExpect(status().isForbidden());
    }
    @Test void membersCannotCreateAccountsAndAdminsCannotDisableThemselves() throws Exception {
        mvc.perform(post("/api/v1/users").with(user("work-member").roles("MEMBER")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"new-user\",\"password\":\"long-password-test\",\"displayName\":\"Test\",\"role\":\"ADMIN\"}")) .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/users/"+admin+"/enabled").with(user("work-admin").roles("ADMIN")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")) .andExpect(status().isForbidden());
    }
    @Test void cannotAssignTaskOutsideGroup() throws Exception {
        mvc.perform(post("/api/v1/tasks").with(user("work-admin").roles("ADMIN")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Work\",\"description\":\"\",\"groupId\":"+group+",\"assigneeId\":"+outsider+"}")) .andExpect(status().isBadRequest());
    }
    @Test void leaderHasScopeOnlyForManagedGroups() throws Exception {
        long leader=account("work-leader","LEADER");
        jdbc.update("INSERT INTO group_member(group_id,user_id,is_leader) VALUES (?,?,TRUE)",group,leader);
        mvc.perform(get("/api/v1/tasks").with(user("work-leader").roles("LEADER")))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(task));
        jdbc.update("UPDATE group_member SET is_leader=FALSE WHERE user_id=?",leader);
        mvc.perform(get("/api/v1/tasks/"+task+"/comments").with(user("work-leader").roles("LEADER"))).andExpect(status().isForbidden());
    }
    @Test void guestCannotUpdateAssignedTask() throws Exception {
        long guest=account("work-guest","GUEST");
        jdbc.update("UPDATE work_task SET assignee_id=? WHERE id=?",guest,task);
        mvc.perform(patch("/api/v1/tasks/"+task).with(user("work-guest").roles("GUEST")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"SUBMIT\",\"progress\":100,\"version\":0}")) .andExpect(status().isForbidden());
    }
    @Test void notificationReadIsOwnerScoped() throws Exception {
        jdbc.update("INSERT INTO notification(user_id,task_id,message) VALUES (?,?,'test')",member,task);
        long notice=jdbc.queryForObject("SELECT id FROM notification WHERE user_id=? AND task_id=?",Long.class,member,task);
        mvc.perform(post("/api/v1/notifications/"+notice+"/read").with(user("work-outsider").roles("MEMBER")).with(csrf())).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT is_read FROM notification WHERE id=?",Boolean.class,notice)).isFalse();
        mvc.perform(post("/api/v1/notifications/"+notice+"/read").with(user("work-member").roles("MEMBER")).with(csrf())).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT is_read FROM notification WHERE id=?",Boolean.class,notice)).isTrue();
    }
    @Test void attachmentsEnforceTaskScopeAndRejectTraversalNames() throws Exception {
        var file=new org.springframework.mock.web.MockMultipartFile("file","../outside.txt","text/plain","test".getBytes());
        mvc.perform(multipart("/api/v1/tasks/"+task+"/attachments").file(file).with(user("work-member").roles("MEMBER")).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/tasks/"+task+"/attachments").file(file).with(user("work-outsider").roles("MEMBER")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/tasks/"+task+"/attachments/1").with(user("work-outsider").roles("MEMBER"))).andExpect(status().isForbidden());
    }
    @Test void attachmentRoundTripKeepsOriginalNameAndOpaqueStorage() throws Exception {
        var file=new org.springframework.mock.web.MockMultipartFile("file","notes.txt","text/plain","test attachment".getBytes());
        mvc.perform(multipart("/api/v1/tasks/"+task+"/attachments").file(file).with(user("work-member").roles("MEMBER")).with(csrf())).andExpect(status().isCreated());
        var row=jdbc.queryForMap("SELECT id,storage_key FROM task_attachment WHERE task_id=?",task);
        java.nio.file.Path path=java.nio.file.Path.of("data/uploads").toAbsolutePath().resolve((String)row.get("storage_key")).normalize();
        try {
            assertThat(path.getFileName().toString()).doesNotContain("notes");
            mvc.perform(get("/api/v1/tasks/"+task+"/attachments/"+row.get("id")).with(user("work-member").roles("MEMBER")))
                .andExpect(status().isOk()).andExpect(content().bytes("test attachment".getBytes()))
                .andExpect(header().string("Content-Type","application/octet-stream"));
        } finally {
            if(path.startsWith(java.nio.file.Path.of("data/uploads").toAbsolutePath().normalize())) java.nio.file.Files.deleteIfExists(path);
        }
    }
}
