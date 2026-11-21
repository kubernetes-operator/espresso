package kr.espresso.common;

import java.util.UUID;

/** 데모 데이터 고정 ID — auth/core/settlement 시드가 같은 ID 를 공유한다. (데모 전용) */
public final class DemoIds {
    private DemoIds() {}
    public static final UUID REGION_SEOUL    = UUID.fromString("11111111-0000-0000-0000-000000000001");
    public static final UUID REGION_GYEONGGI = UUID.fromString("11111111-0000-0000-0000-000000000002");
    public static final UUID STORE_GANGNAM   = UUID.fromString("22222222-0000-0000-0000-000000000001");
    public static final UUID STORE_HONGDAE   = UUID.fromString("22222222-0000-0000-0000-000000000002");
    public static final UUID STORE_PANGYO    = UUID.fromString("22222222-0000-0000-0000-000000000003");
    public static final UUID BAKERY_CENTRAL  = UUID.fromString("22222222-0000-0000-0000-000000000010");

    public static UUID user(int n) { return UUID.fromString(String.format("33333333-0000-0000-0000-%012d", n)); }
    public static final UUID ADMIN = user(1), LOGI = user(2), HR = user(3), SV = user(4),
            OWNER1 = user(5), OWNER2 = user(6), MGR1 = user(7), MGR2 = user(8), MGR3 = user(9),
            STAFF1 = user(10), STAFF2 = user(11), INVESTOR1 = user(12), BAKER1 = user(13);
}
