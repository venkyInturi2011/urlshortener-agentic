package com.example.shortener.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUrlRequest(
        @NotBlank @Size(max = 2048) String longUrl,
        @Size(max = 32) String customAlias) {}
