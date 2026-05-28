package com.firm.testdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.Map;

@Component
public class LokiLogGenerator {

    private static final Logger log = LoggerFactory.getLogger(LokiLogGenerator.class);

    private final WebClient lokiClient;

    public LokiLogGenerator(TestDataProperties properties) {
        this.lokiClient = WebClient.builder()
                .baseUrl(properties.lokiUrl())
                .build();
    }

    // traceIds: map of scenario name → 32-char hex trace ID from TraceGenerator
    public void pushAll(Map<String, String> traceIds) {
        pushNpe(traceIds.get("npe"));
        pushTimeout(traceIds.get("timeout"));
        pushDbError(traceIds.get("db_error"));
        pushMqError(traceIds.get("mq_error"));
        pushOom();
        log.info("All log scenarios pushed to Loki");
    }

    private void pushNpe(String traceId) {
        String shortTrace = shortTrace(traceId);
        String logLine = "2024-01-15 10:30:00.123 ERROR [collateral-service,%s,7a3f1b00] [http-nio-8080-exec-3] c.f.c.CollateralCalculator - java.lang.NullPointerException: null\n\tat com.firm.collateral.CollateralCalculator.calculateInitialMargin(CollateralCalculator.java:247)"
                .formatted(shortTrace);
        push("collateral-service", "COLL", logLine);
    }

    private void pushTimeout(String traceId) {
        String shortTrace = shortTrace(traceId);
        String logLine = "2024-01-15 11:00:00.456 ERROR [margin-call-service,%s,4d2e9c00] [http-nio-8080-exec-7] c.f.m.MarginCallService - java.net.SocketTimeoutException: Read timed out after 30000ms\n\tat com.firm.margin.client.CollateralServiceClient.getPositions(CollateralServiceClient.java:112) calling collateral-service /api/positions"
                .formatted(shortTrace);
        push("margin-call-service", "MRGN", logLine);
    }

    private void pushDbError(String traceId) {
        String shortTrace = shortTrace(traceId);
        // Cross-service: same error appears in 3 services
        String lineTemplate = " ERROR [%s,%s,1a2b3c00] [db-pool-1] c.f.r.Repository - com.sybase.jdbc4.jdbc.SybSQLException: JZ006: Caught IOException: Connection reset by peer";
        push("trade-enrichment-service", "TRADE",
            "2024-01-15 09:15:00.789" + lineTemplate.formatted("trade-enrichment-service", shortTrace));
        push("collateral-service", "COLL",
            "2024-01-15 09:15:05.123" + lineTemplate.formatted("collateral-service", shortTrace));
        push("risk-aggregator-service", "RISK",
            "2024-01-15 09:15:08.456" + lineTemplate.formatted("risk-aggregator-service", shortTrace));
    }

    private void pushMqError(String traceId) {
        String shortTrace = shortTrace(traceId);
        // Cross-service: consumer + producer both affected
        String lineTemplate = " ERROR [%s,%s,5e6f7a00] [mq-listener-1] c.f.m.MqConsumer - com.ibm.mq.MQException: MQRC_Q_FULL (reason=2053) queue=MARGIN.CALL.REQUEST.QUEUE";
        push("margin-event-consumer", "MRGN",
            "2024-01-15 08:45:00.321" + lineTemplate.formatted("margin-event-consumer", shortTrace));
        push("trade-settlement-service", "SETL",
            "2024-01-15 08:45:02.000" + lineTemplate.formatted("trade-settlement-service", shortTrace));
    }

    private void pushOom() {
        // No trace — JVM crash pre-empts MDC logging
        String logLine = "2024-01-15 07:30:00.001 ERROR [risk-aggregator-service] [GC-worker-2] c.f.r.PositionAggregator - java.lang.OutOfMemoryError: Java heap space\n\tat com.firm.risk.PositionAggregator.aggregate(PositionAggregator.java:891)";
        push("risk-aggregator-service", "RISK", logLine);
    }

    private void push(String service, String appcode, String logLine) {
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

    // Loki values need the log line as a JSON string literal
    private String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    // OTel generates 32-char trace IDs; Spring Boot MDC traditionally uses 16-char
    // Use the first 16 chars so log lines are recognisable
    private String shortTrace(String traceId) {
        return traceId != null && traceId.length() >= 16 ? traceId.substring(0, 16) : traceId;
    }
}
