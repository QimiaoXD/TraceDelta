package io.tracedelta.common;

public enum ErrorCode {
    VALIDATION_ERROR(400),
    UNAUTHORIZED(401),
    NOT_FOUND(404),
    DUPLICATE_CASE_KEY(400),
    IMPORT_INVALID(400),
    PROVIDER_ERROR(400);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
