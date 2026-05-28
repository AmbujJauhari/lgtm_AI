package com.firm.testdata;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Component
public class TraceGenerator {

    private static final Logger log = LoggerFactory.getLogger(TraceGenerator.class);
    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");
    private static final AttributeKey<String> APPCODE = AttributeKey.stringKey("appcode");

    private final String otlpEndpoint;

    public TraceGenerator(TestDataProperties properties) {
        this.otlpEndpoint = properties.otlpEndpoint() + "/v1/traces";
    }

    // Returns map: scenario name → trace ID (32-char hex, embeddable in log lines)
    public Map<String, String> generateAll() {
        Map<String, String> traceIds = new HashMap<>();
        traceIds.put("npe", npeTrace());
        traceIds.put("timeout", timeoutTrace());
        traceIds.put("db_error", dbErrorTrace());
        traceIds.put("mq_error", mqErrorTrace());
        // OOM has no trace — JVM crash before MDC can log
        log.info("Generated traces: {}", traceIds);
        return traceIds;
    }

    // Single span — collateral-service only, NullPointerException
    private String npeTrace() {
        try (var sdk = buildSdk("collateral-service", "COLL")) {
            Tracer tracer = sdk.getTracer("test-data-generator");
            Span span = tracer.spanBuilder("CollateralCalculator.calculateInitialMargin")
                    .setAttribute("exception.type", "java.lang.NullPointerException")
                    .setAttribute("exception.message", "null")
                    .startSpan();
            span.setStatus(StatusCode.ERROR, "NullPointerException");
            span.end();
            String traceId = span.getSpanContext().getTraceId();
            sdk.getSdkTracerProvider().forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);
            log.info("NPE trace pushed: {}", traceId);
            return traceId;
        }
    }

    // 4-service call chain — margin-call → collateral → trade-enrichment → position
    private String timeoutTrace() {
        OtlpHttpSpanExporter exporter = buildExporter();
        Map<String, SdkTracerProvider> providers = new HashMap<>();
        try {
            String[] services = {"margin-call-service", "collateral-service", "trade-enrichment-service", "position-service"};
            String[] appcodes = {"MRGN", "COLL", "TRADE", "POS"};
            for (int i = 0; i < services.length; i++) {
                providers.put(services[i], buildProvider(services[i], appcodes[i], exporter));
            }

            Tracer marginTracer = providers.get("margin-call-service").get("test-data-generator");
            Tracer collateralTracer = providers.get("collateral-service").get("test-data-generator");
            Tracer tradeTracer = providers.get("trade-enrichment-service").get("test-data-generator");
            Tracer positionTracer = providers.get("position-service").get("test-data-generator");

            String traceId;
            Span rootSpan = marginTracer.spanBuilder("MarginCallService.processMarginCall").startSpan();
            traceId = rootSpan.getSpanContext().getTraceId();
            rootSpan.setStatus(StatusCode.ERROR, "SocketTimeoutException");
            rootSpan.setAttribute("exception.type", "java.net.SocketTimeoutException");

            try (Scope rootScope = rootSpan.makeCurrent()) {
                Span collSpan = collateralTracer.spanBuilder("CollateralServiceClient.getPositions")
                        .setParent(Context.current()).startSpan();
                collSpan.setStatus(StatusCode.ERROR, "read timeout");
                try (Scope collScope = collSpan.makeCurrent()) {
                    Span tradeSpan = tradeTracer.spanBuilder("TradeRepository.findByPortfolio")
                            .setParent(Context.current()).startSpan();
                    try (Scope tradeScope = tradeSpan.makeCurrent()) {
                        Span posSpan = positionTracer.spanBuilder("PositionService.calculateNet")
                                .setParent(Context.current()).startSpan();
                        posSpan.end();
                    }
                    tradeSpan.end();
                }
                collSpan.end();
            }
            rootSpan.end();

            providers.values().forEach(p -> p.forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS));
            log.info("Timeout trace pushed: {}", traceId);
            return traceId;
        } finally {
            providers.values().forEach(SdkTracerProvider::close);
            exporter.close();
        }
    }

    // Single span — trade-enrichment-service, Sybase connection error
    private String dbErrorTrace() {
        try (var sdk = buildSdk("trade-enrichment-service", "TRADE")) {
            Tracer tracer = sdk.getTracer("test-data-generator");
            Span span = tracer.spanBuilder("TradeRepository.findByTradeId")
                    .setAttribute("exception.type", "com.sybase.jdbc4.jdbc.SybSQLException")
                    .setAttribute("exception.message", "JZ006: Caught IOException: Connection reset by peer")
                    .startSpan();
            span.setStatus(StatusCode.ERROR, "SybSQLException");
            span.end();
            String traceId = span.getSpanContext().getTraceId();
            sdk.getSdkTracerProvider().forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);
            log.info("DB error trace pushed: {}", traceId);
            return traceId;
        }
    }

    // 3-service MQ chain — margin-event-consumer → margin-call → settlement
    private String mqErrorTrace() {
        OtlpHttpSpanExporter exporter = buildExporter();
        Map<String, SdkTracerProvider> providers = new HashMap<>();
        try {
            providers.put("margin-event-consumer", buildProvider("margin-event-consumer", "MRGN", exporter));
            providers.put("margin-call-service", buildProvider("margin-call-service", "MRGN", exporter));
            providers.put("settlement-service", buildProvider("settlement-service", "SETL", exporter));

            Tracer consumerTracer = providers.get("margin-event-consumer").get("test-data-generator");
            Tracer marginTracer = providers.get("margin-call-service").get("test-data-generator");
            Tracer settlementTracer = providers.get("settlement-service").get("test-data-generator");

            String traceId;
            Span rootSpan = consumerTracer.spanBuilder("MarginEventConsumer.onMessage").startSpan();
            traceId = rootSpan.getSpanContext().getTraceId();
            rootSpan.setStatus(StatusCode.ERROR, "MQRC_Q_FULL");
            rootSpan.setAttribute("exception.type", "com.ibm.mq.MQException");

            try (Scope rootScope = rootSpan.makeCurrent()) {
                Span marginSpan = marginTracer.spanBuilder("MarginCallService.create")
                        .setParent(Context.current()).startSpan();
                try (Scope marginScope = marginSpan.makeCurrent()) {
                    Span settlSpan = settlementTracer.spanBuilder("SettlementService.schedule")
                            .setParent(Context.current()).startSpan();
                    settlSpan.end();
                }
                marginSpan.end();
            }
            rootSpan.end();

            providers.values().forEach(p -> p.forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS));
            log.info("MQ error trace pushed: {}", traceId);
            return traceId;
        } finally {
            providers.values().forEach(SdkTracerProvider::close);
            exporter.close();
        }
    }

    private OpenTelemetrySdk buildSdk(String serviceName, String appcode) {
        OtlpHttpSpanExporter exporter = buildExporter();
        SdkTracerProvider provider = buildProvider(serviceName, appcode, exporter);
        return OpenTelemetrySdk.builder().setTracerProvider(provider).build();
    }

    private OtlpHttpSpanExporter buildExporter() {
        return OtlpHttpSpanExporter.builder()
                .setEndpoint(otlpEndpoint)
                .setTimeout(Duration.ofSeconds(10))
                .build();
    }

    private SdkTracerProvider buildProvider(String serviceName, String appcode, OtlpHttpSpanExporter exporter) {
        Resource resource = Resource.getDefault().merge(Resource.create(
                Attributes.of(SERVICE_NAME, serviceName, APPCODE, appcode)));
        return SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
    }
}
