package kr.espresso.core.support;

import kr.espresso.common.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/core/notifications")
public class NotificationController {
    private final JdbcClient db;
    public NotificationController(JdbcClient db) { this.db = db; }

    @GetMapping
    public Map<String, Object> list() {
        UUID me = CurrentUser.get().id();
        var items = db.sql("select id, type, title, body, link, created_at, read_at from notify.notification where user_id = ? order by created_at desc limit 50")
                .param(me).query().listOfRows();
        Integer unread = db.sql("select count(*) from notify.notification where user_id = ? and read_at is null").param(me).query(Integer.class).single();
        return Map.of("items", items, "unread", unread);
    }

    @PostMapping("/read-all")
    public Map<String, Object> readAll() {
        db.sql("update notify.notification set read_at = now() where user_id = ? and read_at is null").param(CurrentUser.get().id()).update();
        return Map.of("ok", true);
    }
}
