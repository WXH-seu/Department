package edu.seu.department.work;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/tasks/{taskId}/attachments")
public class AttachmentController {
    private final JdbcTemplate jdbc;
    private final TaskController tasks;
    private final WorkAccess access;
    private final TransactionTemplate transaction;
    private final Path root;
    public AttachmentController(JdbcTemplate jdbc,TaskController tasks,WorkAccess access,TransactionTemplate transaction,
        @Value("${department.storage.upload-directory:./data/uploads}") String directory) {
        this.jdbc=jdbc;this.tasks=tasks;this.access=access;this.transaction=transaction;
        this.root=Path.of(directory).toAbsolutePath().normalize();
    }
    @GetMapping public List<Map<String,Object>> list(Authentication actor,@PathVariable long taskId) {
        tasks.visible(actor,taskId);
        return jdbc.queryForList("SELECT id,original_name,size_bytes,created_at FROM task_attachment WHERE task_id=? ORDER BY id",taskId);
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public void upload(Authentication actor,@PathVariable long taskId,@RequestParam("file") MultipartFile file) throws IOException {
        tasks.visible(actor,taskId); access.require(!access.role(actor,"GUEST"));
        if(file.isEmpty() || file.getSize()>10*1024*1024) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"附件大小应为 1 字节至 10 MB");
        String name=file.getOriginalFilename();
        if(name==null || name.isBlank() || name.length()>255 || name.contains("/") || name.contains("\\") || name.chars().anyMatch(Character::isISOControl))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"附件文件名不合法");
        Files.createDirectories(root);
        String key=UUID.randomUUID().toString(); Path destination=root.resolve(key);
        try {
            file.transferTo(destination);
            transaction.executeWithoutResult(status->{
                jdbc.update("INSERT INTO task_attachment(task_id,uploader_id,storage_key,original_name,size_bytes) VALUES (?,?,?,?,?)",taskId,access.id(actor),key,name,file.getSize());
                access.audit(actor,"ATTACHMENT_UPLOADED",taskId);
            });
        } catch(RuntimeException | IOException failure) { Files.deleteIfExists(destination); throw failure; }
    }
    @GetMapping("/{id}") public ResponseEntity<FileSystemResource> download(Authentication actor,@PathVariable long taskId,@PathVariable long id) throws IOException {
        tasks.visible(actor,taskId);
        var rows=jdbc.queryForList("SELECT storage_key,original_name FROM task_attachment WHERE task_id=? AND id=?",taskId,id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"附件不存在");
        var row=rows.getFirst();
        Path path=root.resolve((String)row.get("storage_key")).normalize();
        if(!path.startsWith(root) || !Files.isRegularFile(path)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"附件文件不存在");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename((String)row.get("original_name"),StandardCharsets.UTF_8).build().toString())
            .contentLength(Files.size(path)).body(new FileSystemResource(path));
    }
}
