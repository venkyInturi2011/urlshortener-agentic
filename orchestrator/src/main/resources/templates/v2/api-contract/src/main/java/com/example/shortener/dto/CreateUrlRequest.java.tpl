package com.example.shortener.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** {@code expiresAt} is optional and additive: v1 clients that omit it keep working unchanged. */
public record CreateUrlRequest(
        @NotBlank @Size(max = 2048) String longUrl,
        @Size(max = 32) String customAlias,
        Instant expiresAt) {}
