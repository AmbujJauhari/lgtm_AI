package com.firm.testdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Map;

@Component
public class TestDataRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TestDataRunner.class);

    private final TraceGenerator traceGenerator;
    private final LokiLogGenerator lokiLogGenerator;
    private final MetricsGenerator metricsGenerator;
    private final AtlasIncidentsExporter atlasIncidentsExporter;
    private final TestDataProperties properties;

    public TestDataRunner(TraceGenerator traceGenerator,
                          LokiLogGenerator lokiLogGenerator,
                          MetricsGenerator metricsGenerator,
                          AtlasIncidentsExporter atlasIncidentsExporter,
                          TestDataProperties properties) {
        this.traceGenerator = traceGenerator;
        this.lokiLogGenerator = lokiLogGenerator;
        this.metricsGenerator = metricsGenerator;
        this.atlasIncidentsExporter = atlasIncidentsExporter;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("Exporting synthetic Atlas incidents for indexer...");
        atlasIncidentsExporter.export();

        log.info("Waiting for LGTM stack to be ready...");
        waitForLoki();

        log.info("Pushing test scenarios — 5 error types across 9 services");
        Map<String, String> traceIds = traceGenerator.generateAll();
        lokiLogGenerator.pushAll(traceIds);

        log.info("Pushing baseline metrics for panel data (~30s)...");
        metricsGenerator.pushAll();

        log.info("""

                ╔══════════════════════════════════════════════════════════════════╗
                ║  Test data ready — open Grafana and click a log line to start   ║
                ║  Grafana: http://localhost:3000                                  ║
                ║  Credentials: admin / admin                                     ║
                ╚══════════════════════════════════════════════════════════════════╝
                """);
    }

    private void waitForLoki() {
        WebClient healthClient = WebClient.builder()
                .baseUrl(properties.lokiUrl())
                .build();

        healthClient.get()
                .uri("/ready")
                .retrieve()
                .bodyToMono(String.class)
                .retryWhen(Retry.fixedDelay(20, Duration.ofSeconds(5))
                        .doBeforeRetry(signal -> log.info("Loki not ready yet, retrying... (attempt {})",
                                signal.totalRetries() + 1)))
                .block();

        log.info("LGTM stack is ready");
    }
}
