package com.firm.investigation.panel;

import com.firm.investigation.graph.InvestigationState;
import com.firm.investigation.llm.PanelSelectionPromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class PanelSelectionService {

    private static final Logger log = LoggerFactory.getLogger(PanelSelectionService.class);

    private final ChatClient chatClient;
    private final PanelSelectionPromptBuilder promptBuilder;

    public PanelSelectionService(ChatClient chatClient, PanelSelectionPromptBuilder promptBuilder) {
        this.chatClient = chatClient;
        this.promptBuilder = promptBuilder;
    }

    public PanelSelectionResult selectPanels(InvestigationState state) {
        String availablePanels = state.availablePanels();
        String errorCategory = state.reason() != null ? state.reason().errorCategory() : "UNKNOWN";
        log.info("Selecting panels for errorCategory={} components={}",
                errorCategory, state.mergedComponents());

        PanelSelectionResult result = chatClient.prompt()
                .system(promptBuilder.systemPrompt())
                .user(promptBuilder.userPrompt(state, availablePanels))
                .call()
                .entity(PanelSelectionResult.class);

        log.info("Panel selection: title='{}' panelCount={}", result.dashboardTitle(),
                result.selectedPanels() != null ? result.selectedPanels().size() : 0);
        return result;
    }
}
