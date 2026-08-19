package com.blackbox.wow.client;

public class RaiderIoCollectionException extends RuntimeException {

    private final Category category;

    public RaiderIoCollectionException(Category category, String message) {
        super(message);
        this.category = category;
    }

    public RaiderIoCollectionException(Category category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    public Category category() {
        return category;
    }

    public enum Category {
        NOT_FOUND,
        RATE_LIMITED,
        TIMEOUT,
        UPSTREAM,
        INVALID_RESPONSE,
        UNKNOWN
    }
}
