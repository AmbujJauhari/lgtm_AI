package com.firm.testdata;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Pushes baseline Prometheus-compatible metrics into LGTM via OTLP so that the
 * panel queries on the generated investigation dashboards have data to show.
 *
 * <p>All metrics use:
 * <ul>
 *   <li>{@code service} attribute = service name</li>
 *   <li>{@code appcode} attribute = AT4278 (from {@link TestDataProperties})</li>
 *   <li>Names matching what panel templates query (e.g. {@code jvm_memory_used_bytes})</li>
 * </ul>
 *
 * <p>Generates ~30 seconds of data so {@code rate()} based panel queries
 * have enough samples to compute over a 5-minute window.
 */
@Component
public class MetricsGenerator {

    private static final Logger log = LoggerFactory.getLogger(MetricsGenerator.class);

    /** Services we push metrics for — superset of services referenced by our 5 trace+log scenarios. */
    private static final List<String> SERVICES = List.of(
            "collateral-service", "ledger-service", "booking-service", "position-service",
            "margin-call-service", "trade-enrichment-service", "risk-aggregator-service",
            "margin-event-consumer", "trade-settlement-service", "pricing-service",
            "reference-data-service", "settlement-service"
    );

    /** Heap base size per service (~MB), gives realistic-looking values when ±100MB jitter is added. */
    private static final Map<String, Long> HEAP_BASE = Map.ofEntries(
            Map.entry("collateral-service", 400_000_000L),
            Map.entry("ledger-service", 500_000_000L),
            Map.entry("booking-service", 350_000_000L),
            Map.entry("position-service", 450_000_000L),
            Map.entry("margin-call-service", 380_000_000L),
            Map.entry("trade-enrichment-service", 420_000_000L),
            Map.entry("risk-aggregator-service", 1_200_000_000L), // bigger — does heavy aggregation
            Map.entry("margin-event-consumer", 250_000_000L),
            Map.entry("trade-settlement-service", 300_000_000L),
            Map.entry("pricing-service", 600_000_000L),
            Map.entry("reference-data-service", 200_000_000L),
            Map.entry("settlement-service", 280_000_000L)
    );

    private final String otlpMetricsEndpoint;
    private final String appcode;

    public MetricsGenerator(TestDataProperties properties) {
        this.otlpMetricsEndpoint = properties.otlpEndpoint() + "/v1/metrics";
        this.appcode = properties.appcode();
    }

    public void pushAll() {
        log.info("Pushing baseline metrics for {} services (appcode={})", SERVICES.size(), appcode);

        OtlpHttpMetricExporter exporter = OtlpHttpMetricExporter.builder()
                .setEndpoint(otlpMetricsEndpoint)
                .setTimeout(Duration.ofSeconds(10))
                .build();

        SdkMeterProvider provider = SdkMeterProvider.builder()
                .registerMetricReader(PeriodicMetricReader.builder(exporter)
                        .setInterval(Duration.ofSeconds(3))
                        .build())
                .build();

        try {
            Meter meter = provider.get("test-data-generator");

            // ── Observable gauges (callback-based, sampled every 3s by the reader) ──
            registerJvmHeapGauge(meter);
            registerThreadPoolGauge(meter);
            registerHikariPoolGauge(meter);
            registerMqGauges(meter);

            // ── Histograms — record samples in a loop so rate() panels have data ──
            DoubleHistogram httpLatency = meter
                    .histogramBuilder("http_server_requests_seconds")
                    .setUnit("s")
                    .build();
            DoubleHistogram gcPause = meter
                    .histogramBuilder("jvm_gc_pause_seconds")
                    .setUnit("s")
                    .build();
            DoubleHistogram dbQueryLatency = meter
                    .histogramBuilder("spring_data_repository_invocations_seconds")
                    .setUnit("s")
                    .build();

            Random rng = new Random(42);
            int ticks = 10;  // 10 ticks × 3s = 30 seconds of metric data
            for (int tick = 0; tick < ticks; tick++) {
                for (String svc : SERVICES) {
                    // Mostly fast successful requests
                    for (int i = 0; i < 20; i++) {
                        httpLatency.record(0.020 + rng.nextDouble() * 0.180, attrs(svc, "method", "GET", "status", "200"));
                    }
                    // Some POSTs
                    for (int i = 0; i < 5; i++) {
                        httpLatency.record(0.050 + rng.nextDouble() * 0.300, attrs(svc, "method", "POST", "status", "200"));
                    }
                    // A handful of errors (drives error_rate panel)
                    if (rng.nextDouble() < 0.3) {
                        httpLatency.record(1.0 + rng.nextDouble() * 4.0, attrs(svc, "method", "GET", "status", "500"));
                    }
                    // GC pauses
                    gcPause.record(rng.nextDouble() * 0.05, attrs(svc, "action", "end of minor GC", "cause", "Allocation Failure"));
                    if (rng.nextDouble() < 0.1) {
                        gcPause.record(0.1 + rng.nextDouble() * 0.4, attrs(svc, "action", "end of major GC", "cause", "G1 Evacuation Pause"));
                    }
                    // DB query latency
                    for (int i = 0; i < 8; i++) {
                        dbQueryLatency.record(0.005 + rng.nextDouble() * 0.040, attrs(svc));
                    }
                }
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            provider.forceFlush().join(10, TimeUnit.SECONDS);
            log.info("Metrics push complete ({} services, ~{}s of data)", SERVICES.size(), ticks * 3);
        } finally {
            provider.close();
        }
    }

    private void registerJvmHeapGauge(Meter meter) {
        meter.gaugeBuilder("jvm_memory_used_bytes")
                .setUnit("By")
                .ofLongs()
                .buildWithCallback(measurement -> {
                    Random rng = new Random();
                    for (String svc : SERVICES) {
                        long base = HEAP_BASE.getOrDefault(svc, 300_000_000L);
                        long heap = base + (long) (rng.nextDouble() * 100_000_000);
                        long nonHeap = 80_000_000L + (long) (rng.nextDouble() * 30_000_000);
                        measurement.record(heap, attrs(svc, "area", "heap", "id", "G1 Eden Space"));
                        measurement.record(nonHeap, attrs(svc, "area", "nonheap", "id", "Metaspace"));
                    }
                });
    }

    private void registerThreadPoolGauge(Meter meter) {
        meter.gaugeBuilder("executor_active_threads")
                .ofLongs()
                .buildWithCallback(measurement -> {
                    Random rng = new Random();
                    for (String svc : SERVICES) {
                        long active = 5 + (long) (rng.nextDouble() * 40);
                        measurement.record(active, attrs(svc, "name", "applicationTaskExecutor"));
                    }
                });
    }

    private void registerHikariPoolGauge(Meter meter) {
        meter.gaugeBuilder("hikaricp_connections_active")
                .ofLongs()
                .buildWithCallback(measurement -> {
                    Random rng = new Random();
                    for (String svc : SERVICES) {
                        long active = 2 + (long) (rng.nextDouble() * 15);
                        measurement.record(active, attrs(svc, "pool", "HikariPool-1"));
                    }
                });
    }

    private void registerMqGauges(Meter meter) {
        // Realistic-ish steady-state for MQ-handling services
        meter.gaugeBuilder("ibm_mq_consumer_lag")
                .ofLongs()
                .buildWithCallback(measurement -> {
                    Random rng = new Random();
                    measurement.record(50 + (long) (rng.nextDouble() * 200), attrs("margin-event-consumer", "queue", "MARGIN.CALL.REQUEST.QUEUE"));
                    measurement.record(20 + (long) (rng.nextDouble() * 80), attrs("trade-settlement-service", "queue", "SETTLEMENT.INSTRUCTIONS"));
                });
        meter.gaugeBuilder("ibm_mq_dead_letter_queue_depth")
                .ofLongs()
                .buildWithCallback(measurement -> {
                    Random rng = new Random();
                    measurement.record((long) (rng.nextDouble() * 5), attrs("margin-event-consumer", "queue", "MARGIN.DLQ"));
                });
        meter.gaugeBuilder("ibm_mq_queue_depth")
                .ofLongs()
                .buildWithCallback(measurement -> {
                    Random rng = new Random();
                    measurement.record(100 + (long) (rng.nextDouble() * 500), attrs("margin-event-consumer", "queue", "MARGIN.CALL.REQUEST.QUEUE"));
                    measurement.record(50 + (long) (rng.nextDouble() * 200), attrs("trade-settlement-service", "queue", "SETTLEMENT.INSTRUCTIONS"));
                });
    }

    /** Build attributes with service + appcode + arbitrary extra key/value pairs. */
    private Attributes attrs(String svc, String... extraKvPairs) {
        AttributesBuilder b = Attributes.builder()
                .put("service", svc)
                .put("appcode", appcode);
        for (int i = 0; i + 1 < extraKvPairs.length; i += 2) {
            b.put(extraKvPairs[i], extraKvPairs[i + 1]);
        }
        return b.build();
    }
}
