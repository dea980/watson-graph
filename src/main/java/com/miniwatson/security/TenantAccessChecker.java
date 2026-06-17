package com.miniwatson.security;

import org.springframework.stereotype.Component;

@Component
public class TenantAccessChecker {
    private final SecurityProperties props;
    public TenantAccessChecker(SecurityProperties props) { this.props = props; }

    public void check(String namespace) {
        if (!props.isEnabled()) return;                              // 보안 off → 통과
        TenantGuard.requireAccess(namespace, TenantContext.allowed());
    }
}
