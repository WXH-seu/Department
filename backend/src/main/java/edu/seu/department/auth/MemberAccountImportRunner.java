package edu.seu.department.auth;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(20)
@ConditionalOnProperty(name = "department.member-import.file")
public class MemberAccountImportRunner implements ApplicationRunner {
    private final MemberAccountImportService importer;
    private final Path workbook;
    private final Path credentials;

    public MemberAccountImportRunner(MemberAccountImportService importer,
            @Value("${department.member-import.file}") String workbook,
            @Value("${department.member-import.credentials}") String credentials) {
        this.importer = importer;
        this.workbook = Path.of(workbook);
        this.credentials = Path.of(credentials);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        var result = importer.importWorkbook(workbook, credentials);
        System.out.printf("成员账号导入完成：共 %d，新增 %d，更新 %d。凭据已写入指定文件。%n",
            result.total(), result.created(), result.updated());
    }
}
