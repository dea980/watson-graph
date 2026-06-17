package com.miniwatson.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.FORBIDDEN)
public class TenantAccessException extends RuntimeException {
    public TenantAccessException(String message) { super(message); }
}