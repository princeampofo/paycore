package com.paycore.api;

import com.paycore.idempotency.IdempotencyService;
import com.paycore.payments.CreatePaymentRequest;
import com.paycore.payments.PaymentResponse;
import com.paycore.payments.PaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.function.Supplier;

@RestController
public class PaymentController {

    private final IdempotencyService idempotency;
    private final PaymentService paymentService;

    public PaymentController(IdempotencyService idempotency, PaymentService paymentService) {
        this.idempotency = idempotency;
        this.paymentService = paymentService;
    }

    /** Creates the payment and authorizes it. The response's status is authorized, failed or unknown. */
    @PostMapping("/v1/payments")
    public ResponseEntity<String> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                         @RequestBody CreatePaymentRequest request) {
        // Charging the card happens outside any database transaction; recording the result inside one.
        return idempotency.execute(idempotencyKey, "POST /v1/payments", request, HttpStatus.CREATED,
                () -> paymentService.chargeCard(request),
                paymentService::recordCharge).toResponseEntity();
    }

    @PostMapping("/v1/payments/{id}/capture")
    public ResponseEntity<String> capture(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                          @PathVariable long id) {
        return action(idempotencyKey, "POST /v1/payments/" + id + "/capture", () -> paymentService.capture(id));
    }

    @PostMapping("/v1/payments/{id}/cancel")
    public ResponseEntity<String> cancel(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                         @PathVariable long id) {
        return action(idempotencyKey, "POST /v1/payments/" + id + "/cancel", () -> paymentService.cancel(id));
    }

    @PostMapping("/v1/payments/{id}/refund")
    public ResponseEntity<String> refund(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                         @PathVariable long id) {
        return action(idempotencyKey, "POST /v1/payments/" + id + "/refund", () -> paymentService.refund(id));
    }

    public record PaymentList(List<PaymentResponse> data) {
    }

    @GetMapping("/v1/payments")
    public PaymentList list(@RequestParam(name = "merchant_wallet_id", required = false) Long merchantWalletId,
                            @RequestParam(required = false) String customer,
                            @RequestParam(defaultValue = "20") int limit) {
        return new PaymentList(paymentService.list(merchantWalletId, customer, limit));
    }

    @GetMapping("/v1/payments/{id}")
    public PaymentResponse get(@PathVariable long id) {
        return paymentService.get(id);
    }

    // These actions have no request body; the endpoint (which includes the payment id) is the request.
    private ResponseEntity<String> action(String idempotencyKey, String endpoint, Supplier<PaymentResponse> work) {
        return idempotency.execute(idempotencyKey, endpoint, null, HttpStatus.OK, work).toResponseEntity();
    }
}
