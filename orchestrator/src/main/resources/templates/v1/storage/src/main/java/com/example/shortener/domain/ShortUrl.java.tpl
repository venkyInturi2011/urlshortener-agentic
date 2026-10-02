package com.example.shortener.domain;

import java.time.Instant;

/** A shortened link. {@code deleted} is a soft-delete marker so analytics history survives takedown. */
public record ShortUrl(String code, String longUrl, boolean customAlias, Instant createdAt, boolean deleted) {}
