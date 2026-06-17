package com.miniwatson.security;

import java.util.Set;
public class TenantContext {
    private static final ThreadLocal<Set<String>> ALLOWED = new ThreadLocal<>();
    private TenantContext() {}

    public static void set(Set<String> allowedNamespaces) { ALLOWED.set(allowedNamespaces); }
    public static Set<String> allowed() { return ALLOWED.get(); }
    public static void clear() { ALLOWED.remove(); }
}
