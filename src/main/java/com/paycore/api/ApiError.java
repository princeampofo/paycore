package com.paycore.api;

/**
 * Every error response has this shape, e.g.
 * {@code {"type": "invalid_request_error", "code": "insufficient_funds", "message": "..."}}.
 * {@code code} is stable for clients to branch on; {@code message} is for humans.
 */
public record ApiError(String type, String code, String message) {
}
