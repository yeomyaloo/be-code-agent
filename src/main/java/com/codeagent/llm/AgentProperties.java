package com.codeagent.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param maxSteps 실행 담당 하나가 할 일 하나에 쓸 수 있는 최대 LLM 호출 횟수
 */
@ConfigurationProperties("codeagent.agent")
public record AgentProperties(int maxSteps) {
}
