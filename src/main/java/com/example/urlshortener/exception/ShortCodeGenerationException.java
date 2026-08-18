package com.example.urlshortener.exception;

public class ShortCodeGenerationException extends RuntimeException {
    public ShortCodeGenerationException(String message) {
        super(message);
    }
}
