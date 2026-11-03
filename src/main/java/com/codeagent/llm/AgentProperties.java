package com.codeagent.llm;

import com.codeagent.orchestrator.PlannerMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param maxSteps      실행 담당 하나가 할 일 하나에 쓸 수 있는 최대 LLM 호출 횟수 (계획 담당 한 회차도 같다)
 * @param workers       분석 작업 하나에서 동시에 도는 실행 담당 수
 * @param planner       요청에서 정하지 않았을 때 쓰는 계획 방식
 * @param plannerRounds LLM 계획 담당이 할 일을 정하는 최대 회차 (1회차 + 결과를 보고 보완하는 회차)
 */
@ConfigurationProperties("codeagent.agent")
public record AgentProperties(int maxSteps, int workers, PlannerMode planner, int plannerRounds) {

    public AgentProperties {
        if (workers < 1) {
            throw new IllegalArgumentException("codeagent.agent.workers는 1 이상이어야 함: " + workers);
        }
        if (planner == null) {
            planner = PlannerMode.LLM;
        }
        if (plannerRounds < 1) {
            plannerRounds = 3;
        }
    }
}
