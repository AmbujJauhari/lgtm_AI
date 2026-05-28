package com.firm.investigation.config;

import com.firm.investigation.mcp.InvestigationMcpService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class McpConfig {

    // @Lazy breaks the circular dependency:
    // toolCallbackResolver → investigationTools → InvestigationMcpService → LangGraphConfig
    //   → TriageService → ChatClient → AzureOpenAiChatModel → toolCallingManager → toolCallbackResolver
    @Bean
    public ToolCallbackProvider investigationTools(@Lazy InvestigationMcpService mcpService) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(mcpService)
                .build();
    }
}
