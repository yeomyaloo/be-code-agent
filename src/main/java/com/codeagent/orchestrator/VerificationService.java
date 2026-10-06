package com.codeagent.orchestrator;

import com.codeagent.agent.AgentLoop;
import com.codeagent.agent.AgentLoop.AgentRequest;
import com.codeagent.agent.AgentLoop.AgentRun;
import com.codeagent.agent.VerifierPrompts;
import com.codeagent.agent.tool.AgentTool;
import com.codeagent.agent.tool.SubmitVerdictTool;
import com.codeagent.agent.tool.ToolContext;
import com.codeagent.analysisgraph.AnalysisGraphService;
import com.codeagent.analysisgraph.domain.AnalysisEdge;
import com.codeagent.analysisgraph.domain.AnalysisEdgeKind;
import com.codeagent.analysisgraph.domain.AnalysisEdgeRepository;
import com.codeagent.analysisgraph.domain.AnalysisNode;
import com.codeagent.analysisgraph.domain.AnalysisNodeKind;
import com.codeagent.analysisgraph.domain.AnalysisNodeRepository;
import com.codeagent.analysisgraph.domain.AnalysisNodeStatus;
import com.codeagent.llm.AgentProperties;
import com.codeagent.llm.ModelTier;
import com.codeagent.project.AnalysisJob;
import com.codeagent.project.AnalysisJobRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 검증 담당. 검증 대기(OPEN) 발견마다 별도의 에이전트가 반대 입장에서 다시 확인해
 * CONFIRMED / REJECTED / UNCERTAIN 으로 판정한다.
 */
@Slf4j
@Service
public class VerificationService {

    static final String VERIFIER_ID = "verifier-1";

    /** 검증 담당은 코드를 읽기만 하고, 기록은 submit_verdict 로만 한다 */
    private static final Set<String> VERIFIER_TOOLS = Set.of(
            "read_file", "grep_code", "find_code_nodes", "get_callers", "get_callees", "search_worker_traces",
            SubmitVerdictTool.NAME);

    private final AnalysisJobRepository jobRepository;
    private final AnalysisNodeRepository nodeRepository;
    private final AnalysisEdgeRepository edgeRepository;
    private final AnalysisGraphService analysisGraph;
    private final AgentLoop agentLoop;
    private final List<AgentTool> tools;
    private final AgentProperties agentProperties;

    public VerificationService(AnalysisJobRepository jobRepository, AnalysisNodeRepository nodeRepository,
                               AnalysisEdgeRepository edgeRepository, AnalysisGraphService analysisGraph,
                               AgentLoop agentLoop, List<AgentTool> allTools, AgentProperties agentProperties) {
        this.jobRepository = jobRepository;
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.analysisGraph = analysisGraph;
        this.agentLoop = agentLoop;
        this.tools = allTools.stream().filter(t -> VERIFIER_TOOLS.contains(t.name())).toList();
        this.agentProperties = agentProperties;
    }

    public record VerificationSummary(int verified, boolean budgetExhausted) {
    }

    /** 이 작업의 검증 대기 발견을 차례로 검증한다 */
    public VerificationSummary verifyOpenFindings(Long jobId, Long projectId, Path repoRoot) {
        List<AnalysisNode> findings = nodeRepository.findByJobIdAndKindAndStatusOrderById(
                jobId, AnalysisNodeKind.FINDING, AnalysisNodeStatus.OPEN);
        int verified = 0;
        for (AnalysisNode finding : findings) {
            AnalysisJob job = jobRepository.findById(jobId).orElseThrow();
            if (job.remainingTokens() == 0) {
                return new VerificationSummary(verified, true);
            }
            verify(job, finding, projectId, repoRoot);
            verified++;
        }
        return new VerificationSummary(verified, false);
    }

    private void verify(AnalysisJob job, AnalysisNode finding, Long projectId, Path repoRoot) {
        log.info("발견 #{} 검증 시작: {}", finding.getId(), finding.getTitle());
        List<AnalysisNode> facts = edgeRepository.findByDstIdAndKind(finding.getId(), AnalysisEdgeKind.PROVES).stream()
                .map(AnalysisEdge::getSrcId)
                .map(nodeRepository::findById)
                .flatMap(java.util.Optional::stream)
                .toList();
        String prompt = VerifierPrompts.finding(finding, analysisGraph.props(finding), facts);

        // 분석 기록은 발견 id 로 묶는다 (GET /traces?intentionId={발견 id})
        ToolContext context = new ToolContext(projectId, repoRoot, job.getId(), finding.getId(), VERIFIER_ID);
        AgentRun run = agentLoop.run(new AgentRequest(ModelTier.VERIFIER, VerifierPrompts.SYSTEM, prompt, tools,
                context, agentProperties.maxSteps(), job.remainingTokens()));

        AnalysisJob latest = jobRepository.findById(job.getId()).orElseThrow();
        latest.addUsage(run.inputTokens(), run.outputTokens());
        jobRepository.save(latest);

        // submit_verdict 없이 끝났으면 (단계·예산 초과, 오류, 제출 누락) 판단 불가로 남긴다
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("verdict", "uncertain");
        fallback.put("reasoning", run.finalText() == null || run.finalText().isBlank()
                ? "검증 담당이 판정을 제출하지 않음" : run.finalText());
        fallback.put("outcome", run.outcome().name());
        fallback.put("error", run.error());
        fallback.put("verifier", VERIFIER_ID);
        boolean fallbackRecorded = analysisGraph.recordVerdict(job.getId(), finding.getId(), AnalysisNodeStatus.UNCERTAIN, fallback);

        log.info("발견 #{} 검증 종료: {} ({}단계, 입력 {} / 출력 {} 토큰){}", finding.getId(), run.outcome(), run.steps(),
                run.inputTokens(), run.outputTokens(), fallbackRecorded ? " - 판정 미제출로 UNCERTAIN 처리" : "");
    }
}
