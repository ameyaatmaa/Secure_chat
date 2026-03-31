package com.securechat.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank @Size(min = 3, max = 50)
    @Pattern(regexp = "^[a-zA-Z0-9_.-]+$", message = "must contain only letters, digits, '_', '-', or '.'")
    String username,
    @NotBlank @Size(min = 8) String password,
    @NotBlank String publicKey
) {}
