package com.miniwatson.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.*;

/** security.enabled + security.api-keys(key -> "ns1,ns2" 또는 "*"). */
@Component
@ConfigurationProperties(prefix = "security")
public class SecurityProperties {
    private boolean enabled = true;
    private Map<String, String> apiKeys = new LinkedHashMap<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Map<String, String> getApiKeys() { return apiKeys; }
    public void setApiKeys(Map<String, String> apiKeys) { this.apiKeys = apiKeys; }

    /** key -> 허용 namespace 집합. 모르는 키면 null. */
    public Set<String> namespacesFor(String key) {
        String csv = apiKeys.get(key);
        if (csv == null) return null;
        Set<String> out = new HashSet<>();
        for (String s : csv.split(",")) { String t = s.trim(); if (!t.isEmpty()) out.add(t); }
        return out;
    }
}