package com.firm.investigation.api;

import com.firm.investigation.api.dto.InvestigationRequest;
import com.firm.investigation.component.PanelServiceClient;
import com.firm.investigation.config.LangGraphConfig;
import com.firm.investigation.graph.InvestigationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1")
public class InvestigationController {

    private static final Logger log = LoggerFactory.getLogger(InvestigationController.class);

    // Spring Boot MDC pattern: [service-name,traceId,spanId]
    private static final Pattern MDC_PATTERN = Pattern.compile("\\[([a-zA-Z][\\w.-]+),[\\w]+,[\\w]+\\]");
    // ISO-8601 timestamp at the start of a log line: 2024-01-15 10:30:00.123 or 2024-01-15T10:30:00Z
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?)(?:Z|[+\\-]\\d{2}:?\\d{2})?");

    private final LangGraphConfig langGraphConfig;
    private final PanelServiceClient panelServiceClient;
    private final String defaultAppcode;

    public InvestigationController(LangGraphConfig langGraphConfig, PanelServiceClient panelServiceClient,
                                   @Value("${investigation.default-appcode:AT4278}") String defaultAppcode) {
        this.langGraphConfig = langGraphConfig;
        this.panelServiceClient = panelServiceClient;
        this.defaultAppcode = defaultAppcode;
    }

    /**
     * Investigation entry point.
     *
     * <p>The full set of query params are optional because Grafana's Loki "derivedFields"
     * mechanism can only pass the captured log line — it can't pass stream labels into the
     * URL. Fallback derivation:
     * <ul>
     *   <li>{@code service}: parsed from Spring Boot MDC pattern {@code [service,traceId,spanId]};
     *       falls back to "unknown"</li>
     *   <li>{@code appcode}: defaults to {@code investigation.default-appcode} (AT4278)</li>
     *   <li>{@code timestamp}: parsed from the start of the log line; falls back to now</li>
     * </ul>
     */
    @GetMapping("/investigate")
    public Mono<ResponseEntity<?>> investigate(
            @RequestParam String logLine,
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String appcode,
            @RequestParam(required = false) String timestamp,
            @RequestParam(required = false) String format) {

        String resolvedService = (service == null || service.isBlank()) ? extractService(logLine) : service;
        String resolvedAppcode = (appcode == null || appcode.isBlank()) ? defaultAppcode : appcode;
        String resolvedTimestamp = (timestamp == null || timestamp.isBlank()) ? extractTimestamp(logLine) : timestamp;
        boolean jsonResponse = "json".equalsIgnoreCase(format);

        InvestigationRequest request = new InvestigationRequest(
                logLine, resolvedService, resolvedAppcode, resolvedTimestamp);

        return Mono.<ResponseEntity<?>>fromCallable(() -> {
            InvestigationState initial = InvestigationState.initial(request)
                    .withAvailablePanels(panelServiceClient.getDescriptions());
            InvestigationState result = langGraphConfig.investigationGraph().invoke(initial);
            String url = result.dashboardUrl();
            if (url == null || url.isBlank()) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of(
                                "error", "Investigation did not produce a dashboard URL",
                                "appcode", resolvedAppcode,
                                "service", resolvedService));
            }
            if (jsonResponse) {
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("dashboardUrl", url));
            }
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(url))
                    .build();
        })
        .onErrorResume(throwable -> {
            log.error("Investigation failed for service={} appcode={}: {}",
                    resolvedService, resolvedAppcode, throwable.getMessage(), throwable);
            return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "error", "AI investigation failed",
                            "stage", classifyStage(throwable),
                            "message", String.valueOf(throwable.getMessage()),
                            "appcode", resolvedAppcode,
                            "service", resolvedService)));
        })
        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    static String extractService(String logLine) {
        if (logLine == null) return "unknown";
        Matcher m = MDC_PATTERN.matcher(logLine);
        return m.find() ? m.group(1) : "unknown";
    }

    static String extractTimestamp(String logLine) {
        if (logLine != null) {
            Matcher m = TIMESTAMP_PATTERN.matcher(logLine);
            if (m.find()) {
                String ts = m.group(1).replace(' ', 'T').replace(',', '.');
                return ts + "Z";
            }
        }
        return Instant.now().toString();
    }

    /** Best-effort stage classification from the stack trace top frame. */
    private static String classifyStage(Throwable t) {
        StackTraceElement[] trace = t.getStackTrace();
        for (StackTraceElement frame : trace) {
            String cls = frame.getClassName();
            if (cls.contains("ReasonNode") || cls.contains("ReasonService")) return "reason";
            if (cls.contains("PanelSelectionNode") || cls.contains("PanelSelectionService")) return "panel-selection";
            if (cls.contains("DashboardAssembly")) return "dashboard-assembly";
            if (cls.contains("Publish") || cls.contains("GrafanaPublish")) return "publish";
        }
        return "unknown";
    }
}
