package com.codeagent.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
@EnableConfigurationProperties({LlmProperties.class, AgentProperties.class})
public class LlmConfig {

    /**
     * codeagent.llm.api-key 로 인증한다. 비어 있으면 SDK 기본 방식(환경 변수 등)을 따른다.
     * 키가 없어도 앱은 뜨도록 처음 사용할 때 만든다.
     */
    @Bean(destroyMethod = "close")
    @Lazy
    public AnthropicClient anthropicClient(LlmProperties properties) {
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder().fromEnv();
        if (properties.apiKey() != null && !properties.apiKey().isBlank()) {
            builder.apiKey(properties.apiKey());
        }
        return builder.build();
    }
}
