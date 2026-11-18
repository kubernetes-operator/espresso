package kr.espresso.core.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/** Outbox 전송기: 미전송 이벤트를 순서대로 settlement-service 로 전달 (실패 시 다음 주기 재시도, 수신측 멱등) */
@Component
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private final JdbcClient db;
    private final RestClient http;

    public OutboxRelay(JdbcClient db, @Value("${app.settlement-url}") String url) {
        this.db = db; this.http = RestClient.create(url);
    }

    @Scheduled(fixedDelay = 3000, initialDelay = 10000)
    public void relay() {
        var rows = db.sql("select id, event_type, aggregate_id, cast(payload as text) payload, created_at from common.outbox_event where published_at is null order by created_at limit 100")
                .query().listOfRows();
        for (var r : rows) {
            try {
                http.post().uri("/internal/events").header("Content-Type", "application/json")
                        .body(Map.of("id", r.get("id"), "type", r.get("event_type"), "payload", r.get("payload"), "createdAt", r.get("created_at").toString()))
                        .retrieve().toBodilessEntity();
                db.sql("update common.outbox_event set published_at = now() where id = ?").param((UUID) r.get("id")).update();
            } catch (Exception e) {
                log.warn("outbox relay failed {} {}: {}", r.get("event_type"), r.get("id"), e.getMessage());
                return;   // 순서 보장: 실패하면 이번 주기 중단
            }
        }
    }
}
