package com.firm.investigation.reason;

import com.firm.investigation.api.dto.InvestigationRequest;
import com.firm.investigation.catalog.ServiceCatalogProvider;
import com.firm.investigation.memory.AtlasMemoryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ReasonService {

    private static final Logger log = LoggerFactory.getLogger(ReasonService.class);

    private final ChatClient chatClient;
    private final ReasonPromptBuilder promptBuilder;
    private final ServiceCatalogProvider serviceCatalog;

    public ReasonService(ChatClient chatClient,
                         ReasonPromptBuilder promptBuilder,
                         ServiceCatalogProvider serviceCatalog) {
        this.chatClient = chatClient;
        this.promptBuilder = promptBuilder;
        this.serviceCatalog = serviceCatalog;
    }

    public ReasonResult reason(InvestigationRequest request, AtlasMemoryResult atlasMemory) {
        List<String> catalog = serviceCatalog.servicesFor(request.appcode());

        log.info("Reasoning for appcode={} originating={} atlasHits={}",
                request.appcode(), request.service(), atlasMemory.relatedIncidents().size());

        ReasonResult raw = chatClient.prompt()
                .system(promptBuilder.systemPrompt())
                .user(promptBuilder.userPrompt(request, atlasMemory, catalog))
                .call()
                .entity(ReasonResult.class);

        // Hard-validate LLM output against the catalog — drop any hallucinated service names.
        List<String> validatedServices = serviceCatalog.validate(request.appcode(), raw.likelyServices());
        List<String> validatedLokiServices = raw.lokiPlan() == null
                ? List.of()
                : serviceCatalog.validate(request.appcode(), raw.lokiPlan().services());

        ReasonResult.LokiPlan loki = raw.lokiPlan() == null ? ReasonResult.LokiPlan.skip()
                : new ReasonResult.LokiPlan(
                        raw.lokiPlan().query(),
                        raw.lokiPlan().pattern(),
                        validatedLokiServices,
                        raw.lokiPlan().timeWindowMinutes());

        ReasonResult.TempoPlan tempo = raw.tempoPlan() == null ? ReasonResult.TempoPlan.skip() : raw.tempoPlan();

        ReasonResult validated = new ReasonResult(
                raw.errorCategory(),
                raw.errorPattern(),
                raw.traceId(),
                validatedServices,
                raw.confidence(),
                tempo,
                loki,
                raw.reasoning()
        );

        log.info("Reasoned: category={} confidence={} services={} tempoPlan.query={} lokiPlan.query={}",
                validated.errorCategory(), validated.confidence(), validated.likelyServices(),
                validated.tempoPlan().query(), validated.lokiPlan().query());
        return validated;
    }
}
