package kr.espresso.core.support;

import kr.espresso.common.ApiException;
import kr.espresso.common.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 요구 19: 모든 문서 공통 메모(댓글)·첨부·이력. 접근 권한은 문서의 매장 범위로 확인 */
@RestController
@RequestMapping("/api/core/docs/{docType}/{docId}")
public class DocController {
    private final JdbcClient db;
    private final DocTrail trail;
    private final StoreScope scope;
    private final Path uploadDir;

    public DocController(JdbcClient db, DocTrail trail, StoreScope scope, @Value("${app.upload-dir}") String dir) throws IOException {
        this.db = db; this.trail = trail; this.scope = scope;
        this.uploadDir = Paths.get(dir); Files.createDirectories(uploadDir);
    }

    private static final Map<String, String> STORE_OF = Map.of(
            "LOGISTICS_REQUEST", "select store_id from logistics.request where id = ?",
            "STOCK_ADJUSTMENT", "select store_id from inventory.stock_adjustment where id = ?",
            "PAYROLL_RUN", "select store_id from hr.payroll_run where id = ?",
            "DAILY_SALES", "select store_id from sales.daily_sales where id = ?");
    // EXPENSE 문서의 메모·첨부는 settlement 가 보관 매장을 알려주므로 매장 범위 대신 결재 요청의 매장으로 확인
    private static final String EXPENSE_STORE = "select store_id from approval.request where doc_type = 'EXPENSE' and doc_id = ? order by requested_at desc limit 1";

    private void check(String docType, UUID docId) {
        CurrentUser u = CurrentUser.get();
        String sql = "EXPENSE".equals(docType) ? EXPENSE_STORE : STORE_OF.get(docType);
        if (sql == null) { if (!u.isHqAdmin()) throw ApiException.forbidden("문서에 접근할 수 없습니다."); return; }
        UUID store = db.sql(sql).param(docId).query(UUID.class).optional().orElseThrow(() -> ApiException.notFound("문서"));
        scope.check(u, store);
    }

    @GetMapping("/comments")
    public List<Map<String, Object>> comments(@PathVariable String docType, @PathVariable UUID docId) {
        check(docType, docId);
        return db.sql("select id, parent_id, author_id, body, created_at from common.doc_comment where doc_type = ? and doc_id = ? order by created_at")
                .params(docType, docId).query().listOfRows();
    }

    public record CommentReq(String body, UUID parentId) {}

    @PostMapping("/comments")
    public Map<String, Object> addComment(@PathVariable String docType, @PathVariable UUID docId, @RequestBody CommentReq req) {
        check(docType, docId);
        if (req.body() == null || req.body().isBlank()) throw ApiException.bad("메모 내용을 입력하세요.");
        UUID id = UUID.randomUUID();
        db.sql("insert into common.doc_comment(id, doc_type, doc_id, parent_id, author_id, body) values (?,?,?,?,?,?)")
                .params(id, docType, docId, req.parentId(), CurrentUser.get().id(), req.body().trim()).update();
        return Map.of("id", id);
    }

    @GetMapping("/history")
    public List<Map<String, Object>> history(@PathVariable String docType, @PathVariable UUID docId) {
        check(docType, docId);
        return trail.history(docType, docId);
    }

    @GetMapping("/attachments")
    public List<Map<String, Object>> attachments(@PathVariable String docType, @PathVariable UUID docId) {
        check(docType, docId);
        return db.sql("select id, file_name, mime_type, size_bytes, uploaded_by, uploaded_at from common.attachment where doc_type = ? and doc_id = ? order by uploaded_at")
                .params(docType, docId).query().listOfRows();
    }

    @PostMapping(value = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@PathVariable String docType, @PathVariable UUID docId, @RequestParam("file") MultipartFile file) throws IOException {
        check(docType, docId);
        if (file.isEmpty()) throw ApiException.bad("파일이 비어 있습니다.");
        UUID id = UUID.randomUUID();
        Path target = uploadDir.resolve(id.toString());
        file.transferTo(target);
        db.sql("insert into common.attachment(id, doc_type, doc_id, object_key, file_name, mime_type, size_bytes, uploaded_by) values (?,?,?,?,?,?,?,?)")
                .params(id, docType, docId, id.toString(), Objects.requireNonNullElse(file.getOriginalFilename(), "file"),
                        file.getContentType(), file.getSize(), CurrentUser.get().id()).update();
        return Map.of("id", id);
    }

    @GetMapping("/attachments/{attId}")
    public ResponseEntity<FileSystemResource> download(@PathVariable String docType, @PathVariable UUID docId, @PathVariable UUID attId) {
        check(docType, docId);
        var row = db.sql("select object_key, file_name, mime_type from common.attachment where id = ? and doc_id = ?")
                .params(attId, docId).query().listOfRows();
        if (row.isEmpty()) throw ApiException.notFound("첨부");
        String name = URLEncoder.encode((String) row.get(0).get("file_name"), StandardCharsets.UTF_8).replace("+", "%20");
        String mime = (String) row.get(0).get("mime_type");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + name)
                .contentType(mime == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(mime))
                .body(new FileSystemResource(uploadDir.resolve((String) row.get(0).get("object_key"))));
    }
}
