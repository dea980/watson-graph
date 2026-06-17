package com.miniwatson.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.*;

/** C안: SS가 JWT를 검증·인증한 뒤, namespaces 클레임을 TenantContext로 옮긴다. 격리 코어 공유. */
@Component
@ConditionalOnProperty(name = "security.mode", havingValue = "jwt")
public class JwtTenantContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            Object claim = jwt.getToken().getClaim("namespaces");   // "ns1,ns2" 또는 리스트
            TenantContext.set(parse(claim));
        }
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
        }
    }

    private Set<String> parse(Object claim) {
        Set<String> out = new HashSet<>();
        if (claim instanceof String s) { for (String t : s.split(",")) if (!t.isBlank()) out.add(t.trim()); }
        else if (claim instanceof Collection<?> c) { for (Object o : c) out.add(String.valueOf(o)); }
        return out;
    }
}