package com.example.shortener.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/** Random base62 codes: unguessable and coordination-free; uniqueness is enforced by the repository. */
@Component
public class CodeGenerator {

    public static final int LENGTH = 7;
    private static final char[] ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    private final SecureRandom random = new SecureRandom();

    public String next() {
        char[] out = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) out[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        return new String(out);
    }
}
