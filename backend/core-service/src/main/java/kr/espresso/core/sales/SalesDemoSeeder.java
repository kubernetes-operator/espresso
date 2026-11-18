package kr.espresso.core.sales;

import kr.espresso.common.CurrentUser;
import kr.espresso.common.DemoIds;
import kr.espresso.common.RoleScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/** 데모: 지난달 1일 ~ 어제까지 매장별 일 매출 (마감 → 결산 이벤트). 결산·비교 화면 확인용 */
@Component
@Order(5)
public class SalesDemoSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SalesDemoSeeder.class);
    private final JdbcClient db;
    private final SalesService svc;
    private final boolean enabled;

    public SalesDemoSeeder(JdbcClient db, SalesService svc, @Value("${app.demo.seed}") boolean enabled) { this.db = db; this.svc = svc; this.enabled = enabled; }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled || db.sql("select count(*) from sales.daily_sales").query(Integer.class).single() > 0) return;
        CurrentUser admin = new CurrentUser(DemoIds.ADMIN, "admin", "최총괄", List.of(new RoleScope("HQ_ADMIN", "ALL", null)), null);
        Object[][] stores = {{DemoIds.STORE_GANGNAM, 1_150_000L}, {DemoIds.STORE_HONGDAE, 880_000L}, {DemoIds.STORE_PANGYO, 1_020_000L}};
        Random rnd = new Random(42);
        LocalDate from = LocalDate.now().minusMonths(1).withDayOfMonth(1), to = LocalDate.now().minusDays(1);
        int n = 0;
        for (Object[] st : stores) {
            long base = (Long) st[1];
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                double wk = d.getDayOfWeek().getValue() >= 6 ? 1.25 : 1.0;
                long total = Math.round(base * wk * (0.85 + rnd.nextDouble() * 0.3) / 100) * 100;
                long bev = Math.round(total * 0.66 / 100) * 100, des = Math.round(total * 0.26 / 100) * 100, tw = Math.round(total * 0.04 / 100) * 100, etc = total - bev - des - tw;
                long disc = Math.round(total * 0.012 / 100) * 100;
                long net = total - disc, cash = Math.round(net * 0.08 / 100) * 100;
                // SecurityContext 없이 서비스 직접 호출 (데모 시드 전용)
                UUID id = svc.upsert((UUID) st[0], d, new SalesService.DayReq(bev, des, tw, etc, 0L, disc, 0L, net - cash, cash, 0L, (int) (total / 6200), "데모", true), "MANUAL", null, admin);
                n++;
            }
        }
        log.info("demo sales seeded: {} days", n);
    }
}
