package kr.espresso.core.hr;

import java.time.*;

/** 근무시간 분해: 휴게 차감 → 일 8시간 초과 = 연장, 22:00~06:00 겹침 = 야간 (KST) */
public final class WorkCalc {
    private WorkCalc() {}
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public record Minutes(int regular, int overtime, int night) {}

    public static Minutes split(OffsetDateTime in, OffsetDateTime out, int breakMin) {
        if (in == null || out == null || !out.isAfter(in)) return new Minutes(0, 0, 0);
        int total = (int) Duration.between(in, out).toMinutes() - Math.max(breakMin, 0);
        total = Math.max(total, 0);
        int regular = Math.min(total, 480), overtime = total - regular;
        int night = 0;
        LocalDateTime a = in.atZoneSameInstant(KST).toLocalDateTime(), b = out.atZoneSameInstant(KST).toLocalDateTime();
        for (LocalDate d = a.toLocalDate().minusDays(1); !d.isAfter(b.toLocalDate()); d = d.plusDays(1)) {
            LocalDateTime ns = d.atTime(22, 0), ne = d.plusDays(1).atTime(6, 0);
            LocalDateTime s = a.isAfter(ns) ? a : ns, e = b.isBefore(ne) ? b : ne;
            if (e.isAfter(s)) night += (int) Duration.between(s, e).toMinutes();
        }
        return new Minutes(regular, overtime, Math.min(night, total));
    }
}
