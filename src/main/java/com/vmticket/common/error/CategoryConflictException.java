package com.vmticket.common.error;

public class CategoryConflictException extends RuntimeException {

    public CategoryConflictException(String message) {
        super(message);
    }
}
