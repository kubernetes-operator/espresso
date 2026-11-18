package kr.espresso.core.support;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Transactional Outbox: 업무 트랜잭션 안에서 이벤트를 기록 → 별도 전송기가 settlement 로 전달 */
@Component
public class Outbox {
    private final JdbcClient db;
    private final Json json;
    public Outbox(JdbcClient db, Json json) { this.db = db; this.json = json; }

    public void publish(String schema, String aggregateType, UUID aggregateId, String eventType, Object payload) {
        db.sql("insert into common.outbox_event(source_schema, aggregate_type, aggregate_id, event_type, payload) values (?,?,?,?, cast(? as jsonb))")
                .params(schema, aggregateType, aggregateId, eventType, json.write(payload)).update();
    }
}
