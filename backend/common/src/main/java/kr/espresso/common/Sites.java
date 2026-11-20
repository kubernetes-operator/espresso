package kr.espresso.common;

import java.util.UUID;

/** 사이트 구분: 매장(STORE) / 본사(HQ) / 베이커리(BAKERY). 본사 사이트는 고정 ID (V6 마이그레이션) */
public final class Sites {
    private Sites() {}
    public static final UUID HQ = UUID.fromString("00000000-0000-0000-0000-0000000000a0");
    public static final String STORE = "STORE", HQ_TYPE = "HQ", BAKERY = "BAKERY";
}
