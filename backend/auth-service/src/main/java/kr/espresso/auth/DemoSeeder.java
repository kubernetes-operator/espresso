package kr.espresso.auth;

import kr.espresso.common.DemoIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 데모 계정 시드 (app.demo.seed=true 이고 계정이 하나도 없을 때만). 이름은 모두 가명. */
@Component
public class DemoSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);
    private final JdbcClient db;
    private final PasswordEncoder encoder;
    private final boolean enabled;

    public DemoSeeder(JdbcClient db, PasswordEncoder encoder, @Value("${app.demo.seed}") boolean enabled) {
        this.db = db; this.encoder = encoder; this.enabled = enabled;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        Integer n = db.sql("select count(*) from auth.app_user").query(Integer.class).single();
        if (n > 0) { seedBakery(); return; }
        String pw = encoder.encode("demo1234");   // 데모 전용 공통 비밀번호
        user(DemoIds.ADMIN, "admin", "최총괄", pw, "INCLUSIVE");
        user(DemoIds.LOGI, "logi", "박물류", pw, null);
        user(DemoIds.HR, "hr", "정인사", pw, null);
        user(DemoIds.SV, "sv", "윤감독", pw, null);
        user(DemoIds.OWNER1, "owner1", "이점주", pw, null);
        user(DemoIds.OWNER2, "owner2", "한점주", pw, null);
        user(DemoIds.MGR1, "mgr1", "김매니저", pw, null);
        user(DemoIds.MGR2, "mgr2", "오매니저", pw, null);
        user(DemoIds.MGR3, "mgr3", "서매니저", pw, null);
        user(DemoIds.STAFF1, "staff1", "강사원", pw, null);
        user(DemoIds.STAFF2, "staff2", "임사원", pw, null);
        user(DemoIds.INVESTOR1, "investor1", "투자자A", pw, null);

        asg(DemoIds.ADMIN, "HQ_ADMIN", "ALL", null);
        asg(DemoIds.LOGI, "LOGISTICS_MGR", "ALL", null);
        asg(DemoIds.HR, "HR_MGR", "ALL", null);
        asg(DemoIds.SV, "SUPERVISOR", "REGION", DemoIds.REGION_SEOUL);
        asg(DemoIds.OWNER1, "OWNER", "STORE", DemoIds.STORE_GANGNAM);
        asg(DemoIds.OWNER1, "OWNER", "STORE", DemoIds.STORE_HONGDAE);
        asg(DemoIds.OWNER2, "OWNER", "STORE", DemoIds.STORE_PANGYO);
        asg(DemoIds.MGR1, "STORE_MGR", "STORE", DemoIds.STORE_GANGNAM);
        asg(DemoIds.MGR2, "STORE_MGR", "STORE", DemoIds.STORE_HONGDAE);
        asg(DemoIds.MGR3, "STORE_MGR", "STORE", DemoIds.STORE_PANGYO);
        asg(DemoIds.STAFF1, "STAFF", "STORE", DemoIds.STORE_GANGNAM);
        asg(DemoIds.STAFF2, "STAFF", "STORE", DemoIds.STORE_HONGDAE);
        asg(DemoIds.INVESTOR1, "INVESTOR", "STORE", DemoIds.STORE_GANGNAM);
        log.info("demo accounts seeded (12)");
        seedBakery();
    }

    /** 1차 점검 반영: 베이커리 매장 관리자 데모 계정 (이미 있으면 건너뜀) */
    private void seedBakery() {
        if (db.sql("select count(*) from auth.app_user where login_id = 'baker1'").query(Integer.class).single() > 0) return;
        user(DemoIds.BAKER1, "baker1", "문베이커", encoder.encode("demo1234"), null);
        asg(DemoIds.BAKER1, "STORE_MGR", "STORE", DemoIds.BAKERY_CENTRAL);
        log.info("demo bakery account seeded");
    }

    private void user(UUID id, String login, String name, String hash, String vat) {
        db.sql("insert into auth.app_user(id, login_id, name, password_hash, vat_display_override) values (?,?,?,?,?)")
                .params(id, login, name, hash, vat == null ? "EXCLUSIVE" : vat).update();
    }

    private void asg(UUID user, String role, String type, UUID scope) {
        db.sql("insert into auth.user_role_assignment(user_id, role_code, scope_type, scope_id, valid_from) values (?,?,?,?, date '2026-01-01')")
                .params(user, role, type, scope).update();
    }
}
