package com.paycore.api;

import com.paycore.transfers.TransferRequest;
import com.paycore.transfers.TransferResponse;
import com.paycore.transfers.TransferService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping("/v1/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    public TransferResponse create(@RequestBody TransferRequest request) {
        return transferService.transferFunds(request);
    }
}
