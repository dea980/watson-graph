package com.miniwatson.security;

import java.util.Set;

/** 요청 namespace가 호출자 허용 집합에 있는지 강제. requireReadOnly와 같은 순수-static 패턴. */
public final class TenantGuard {
    private TenantGuard() {}

    public static void requireAccess(String namespace, Set<String> allowed) {
        if (allowed == null || allowed.isEmpty())
            throw new TenantAccessException("unauthenticated");   // 컨텍스트 없음
        if (allowed.contains("*")) return;                        // 전체 권한(dev/admin)
        String ns = (namespace == null || namespace.isBlank()) ? "default" : namespace;
        if (!allowed.contains(ns))
            throw new TenantAccessException("namespace '" + ns + "' 접근 거부");
    }
}