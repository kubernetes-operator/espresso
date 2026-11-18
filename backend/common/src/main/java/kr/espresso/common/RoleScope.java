package kr.espresso.common;

import java.util.UUID;

/** 등급 배정 1건: role + 범위(ALL / REGION / STORE) */
public record RoleScope(String role, String scopeType, UUID scopeId) {
    public boolean isAll() { return "ALL".equals(scopeType); }
}
