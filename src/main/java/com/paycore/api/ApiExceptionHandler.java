package com.paycore.api;

import com.paycore.ledger.InsufficientFundsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String INVALID_REQUEST = "invalid_request_error";

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handle(ApiException e) {
        return error(e.status(), INVALID_REQUEST, e.code(), e.getMessage());
    }

    @ExceptionHandler(InsufficientFundsException.class)
    ResponseEntity<ApiError> handle(InsufficientFundsException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, INVALID_REQUEST, "insufficient_funds",
                "Wallet " + e.accountId() + " has insufficient funds");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handle(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, INVALID_REQUEST, "invalid_body", "Request body is missing or not valid JSON");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiError> handle(MissingRequestHeaderException e) {
        return error(HttpStatus.BAD_REQUEST, INVALID_REQUEST, "missing_header",
                "The " + e.getHeaderName() + " header is required");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handle(Exception e) {
        // Spring's own errors (unknown route, wrong HTTP method...) already know their status.
        if (e instanceof ErrorResponse springError) {
            return error(springError.getStatusCode(), INVALID_REQUEST, "invalid_request", e.getMessage());
        }
        log.error("Unhandled error", e);
        // Never leak internals (SQL, stack traces) to the client.
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "api_error", "internal_error", "Something went wrong on our side");
    }

    private static ResponseEntity<ApiError> error(HttpStatusCode status, String type, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(type, code, message));
    }
}
