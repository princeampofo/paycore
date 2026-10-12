package com.paycore.api;

import com.paycore.idempotency.IdempotencyService;
import com.paycore.transfers.TransferRequest;
import com.paycore.transfers.TransferService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TransferController {

    private final IdempotencyService idempotency;
    private final TransferService transferService;

    public TransferController(IdempotencyService idempotency, TransferService transferService) {
        this.idempotency = idempotency;
        this.transferService = transferService;
    }

    // Returns the saved JSON rather than a TransferResponse, so a retry gets byte-for-byte
    // the same body as the first request.
    @PostMapping("/v1/transfers")
    public ResponseEntity<String> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                         @RequestBody TransferRequest request) {
        return idempotency.execute(idempotencyKey, "POST /v1/transfers", request, HttpStatus.CREATED,
                () -> transferService.transferFunds(request)).toResponseEntity();
    }
}
