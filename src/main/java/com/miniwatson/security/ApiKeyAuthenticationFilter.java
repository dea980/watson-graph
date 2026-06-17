package com.miniwatson.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;

/** B안: SS 통합. X-API-Key 검증 → SecurityContext에 Authentication 심고 TenantContext도 채움.
 *  인증 필수(authenticated())는 SS 체인이, namespace 격리는 TenantGuard가 담당. */
@Component
@ConditionalOnProperty(name = "security.mode", havingValue = "spring-apikey")
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-API-Key";
    private final SecurityProperties props;
    public ApiKeyAuthenticationFilter(SecurityProperties props) { this.props = props; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String key = req.getHeader(HEADER);
        if (key != null && key.isBlank()) key = null;
        Set<String> allowed = (key == null) ? null : lookup(key);
        if (allowed != null) {
            var auth = new UsernamePasswordAuthenticationToken(
                    key, null, List.of(new SimpleGrantedAuthority("ROLE_TENANT")));
            SecurityContextHolder.getContext().setAuthentication(auth);   // SS 통합
            TenantContext.set(allowed);                                   // 격리 코어 공유
        }
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private Set<String> lookup(String presented) {
        byte[] p = presented.getBytes(StandardCharsets.UTF_8);
        Set<String> match = null;
        for (String k : props.getApiKeys().keySet()) {
            if (k.isBlank()) continue;
            if (MessageDigest.isEqual(k.getBytes(StandardCharsets.UTF_8), p)) match = props.namespacesFor(k);
        }
        return match;
    }
}