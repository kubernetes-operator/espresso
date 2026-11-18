package kr.espresso.core.inventory;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/** 재고 원장 + 잔액 (이동평균법) */
@Service
public class StockService {
    private final JdbcClient db;
    public StockService(JdbcClient db) { this.db = db; }

    /** 입고: 이동평균 단가 갱신. unitCost = 공급가 기준 단가 */
    public void receive(UUID store, UUID item, BigDecimal qty, BigDecimal unitCost, String refType, UUID refId, UUID actor) {
        var cur = balance(store, item);
        BigDecimal newQty = cur[0].add(qty);
        BigDecimal avg = newQty.signum() <= 0 ? unitCost
                : cur[0].max(BigDecimal.ZERO).multiply(cur[1]).add(qty.multiply(unitCost)).divide(newQty.max(qty), 2, RoundingMode.HALF_UP);
        upsert(store, item, newQty, avg);
        ledger(store, item, qty, unitCost, "RECEIPT", refType, refId, newQty, actor);
    }

    /** 출고성 차감 (손실·결손·내부사용·판매): 현재 이동평균 단가로 차감, 금액 반환 */
    public long issue(UUID store, UUID item, BigDecimal qty, String reason, String refType, UUID refId, UUID actor) {
        var cur = balance(store, item);
        BigDecimal newQty = cur[0].subtract(qty);
        upsert(store, item, newQty, cur[1]);
        return ledger(store, item, qty.negate(), cur[1], reason, refType, refId, newQty, actor);
    }

    /** [qty, avgCost] */
    public BigDecimal[] balance(UUID store, UUID item) {
        var rows = db.sql("select qty, avg_cost from inventory.stock_balance where store_id = ? and item_id = ? for update")
                .params(store, item).query().listOfRows();
        if (rows.isEmpty()) return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO};
        return new BigDecimal[]{(BigDecimal) rows.get(0).get("qty"), (BigDecimal) rows.get(0).get("avg_cost")};
    }

    private void upsert(UUID store, UUID item, BigDecimal qty, BigDecimal avg) {
        db.sql("""
                insert into inventory.stock_balance(store_id, item_id, qty, avg_cost, updated_at) values (?,?,?,?, now())
                on conflict (store_id, item_id) do update set qty = excluded.qty, avg_cost = excluded.avg_cost, updated_at = now()""")
                .params(store, item, qty, avg).update();
    }

    private long ledger(UUID store, UUID item, BigDecimal delta, BigDecimal cost, String reason, String refType, UUID refId, BigDecimal bal, UUID actor) {
        long amount = delta.multiply(cost).setScale(0, RoundingMode.HALF_UP).longValue();
        db.sql("""
                insert into inventory.stock_ledger(store_id, item_id, qty_delta, unit_cost, amount, reason, ref_type, ref_id, balance_qty, actor_id)
                values (?,?,?,?,?,?,?,?,?,?)""").params(store, item, delta, cost, amount, reason, refType, refId, bal, actor).update();
        return Math.abs(amount);
    }
}
