package com.firm.investigation.catalog;

import java.util.List;

public interface ServiceCatalogProvider {

    /** Canonical service names for a given appcode. Empty list if appcode is unknown. */
    List<String> servicesFor(String appcode);

    /** True if the service name exists in the catalog for the given appcode. */
    boolean isKnownService(String appcode, String serviceName);

    /**
     * Filter a candidate list to only services present in the catalog for this
     * appcode. Used to drop LLM-hallucinated service names downstream.
     */
    List<String> validate(String appcode, List<String> candidates);
}
