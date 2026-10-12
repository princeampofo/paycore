package com.paycore.payments;

import com.paycore.ledger.Money;
import com.paycore.metrics.PaycoreMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;

/** Talks to the card processor (in this project, mock-processor/). */
@Component
public class ProcessorClient {

    /** What we know about a charge: the card was charged, it wasn't, or we can't tell. */
    public enum ChargeResult { CHARGED, NOT_CHARGED, UNKNOWN }

    record ChargeRequest(String ref, long amount, String currency, String cardToken) {
    }

    record ChargeResponse(String ref, String status) {
    }

    private static final Logger log = LoggerFactory.getLogger(ProcessorClient.class);

    private final RestClient rest;
    private final PaycoreMetrics metrics;

    public ProcessorClient(RestClient.Builder builder, PaycoreMetrics metrics,
                           @Value("${paycore.processor.base-url}") String baseUrl,
                           @Value("${paycore.processor.timeout}") Duration timeout) {
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build());
        requestFactory.setReadTimeout(timeout);
        this.rest = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.metrics = metrics;
    }

    /**
     * Asks the processor to charge the card under our {@code ref}.
     *
     * A timeout, a dropped connection or a server error tells us nothing about whether the card
     * was charged. Calling it a decline could lose a real charge, and retrying blindly could charge
     * twice. So it's reported as UNKNOWN, to be resolved later by asking about {@code ref}.
     */
    public ChargeResult charge(String ref, Money amount, String cardToken) {
        return counted("charge", requestCharge(ref, amount, cardToken));
    }

    private ChargeResult requestCharge(String ref, Money amount, String cardToken) {
        try {
            ChargeResponse response = rest.post().uri("/charges")
                    .body(new ChargeRequest(ref, amount.amount(), amount.currency(), cardToken))
                    .retrieve()
                    .body(ChargeResponse.class);
            return resultOf(response);
        } catch (RestClientException e) {
            log.warn("No clear answer from the processor for charge {}: {}", ref, e.getMessage());
            return ChargeResult.UNKNOWN;
        }
    }

    /** Asks what happened to an earlier charge. Never charges anything. */
    public ChargeResult chargeStatus(String ref) {
        return counted("status", requestStatus(ref));
    }

    private ChargeResult requestStatus(String ref) {
        try {
            return resultOf(rest.get().uri("/charges/{ref}", ref).retrieve().body(ChargeResponse.class));
        } catch (HttpClientErrorException.NotFound e) {
            // The processor never received this charge, so the card wasn't charged.
            return ChargeResult.NOT_CHARGED;
        } catch (RestClientException e) {
            log.warn("Could not get the status of charge {}: {}", ref, e.getMessage());
            return ChargeResult.UNKNOWN;
        }
    }

    private ChargeResult counted(String type, ChargeResult result) {
        metrics.processorRequest(type, result.name().toLowerCase());
        return result;
    }

    private static ChargeResult resultOf(ChargeResponse response) {
        return switch (response == null ? "" : response.status()) {
            case "approved" -> ChargeResult.CHARGED;
            case "declined" -> ChargeResult.NOT_CHARGED;
            default -> ChargeResult.UNKNOWN;
        };
    }
}
