package com.miniwatson.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/** A안: X-API-Key → 허용 namespace. 인증만 담당(격리는 TenantAccessChecker). */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "security.mode", havingValue = "apikey-filter", matchIfMissing = true)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-API-Key";
    private final SecurityProperties props;
    public ApiKeyAuthFilter(SecurityProperties props) { this.props = props; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        if (!props.isEnabled()) return true;            // 보안 off
        return !req.getRequestURI().startsWith("/api/"); // /api/** 만 보호
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String key = req.getHeader(HEADER);
        if (key != null && key.isBlank()) key = null;   // 빈 헤더는 키 없음 취급
        Set<String> allowed = (key == null) ? null : lookup(key);
        if (allowed == null) {                          // 없음/모름 → 401 (fail-closed)
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "missing or invalid API key");
            return;
        }
        try {
            TenantContext.set(allowed);
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();                       // ★ 스레드풀 누수 방지
        }
    }

    /** 상수시간 비교 — 어느 키가 맞았는지 타이밍으로 못 흘리게 끝까지 순회. 빈 설정 키는 스킵. */
    private Set<String> lookup(String presented) {
        byte[] p = presented.getBytes(StandardCharsets.UTF_8);
        Set<String> match = null;
        for (String k : props.getApiKeys().keySet()) {
            if (k.isBlank()) continue;                                   // 빈 설정 키 무시
            if (MessageDigest.isEqual(k.getBytes(StandardCharsets.UTF_8), p))
                match = props.namespacesFor(k);
        }
        return match;
    }
}