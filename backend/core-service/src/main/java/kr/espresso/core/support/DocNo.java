package kr.espresso.core.support;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** 문서번호: PREFIX-YYYYMM-000001 */
@Component
public class DocNo {
    private final JdbcClient db;
    public DocNo(JdbcClient db) { this.db = db; }

    public String next(String prefix) {
        String ym = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        Integer n = db.sql("""
                insert into common.doc_sequence(prefix, yyyymm, last_no) values (?, ?, 1)
                on conflict (prefix, yyyymm) do update set last_no = common.doc_sequence.last_no + 1
                returning last_no""").params(prefix, ym).query(Integer.class).single();
        return String.format("%s-%s-%06d", prefix, ym, n);
    }
}
