package com.yusijia.houseclientservices.controller.dto;

public record LoginResponse(
        String token,
        Long userId,
        String username,
        String role,
        String displayName,
        String message
) {
    public LoginResponse(String token, String message) {
        this(token, null, null, null, null, message);
    }
}
