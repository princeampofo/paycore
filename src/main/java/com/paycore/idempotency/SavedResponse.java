package com.paycore.idempotency;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** The exact HTTP response of the first request, replayed byte for byte to every retry. */
public record SavedResponse(int status, String body) {

    public ResponseEntity<String> toResponseEntity() {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
