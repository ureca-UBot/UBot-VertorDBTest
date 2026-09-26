package com.myapp.infrastructure.vector.http;

public class VectorStoreHttpException extends RuntimeException {
    private final int statusCode;

    public VectorStoreHttpException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
