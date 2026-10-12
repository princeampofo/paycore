package com.paycore.payments;

import com.paycore.ledger.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;

/** Talks to the card processor (in this project, mock-processor/). */
@Component
public class ProcessorClient {

    public enum ChargeResult { APPROVED, DECLINED, UNKNOWN }

    record ChargeRequest(String ref, long amount, String currency, String cardToken) {
    }

    record ChargeResponse(String ref, String status) {
    }

    private static final Logger log = LoggerFactory.getLogger(ProcessorClient.class);

    private final RestClient rest;

    public ProcessorClient(RestClient.Builder builder,
                           @Value("${paycore.processor.base-url}") String baseUrl,
                           @Value("${paycore.processor.timeout}") Duration timeout) {
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build());
        requestFactory.setReadTimeout(timeout);
        this.rest = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    /**
     * Asks the processor to charge the card under our {@code ref}.
     *
     * A timeout, a dropped connection or a server error tells us nothing about whether the card
     * was charged. Calling it a decline could lose a real charge, and retrying blindly could charge
     * twice. So it's reported as UNKNOWN, to be resolved later by asking about {@code ref}.
     */
    public ChargeResult charge(String ref, Money amount, String cardToken) {
        try {
            ChargeResponse response = rest.post().uri("/charges")
                    .body(new ChargeRequest(ref, amount.amount(), amount.currency(), cardToken))
                    .retrieve()
                    .body(ChargeResponse.class);
            return switch (response == null ? "" : response.status()) {
                case "approved" -> ChargeResult.APPROVED;
                case "declined" -> ChargeResult.DECLINED;
                default -> ChargeResult.UNKNOWN;
            };
        } catch (RestClientException e) {
            log.warn("No clear answer from the processor for charge {}: {}", ref, e.getMessage());
            return ChargeResult.UNKNOWN;
        }
    }
}
