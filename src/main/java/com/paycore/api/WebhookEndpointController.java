package com.paycore.api;

import com.paycore.idempotency.IdempotencyService;
import com.paycore.outbox.WebhookEndpointService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WebhookEndpointController {

    private final IdempotencyService idempotency;
    private final WebhookEndpointService endpoints;

    public WebhookEndpointController(IdempotencyService idempotency, WebhookEndpointService endpoints) {
        this.idempotency = idempotency;
        this.endpoints = endpoints;
    }

    @PostMapping("/v1/webhook_endpoints")
    public ResponseEntity<String> register(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                           @RequestBody WebhookEndpointService.RegisterRequest request) {
        return idempotency.execute(idempotencyKey, "POST /v1/webhook_endpoints", request, HttpStatus.CREATED,
                () -> endpoints.register(request)).toResponseEntity();
    }
}
