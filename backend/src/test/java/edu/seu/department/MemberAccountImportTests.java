package edu.seu.department;

import static org.assertj.core.api.Assertions.assertThat;

import edu.seu.department.auth.MemberAccountImportService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest
class MemberAccountImportTests {
    @Autowired MemberAccountImportService importer;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @TempDir Path temporary;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM group_member");
        jdbc.update("DELETE FROM app_user");
    }

    @Test
    void importsAndSynchronizesAccountsWithoutKeepingPersonalContactFields() throws Exception {
        Path workbook = temporary.resolve("members.xlsx");
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("Sheet1");
            sheet.createRow(0).createCell(0).setCellValue("成员名单");
            var header = sheet.createRow(1);
            String[] headers = {"学号", "一卡通", "姓名", "手机号", "职务"};
            for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);
            add(sheet, 2, "10000001", "card-a", "负责人甲", "13000000001", "负责人");
            add(sheet, 3, "20000E 01", "card-b", "骨干乙", "13000000002", "核心骨干");
            add(sheet, 4, "30000001", "card-c", "志愿者丙", "13000000003", "志愿者");
            add(sheet, 5, "40000001", "", "志愿者丁", "", "志愿者");
            sheet.createRow(6).createCell(0).setCellValue("以下为空");
            try (var output = Files.newOutputStream(workbook)) { book.write(output); }
        }

        Path credentials = temporary.resolve("credentials.txt");
        var first = importer.importWorkbook(workbook, credentials);
        assertThat(first.total()).isEqualTo(4);
        assertThat(first.created()).isEqualTo(4);
        assertThat(first.updated()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForList("SELECT role_code FROM user_role ORDER BY role_code", String.class))
            .containsExactly("LEADER", "MEMBER", "MEMBER", "MINISTER");
        assertThat(jdbc.queryForObject("SELECT username FROM app_user WHERE display_name='骨干乙'", String.class))
            .isEqualTo("20000E01");
        String content = Files.readString(credentials);
        assertThat(content).contains("负责人甲\t10000001\t").doesNotContain("13000000001", "card-a");
        String generated = content.lines().filter(line -> line.startsWith("负责人甲\t"))
            .findFirst().orElseThrow().split("\\t")[2];
        assertThat(generated).hasSize(18);
        assertThat(passwords.matches(generated,
            jdbc.queryForObject("SELECT password_hash FROM app_user WHERE username='10000001'", String.class))).isTrue();

        var second = importer.importWorkbook(workbook, temporary.resolve("credentials-2.txt"));
        assertThat(second.created()).isZero();
        assertThat(second.updated()).isEqualTo(4);
        assertThat(Files.readString(second.credentialsFile())).doesNotContain("10000001");
    }

    private void add(org.apache.poi.ss.usermodel.Sheet sheet, int index, String... values) {
        var row = sheet.createRow(index);
        for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
    }
}
