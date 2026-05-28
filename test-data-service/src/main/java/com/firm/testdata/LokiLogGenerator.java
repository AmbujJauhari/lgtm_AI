package com.firm.testdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Pushes synthetic error log lines to Loki for the 5 error scenarios.
 * All log entries are streamed under a single appcode (AT4278 by default) —
 * individual services are differentiated by the {@code service} label, not by
 * separate appcodes. This matches the production data model where AT4278 is
 * the umbrella application code for the whole 140-service platform.
 */
@Component
public class LokiLogGenerator {

    private static final Logger log = LoggerFactory.getLogger(LokiLogGenerator.class);
    private static final DateTimeFormatter LOG_TS_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneOffset.UTC);

    private final WebClient lokiClient;
    private final String appcode;

    public LokiLogGenerator(TestDataProperties properties) {
        this.appcode = properties.appcode();
        this.lokiClient = WebClient.builder()
                .baseUrl(properties.lokiUrl())
                .build();
    }

    public void pushAll(Map<String, String> traceIds) {
        pushNpe(traceIds.get("npe"));
        pushTimeout(traceIds.get("timeout"));
        pushDbError(traceIds.get("db_error"));
        pushMqError(traceIds.get("mq_error"));
        pushOom();
        log.info("All log scenarios pushed to Loki under appcode={}", appcode);
    }

    private void pushNpe(String traceId) {
        String logLine = "%s ERROR [collateral-service,%s,7a3f1b00] [http-nio-8080-exec-3] c.f.c.CollateralCalculator - java.lang.NullPointerException: null\n\tat com.firm.collateral.CollateralCalculator.calculateInitialMargin(CollateralCalculator.java:247)"
                .formatted(now(), traceId);
        push("collateral-service", logLine);
    }

    private void pushTimeout(String traceId) {
        String logLine = "%s ERROR [margin-call-service,%s,4d2e9c00] [http-nio-8080-exec-7] c.f.m.MarginCallService - java.net.SocketTimeoutException: Read timed out after 30000ms\n\tat com.firm.margin.client.CollateralServiceClient.getPositions(CollateralServiceClient.java:112) calling collateral-service /api/positions"
                .formatted(now(), traceId);
        push("margin-call-service", logLine);
    }

    private void pushDbError(String traceId) {
        String lineTemplate = " ERROR [%s,%s,1a2b3c00] [db-pool-1] c.f.r.Repository - com.sybase.jdbc4.jdbc.SybSQLException: JZ006: Caught IOException: Connection reset by peer";
        push("trade-enrichment-service",
            now() + lineTemplate.formatted("trade-enrichment-service", traceId));
        push("collateral-service",
            now() + lineTemplate.formatted("collateral-service", traceId));
        push("risk-aggregator-service",
            now() + lineTemplate.formatted("risk-aggregator-service", traceId));
    }

    private void pushMqError(String traceId) {
        String lineTemplate = " ERROR [%s,%s,5e6f7a00] [mq-listener-1] c.f.m.MqConsumer - com.ibm.mq.MQException: MQRC_Q_FULL (reason=2053) queue=MARGIN.CALL.REQUEST.QUEUE";
        push("margin-event-consumer",
            now() + lineTemplate.formatted("margin-event-consumer", traceId));
        push("trade-settlement-service",
            now() + lineTemplate.formatted("trade-settlement-service", traceId));
    }

    private void pushOom() {
        // No trace — JVM crash pre-empts MDC logging
        String logLine = "%s ERROR [risk-aggregator-service] [GC-worker-2] c.f.r.PositionAggregator - java.lang.OutOfMemoryError: Java heap space\n\tat com.firm.risk.PositionAggregator.aggregate(PositionAggregator.java:891)"
                .formatted(now());
        push("risk-aggregator-service", logLine);
    }

    /** Current UTC time in Spring Boot's default log timestamp format. */
    private String now() {
        return LOG_TS_FORMAT.format(Instant.now());
    }

    private void push(String service, String logLine) {
        long nowNano = Instant.now().toEpochMilli() * 1_000_000L;
        String body = """
                {
                  "streams": [
                    {
                      "stream": {
                        "service": "%s",
                        "appcode": "%s",
                        "level": "error"
                      },
                      "values": [["%d", %s]]
                    }
                  ]
                }
                """.formatted(service, appcode, nowNano, jsonString(logLine));

        lokiClient.post()
                .uri("/loki/api/v1/push")
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Void.class)
                .block();

        log.info("Log pushed → service={} appcode={}", service, appcode);
    }

    private String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
