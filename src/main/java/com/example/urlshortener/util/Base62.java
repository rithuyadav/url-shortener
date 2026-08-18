package com.example.urlshortener.util;

import java.security.SecureRandom;

/**
 * Base62 alphabet helpers used for short-code generation.
 * Base62 (0-9, a-z, A-Z) is used instead of Base64 to keep generated codes
 * URL-safe with no encoding/escaping concerns (no '+', '/', or '=').
 */
public final class Base62 {

    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Base62() {
    }

    /**
     * Generates a random Base62 string of the given length using a
     * cryptographically strong random source. Not sequential/guessable,
     * which avoids short codes being enumerable by an attacker.
     */
    public static String randomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    public static boolean isValidAlphabetOnly(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (ALPHABET.indexOf(value.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }
}
