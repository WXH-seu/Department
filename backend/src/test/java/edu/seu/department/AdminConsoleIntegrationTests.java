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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminConsoleIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    long admin,minister,member,group,task;

    @BeforeEach void setup() {
        admin=account("console-admin","ADMIN"); minister=account("console-minister","MINISTER"); member=account("console-member","MEMBER");
        jdbc.update("INSERT INTO department_group(name) VALUES ('Console group')");group=jdbc.queryForObject("SELECT id FROM department_group WHERE name='Console group'",Long.class);
        jdbc.update("INSERT INTO group_member(group_id,user_id) VALUES (?,?)",group,member);
        jdbc.update("INSERT INTO work_task(title,description,group_id,assignee_id,creator_id,due_date) VALUES ('Console task','details',?,?,?,CURRENT_DATE)",group,member,admin);
        task=jdbc.queryForObject("SELECT id FROM work_task WHERE title='Console task'",Long.class);
    }
    long account(String username,String role){jdbc.update("INSERT INTO app_user(username,password_hash,display_name) VALUES (?,?,?)",username,passwords.encode("test-password-123"),username);long id=jdbc.queryForObject("SELECT id FROM app_user WHERE username=?",Long.class,username);jdbc.update("INSERT INTO user_role(user_id,role_code) VALUES (?,?)",id,role);return id;}

    @Test void overviewAndPagedDirectoriesAreAvailableToManagers() throws Exception {
        mvc.perform(get("/api/v1/admin/overview").with(user("console-admin").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.accounts.total").value(greaterThanOrEqualTo(3))).andExpect(jsonPath("$.tasks.total").value(1));
        mvc.perform(get("/api/v1/admin/users").param("role","MEMBER").param("size","1").with(user("console-minister").roles("MINISTER")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].username").value("console-member"));
        mvc.perform(get("/api/v1/admin/tasks/"+task).with(user("console-admin").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Console task"));
    }

    @Test void ordinaryMembersCannotUseAdminEndpoints() throws Exception {
        mvc.perform(get("/api/v1/admin/users").with(user("console-member").roles("MEMBER"))).andExpect(status().isForbidden());
    }

    @Test void ministerCannotChangeRolesOrHighPrivilegeAccounts() throws Exception {
        mvc.perform(patch("/api/v1/admin/users/"+member).with(user("console-minister").roles("MINISTER")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"LEADER\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/admin/users/"+admin).with(user("console-minister").roles("MINISTER")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
            .andExpect(status().isForbidden());
    }

    @Test void passwordResetReturnsOnceAndRevokesSessions() throws Exception {
        long before=jdbc.queryForObject("SELECT session_version FROM app_user WHERE id=?",Long.class,member);
        var result=mvc.perform(post("/api/v1/admin/users/"+member+"/reset-password").with(user("console-admin").roles("ADMIN")).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.temporaryPassword").isString()).andReturn();
        String temporary=json.readTree(result.getResponse().getContentAsString()).get("temporaryPassword").asText();
        var row=jdbc.queryForMap("SELECT password_hash,must_change_password,session_version FROM app_user WHERE id=?",member);
        assertThat(passwords.matches(temporary,(String)row.get("password_hash"))).isTrue();
        assertThat(row.get("must_change_password")).isEqualTo(true);
        assertThat(((Number)row.get("session_version")).longValue()).isEqualTo(before+1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE action='PASSWORD_RESET' AND target=?",Long.class,String.valueOf(member))).isOne();
    }

    @Test void reassignmentUsesOptimisticVersionAndGroupScope() throws Exception {
        long outsider=account("console-outsider","MEMBER");
        mvc.perform(patch("/api/v1/admin/tasks/"+task+"/assignee").with(user("console-admin").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"assigneeId\":"+outsider+",\"version\":0}"))
            .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/admin/tasks/"+task+"/assignee").with(user("console-admin").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"assigneeId\":"+member+",\"version\":9}"))
            .andExpect(status().isConflict());
    }
}
