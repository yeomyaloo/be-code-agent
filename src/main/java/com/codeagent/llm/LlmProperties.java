package com.codeagent.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 작업 종류별 모델 설정. 실행 담당은 Sonnet, 계획·검증처럼 어려운 판단은 Opus를 쓴다.
 */
@ConfigurationProperties("codeagent.llm")
public record LlmProperties(String apiKey, long maxTokens, boolean fallbacks, Tier worker, Tier planner, Tier verifier) {

    public record Tier(String model, String effort) {
    }

    public Tier tier(ModelTier tier) {
        return switch (tier) {
            case WORKER -> worker;
            case PLANNER -> planner;
            case VERIFIER -> verifier;
        };
    }
}
