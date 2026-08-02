package com.bookmarkdrawer.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AnthropicConfig {

    @Bean
    public AnthropicClient anthropicClient(@Value("${ANTHROPIC_API_KEY}") String apiKey) {
        return AnthropicOkHttpClient.builder().apiKey(apiKey).build();
    }
}
