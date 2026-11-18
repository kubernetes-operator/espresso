package kr.espresso.common;

/** 부가세 계산 규칙 (설계 §7.2): 라인 단위, 원 단위 절사, 면세 0 */
public final class Vat {
    private Vat() {}
    public static final String TAXABLE = "TAXABLE";
    public static final String EXEMPT = "EXEMPT";

    public record Amount(long supply, long vat, long total) {}

    /** 공급가 기준 */
    public static Amount fromSupply(long supply, String taxType) {
        long vat = EXEMPT.equals(taxType) ? 0 : Math.floorDiv(supply, 10);
        return new Amount(supply, vat, supply + vat);
    }

    /** 부가세 포함 금액에서 역산 */
    public static Amount fromTotal(long total, String taxType) {
        if (EXEMPT.equals(taxType)) return new Amount(total, 0, total);
        long supply = Math.round(total / 1.1d);
        return new Amount(supply, total - supply, total);
    }
}
