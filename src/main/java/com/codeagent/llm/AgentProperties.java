package com.codeagent.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param maxSteps 실행 담당 하나가 할 일 하나에 쓸 수 있는 최대 LLM 호출 횟수
 * @param workers  분석 작업 하나에서 동시에 도는 실행 담당 수
 */
@ConfigurationProperties("codeagent.agent")
public record AgentProperties(int maxSteps, int workers) {

    public AgentProperties {
        if (workers < 1) {
            throw new IllegalArgumentException("codeagent.agent.workers는 1 이상이어야 함: " + workers);
        }
    }
}
