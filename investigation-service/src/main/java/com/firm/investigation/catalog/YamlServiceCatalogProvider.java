package com.firm.investigation.catalog;

import com.firm.investigation.config.InvestigationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * YAML-backed catalog reading from investigation.service-catalog in application.yml.
 *
 * <pre>
 * investigation:
 *   service-catalog:
 *     AT4278:
 *       - booking-service
 *       - ledger-service
 *       - ...
 * </pre>
 *
 * Swap-out point: when a real CMDB/service registry is available, write a
 * DbServiceCatalogProvider implementing the same interface and replace the
 * @Component scan target. Nothing else changes.
 */
@Component
public class YamlServiceCatalogProvider implements ServiceCatalogProvider {

    private static final Logger log = LoggerFactory.getLogger(YamlServiceCatalogProvider.class);

    private final Map<String, Set<String>> catalogByAppcode;

    public YamlServiceCatalogProvider(InvestigationProperties properties) {
        Map<String, List<String>> raw = properties.serviceCatalog();
        this.catalogByAppcode = raw == null ? Map.of() : raw.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        e -> Set.copyOf(e.getValue())));
        log.info("Loaded service catalog: {} appcodes, {} total services",
                catalogByAppcode.size(),
                catalogByAppcode.values().stream().mapToInt(Set::size).sum());
    }

    @Override
    public List<String> servicesFor(String appcode) {
        Set<String> services = catalogByAppcode.get(appcode);
        return services == null ? List.of() : List.copyOf(services);
    }

    @Override
    public boolean isKnownService(String appcode, String serviceName) {
        Set<String> services = catalogByAppcode.get(appcode);
        return services != null && services.contains(serviceName);
    }

    @Override
    public List<String> validate(String appcode, List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        Set<String> known = catalogByAppcode.getOrDefault(appcode, Set.of());
        if (known.isEmpty()) {
            // No catalog for this appcode → can't validate, accept as-is rather than dropping everything.
            log.warn("No service catalog for appcode {}, accepting all candidates", appcode);
            return List.copyOf(candidates);
        }
        List<String> kept = candidates.stream().filter(known::contains).toList();
        if (kept.size() != candidates.size()) {
            List<String> dropped = candidates.stream().filter(s -> !known.contains(s)).toList();
            log.warn("Dropped {} hallucinated services for appcode {}: {}",
                    dropped.size(), appcode, dropped);
        }
        return kept;
    }
}
