package kr.espresso.core.support;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.UUID;

/** 앱 내 알림 (푸시 발송은 notification 워커가 pushed_at 이 비어 있는 건을 처리 — 운영 단계) */
@Component
public class Notifier {
    private final JdbcClient db;
    public Notifier(JdbcClient db) { this.db = db; }

    public void send(Collection<UUID> users, String type, String title, String body, String link, UUID storeId) {
        for (UUID u : users)
            db.sql("insert into notify.notification(user_id, type, title, body, link, store_id) values (?,?,?,?,?,?)")
                    .params(u, type, title, body, link, storeId).update();
    }
}
