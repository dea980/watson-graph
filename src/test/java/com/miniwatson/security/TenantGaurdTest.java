package com.miniwatson.security;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class TenantGuardTest {

    @Test void allowsNamespaceInAllowedSet() {
        assertDoesNotThrow(() -> TenantGuard.requireAccess("kr-bcg", Set.of("default", "kr-bcg")));
    }
    @Test void deniesNamespaceNotInAllowedSet() {
        assertThrows(TenantAccessException.class,
                () -> TenantGuard.requireAccess("secret-ns", Set.of("default")));
    }
    @Test void wildcardAllowsAny() {
        assertDoesNotThrow(() -> TenantGuard.requireAccess("anything", Set.of("*")));
    }
    @Test void nullOrEmptyAllowedIsDenied() {
        assertThrows(TenantAccessException.class, () -> TenantGuard.requireAccess("default", null));
        assertThrows(TenantAccessException.class, () -> TenantGuard.requireAccess("default", Set.of()));
    }
    @Test void blankNamespaceFallsBackToDefault() {
        assertDoesNotThrow(() -> TenantGuard.requireAccess("", Set.of("default")));
        assertThrows(TenantAccessException.class, () -> TenantGuard.requireAccess("", Set.of("kr-bcg")));
    }
}