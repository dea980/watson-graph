package com.miniwatson.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.config.Customizer;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;


@Configuration
public class SecurityConfig {

    /** A안(기본): SS는 길을 비켜주고(permitAll), 인증은 ApiKeyAuthFilter가 담당. */
    @Bean
    @ConditionalOnProperty(name = "security.mode", havingValue = "apikey-filter", matchIfMissing = true)
    SecurityFilterChain apiKeyFilterChain(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())                                    // 무상태 API라 CSRF 불필요
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll());   // 강제는 A 필터/TenantGuard가
        return http.build();
    }
    @Bean
    @ConditionalOnProperty(name = "security.mode", havingValue = "spring-apikey")
    SecurityFilterChain springApiKeyChain(HttpSecurity http, ApiKeyAuthenticationFilter f) throws Exception {
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/**").authenticated()   // 인증 없으면 SS가 401
                        .anyRequest().permitAll())
                .addFilterBefore(f,
                        org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
    @Bean
    @ConditionalOnProperty(name = "security.mode", havingValue = "jwt")
    JwtDecoder jwtDecoder(@Value("${security.jwt-secret:}") String secret) {
        var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).build();   // 데모: 대칭키. prod는 issuer-uri/JWKS로 교체
    }

    @Bean
    @ConditionalOnProperty(name = "security.mode", havingValue = "jwt")
    SecurityFilterChain jwtChain(HttpSecurity http, JwtTenantContextFilter tenantFilter) throws Exception {
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))   // 서명·만료 검증은 SS가
                .addFilterAfter(tenantFilter,
                        org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}