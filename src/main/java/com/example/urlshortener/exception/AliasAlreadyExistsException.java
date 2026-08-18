package com.example.urlshortener.exception;

public class AliasAlreadyExistsException extends RuntimeException {
    public AliasAlreadyExistsException(String alias) {
        super("Custom alias '" + alias + "' is already in use");
    }
}
