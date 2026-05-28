package com.firm.investigation.config;

import org.springframework.ai.azure.openai.AzureOpenAiChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
public class LlmConfig {

    @Bean
    @Profile("ollama")
    public ChatClient ollamaChatClient(OllamaChatModel model) {
        // Temperature is configured in application-ollama.yml
        return ChatClient.builder(model).build();
    }

    @Bean
    @Profile("azure-openai")
    public ChatClient azureChatClient(AzureOpenAiChatModel model) {
        return ChatClient.builder(model).build();
    }
}
