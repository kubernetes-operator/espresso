-- =====================================================================
-- auth_db : 계정·등급·매장 스코프 (독립 클러스터, 요구 13)
-- store_id / region_id 는 core_db 소유 값 → FK 없이 값으로만 참조
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS auth;
SET search_path TO auth;

-- 등급 -------------------------------------------------------------------
CREATE TABLE role (
    code        VARCHAR(32) PRIMARY KEY,
    name        VARCHAR(50)  NOT NULL,
    line        VARCHAR(10)  NOT NULL CHECK (line IN ('HQ','STORE','EXTERNAL')),
    rank        SMALLINT     NOT NULL,               -- 작을수록 상위
    parent_code VARCHAR(32)  REFERENCES role(code),
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    description TEXT
);

INSERT INTO role (code, name, line, rank, parent_code, is_active, description) VALUES
 ('HQ_ADMIN',      '총괄 관리자', 'HQ',       0, NULL,       TRUE,  '최상위, 전 매장'),
 ('LOGISTICS_MGR', '물류 관리자', 'HQ',       1, 'HQ_ADMIN', TRUE,  '담당 매장 물류 승인·발송'),
 ('HR_MGR',        '인적 관리자', 'HQ',       1, 'HQ_ADMIN', TRUE,  '담당 매장 직원·근무·급여'),
 ('SUPERVISOR',    '슈퍼바이저',  'HQ',       1, 'HQ_ADMIN', FALSE, '권역 담당 (정의만, 비활성)'),
 ('OWNER',         '점주',        'STORE',    1, 'HQ_ADMIN', TRUE,  '매장 라인 최상위'),
 ('STORE_MGR',     '매장 관리자', 'STORE',    2, 'OWNER',    TRUE,  '매장 운영'),
 ('STAFF',         '일반 사원',   'STORE',    3, 'STORE_MGR',TRUE,  '요청·수령·출퇴근'),
 ('INVESTOR',      '투자자',      'EXTERNAL', 9, NULL,       TRUE,  '공개된 투자 정보 읽기 전용');

-- 계정 -------------------------------------------------------------------
CREATE TABLE app_user (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),   -- 앱에서 UUIDv7 발급
    login_id              VARCHAR(64)  NOT NULL UNIQUE,
    name                  VARCHAR(50)  NOT NULL,
    email                 VARCHAR(255),
    phone                 VARCHAR(32),
    password_hash         VARCHAR(255) NOT NULL,                        -- bcrypt/argon2
    status                VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
                          CHECK (status IN ('ACTIVE','LOCKED','SUSPENDED','RETIRED')),
    vat_display_override  VARCHAR(10)  CHECK (vat_display_override IN ('EXCLUSIVE','INCLUSIVE')),  -- NULL=매장 기본값
    default_store_id      UUID,
    failed_login_count    SMALLINT     NOT NULL DEFAULT 0,
    password_changed_at   TIMESTAMPTZ,
    last_login_at         TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by            UUID,
    updated_by            UUID
);

-- 등급 배정 + 스코프 (한 사람이 매장별로 다른 등급 가능) ----------------------
CREATE TABLE user_role_assignment (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES app_user(id),
    role_code   VARCHAR(32) NOT NULL REFERENCES role(code),
    scope_type  VARCHAR(10) NOT NULL CHECK (scope_type IN ('ALL','REGION','STORE')),
    scope_id    UUID,                                  -- REGION/STORE id, ALL 이면 NULL
    valid_from  DATE        NOT NULL DEFAULT CURRENT_DATE,
    valid_to    DATE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  UUID,
    CHECK ((scope_type = 'ALL') = (scope_id IS NULL))
);
CREATE INDEX ix_ura_user  ON user_role_assignment(user_id);
CREATE INDEX ix_ura_scope ON user_role_assignment(scope_type, scope_id);
CREATE UNIQUE INDEX ux_ura_active
    ON user_role_assignment(user_id, role_code, scope_type, COALESCE(scope_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE valid_to IS NULL;

-- 토큰·로그인 -------------------------------------------------------------
CREATE TABLE refresh_token (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES app_user(id),
    token_hash  VARCHAR(128) NOT NULL UNIQUE,
    device_info VARCHAR(255),
    issued_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ
);
CREATE INDEX ix_refresh_user ON refresh_token(user_id);

CREATE TABLE push_device (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES app_user(id),
    platform    VARCHAR(10) NOT NULL CHECK (platform IN ('IOS','ANDROID','WEB')),
    push_token  TEXT        NOT NULL,
    registered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, push_token)
);

CREATE TABLE login_history (
    id          BIGSERIAL PRIMARY KEY,
    user_id     UUID,
    login_id    VARCHAR(64),
    success     BOOLEAN     NOT NULL,
    ip          INET,
    user_agent  TEXT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_login_hist_user ON login_history(user_id, occurred_at DESC);

-- 감사로그 (append-only) ---------------------------------------------------
CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    actor_id    UUID,
    action      VARCHAR(64) NOT NULL,                  -- ROLE_GRANT, ROLE_REVOKE, VAT_OVERRIDE ...
    target_type VARCHAR(32),
    target_id   UUID,
    before_data JSONB,
    after_data  JSONB,
    ip          INET,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_target ON audit_log(target_type, target_id);

CREATE OR REPLACE FUNCTION auth.forbid_modify() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'append-only table: %', TG_TABLE_NAME; END $$;
CREATE TRIGGER trg_audit_append_only BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION auth.forbid_modify();

-- 이벤트 발행 (Transactional Outbox) ---------------------------------------
CREATE TABLE outbox_event (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id   UUID        NOT NULL,
    event_type     VARCHAR(64) NOT NULL,
    payload        JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);
CREATE INDEX ix_outbox_unpublished ON outbox_event(created_at) WHERE published_at IS NULL;
