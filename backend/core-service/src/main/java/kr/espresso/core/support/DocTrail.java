package kr.espresso.core.support;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.*;

/** 요구 22: 상태 전이 이력 (append-only) */
@Component
public class DocTrail {
    private final JdbcClient db;
    public DocTrail(JdbcClient db) { this.db = db; }

    public void log(String docType, UUID docId, String from, String to, UUID actor, String memo) {
        db.sql("insert into common.status_history(doc_type, doc_id, from_status, to_status, actor_id, memo) values (?,?,?,?,?,?)")
                .params(docType, docId, from, to, actor, memo).update();
    }

    public List<Map<String, Object>> history(String docType, UUID docId) {
        return db.sql("select from_status, to_status, actor_id, memo, occurred_at from common.status_history where doc_type = ? and doc_id = ? order by occurred_at, id")
                .params(docType, docId).query().listOfRows();
    }
}
