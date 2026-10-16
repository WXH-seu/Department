package edu.seu.department.auth;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberAccountImportService {
    private static final Pattern USERNAME = Pattern.compile("[a-zA-Z0-9_.-]{3,64}");
    private static final Map<String, String> ROLES = Map.of(
        "负责人", "MINISTER", "核心骨干", "LEADER", "志愿者", "MEMBER");
    private static final char[] PASSWORD_CHARS =
        "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%".toCharArray();

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final SecureRandom random = new SecureRandom();

    public MemberAccountImportService(JdbcTemplate jdbc, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.passwords = passwords;
    }

    public record ImportResult(int total, int created, int updated, Path credentialsFile) {}
    private record Member(String username, String displayName, String role) {}
    private record Credential(String displayName, String username, String password, String role) {}

    @Transactional
    public ImportResult importWorkbook(Path workbook, Path credentialsFile) throws IOException {
        if (!Files.isRegularFile(workbook)) throw new IllegalArgumentException("成员信息表不存在: " + workbook);
        if (Files.exists(credentialsFile)) throw new IllegalArgumentException("凭据文件已存在，请先妥善保管或更换输出路径: " + credentialsFile);
        List<Member> members = readMembers(workbook);
        List<Credential> credentials = new ArrayList<>();
        int[] counts = importMembers(members, credentials);
        writeCredentials(credentialsFile, credentials);
        return new ImportResult(members.size(), counts[0], counts[1], credentialsFile);
    }

    private int[] importMembers(List<Member> members, List<Credential> credentials) {
        int created = 0;
        int updated = 0;
        for (Member member : members) {
            Long userId = findUserId(member.username());
            if (userId == null) {
                String password = newPassword();
                jdbc.update("INSERT INTO app_user (username,password_hash,display_name) VALUES (?,?,?)",
                    member.username(), passwords.encode(password), member.displayName());
                userId = jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, member.username());
                jdbc.update("INSERT INTO user_role(user_id,role_code) VALUES (?,?)", userId, member.role());
                credentials.add(new Credential(member.displayName(), member.username(), password, member.role()));
                created++;
            } else {
                long adminRoles = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM user_role WHERE user_id=? AND role_code='ADMIN'", Long.class, userId);
                if (adminRoles > 0) throw new IllegalStateException("成员账号不能覆盖系统管理员: " + member.username());
                jdbc.update("UPDATE app_user SET display_name=?, enabled=TRUE, session_version=session_version+1 WHERE id=?",
                    member.displayName(), userId);
                jdbc.update("DELETE FROM user_role WHERE user_id=?", userId);
                jdbc.update("INSERT INTO user_role(user_id,role_code) VALUES (?,?)", userId, member.role());
                updated++;
            }
            jdbc.update("INSERT INTO audit_event(actor,action,target) VALUES ('system-import','MEMBER_ACCOUNT_SYNC',?)",
                member.username() + ":" + member.role());
        }
        return new int[]{created, updated};
    }

    private Long findUserId(String username) {
        try {
            return jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, username);
        } catch (EmptyResultDataAccessException ignored) {
            return null;
        }
    }

    private List<Member> readMembers(Path workbook) throws IOException {
        try (InputStream input = Files.newInputStream(workbook); var book = WorkbookFactory.create(input)) {
            Sheet sheet = book.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            int header = findHeader(sheet, formatter);
            List<Member> members = new ArrayList<>();
            Set<String> usernames = new HashSet<>();
            for (int rowIndex = header + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) continue;
                String studentId = cell(row, 0, formatter).replaceAll("\\s+", "");
                String name = cell(row, 2, formatter);
                String position = cell(row, 4, formatter);
                if (studentId.isBlank() && name.isBlank() && position.isBlank()) continue;
                if (name.isBlank() && position.isBlank() && !USERNAME.matcher(studentId).matches()) continue; // 表尾说明
                if (studentId.isBlank() || name.isBlank() || position.isBlank())
                    throw new IllegalArgumentException("成员表第 " + (rowIndex + 1) + " 行缺少学号、姓名或职务");
                if (!USERNAME.matcher(studentId).matches())
                    throw new IllegalArgumentException("成员表第 " + (rowIndex + 1) + " 行学号不能作为账号");
                String role = ROLES.get(position);
                if (role == null) throw new IllegalArgumentException("成员表第 " + (rowIndex + 1) + " 行职务无法映射权限: " + position);
                if (!usernames.add(studentId)) throw new IllegalArgumentException("成员表存在重复学号，位置在第 " + (rowIndex + 1) + " 行");
                members.add(new Member(studentId, name, role));
            }
            if (members.isEmpty()) throw new IllegalArgumentException("成员表中没有可导入的完整记录");
            return members;
        }
    }

    private int findHeader(Sheet sheet, DataFormatter formatter) {
        for (int i = sheet.getFirstRowNum(); i <= Math.min(sheet.getLastRowNum(), 20); i++) {
            Row row = sheet.getRow(i);
            if (row != null && "学号".equals(cell(row, 0, formatter)) && "姓名".equals(cell(row, 2, formatter))
                    && "职务".equals(cell(row, 4, formatter))) return i;
        }
        throw new IllegalArgumentException("未找到包含学号、姓名和职务的表头");
    }

    private String cell(Row row, int index, DataFormatter formatter) {
        var cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    private String newPassword() {
        StringBuilder value = new StringBuilder(18);
        for (int i = 0; i < 18; i++) value.append(PASSWORD_CHARS[random.nextInt(PASSWORD_CHARS.length)]);
        return value.toString();
    }

    private void writeCredentials(Path destination, List<Credential> credentials) throws IOException {
        Path parent = destination.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".member-credentials-", ".tmp");
        try {
            StringBuilder content = new StringBuilder("服务部系统新账号初始化凭据\r\n")
                .append("请仅发给本人，并提醒首次登录后立即修改密码。\r\n\r\n")
                .append("姓名\t账号\t初始密码\t权限\r\n");
            for (Credential item : credentials) content.append(item.displayName()).append('\t')
                .append(item.username()).append('\t').append(item.password()).append('\t')
                .append(item.role()).append("\r\n");
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
