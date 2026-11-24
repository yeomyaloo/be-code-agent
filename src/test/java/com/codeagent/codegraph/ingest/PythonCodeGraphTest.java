package com.codeagent.codegraph.ingest;

import com.codeagent.agent.tool.GrepCodeTool;
import com.codeagent.agent.tool.ListEntryPointsTool;
import com.codeagent.agent.tool.ListSinkPathsTool;
import com.codeagent.agent.tool.ToolContext;
import com.codeagent.agent.tool.ToolInput;
import com.codeagent.codegraph.ingest.CodeGraphIndexer;
import com.codeagent.codegraph.ingest.CodeGraphIndexer.IndexResult;
import com.codeagent.codegraph.query.CodeGraphQuery;
import com.codeagent.codegraph.query.CodeGraphQuery.EntryPointView;
import com.codeagent.codegraph.query.CodeGraphQuery.SinkPathView;
import com.codeagent.project.Project;
import com.codeagent.project.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Python(FastAPI) 저장소의 코드 그래프가 DB 저장 → 조회(재귀 CTE) → 에이전트 도구까지 Java 와 같은 형식으로 흐르는지 확인한다.
 * 로컬 PostgreSQL 이 필요하다. API 키는 필요 없다.
 */
@SpringBootTest
class PythonCodeGraphTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/fastapi-app").toAbsolutePath();

    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private CodeGraphIndexer indexer;
    @Autowired
    private CodeGraphQuery query;
    @Autowired
    private ListEntryPointsTool listEntryPoints;
    @Autowired
    private ListSinkPathsTool listSinkPaths;
    @Autowired
    private GrepCodeTool grepCode;
    @Autowired
    private JsonMapper jsonMapper;

    private Project project;
    private IndexResult result;

    @BeforeEach
    void setUp() {
        // 등록할 때는 언어를 모르므로 기본값(java)으로 들어간다
        project = projectRepository.save(new Project("fastapi-app", FIXTURE.toString(), "java"));
        result = indexer.index(project.getId());
    }

    @Test
    void 언어를_판별해_프로젝트에_기록한다() {
        assertThat(result.languages()).containsExactly("python");
        assertThat(result.entryPoints()).isEqualTo(12);
        assertThat(result.sinks()).isEqualTo(12);
        assertThat(projectRepository.findById(project.getId()).orElseThrow().getLanguage()).isEqualTo("python");
        assertThat(query.languages(project.getId())).containsExactly("python");
        assertThat(CodeGraphIndexer.projectLanguage(java.util.Set.of("javascript"))).isEqualTo("javascript");
    }

    @Test
    void 진입점의_Depends_를_REST_응답에_싣는다() {
        EntryPointView nmap = query.entryPoints(project.getId()).stream()
                .filter(e -> e.path().equals("/api/scan/nmap")).findFirst().orElseThrow();
        assertThat(nmap.httpMethod()).isEqualTo("POST");
        assertThat(nmap.handler()).isEqualTo("app.routers.scan.run_nmap");
        assertThat(nmap.dependencies()).containsExactly("app.deps.get_current_user");
        assertThat(jsonMapper.writeValueAsString(nmap)).contains("\"dependencies\":[\"app.deps.get_current_user\"]");
    }

    @Test
    void 재귀_CTE_경로_탐색이_모듈을_건너_위험_지점까지_간다() {
        List<SinkPathView> paths = query.sinkPaths(project.getId(), 12);
        assertThat(paths).anySatisfy(p -> {
            assertThat(p.entryPoint().path()).isEqualTo("/api/scan/nmap");
            assertThat(p.sink().api()).isEqualTo("asyncio.create_subprocess_exec");
            assertThat(p.sink().category()).isEqualTo("COMMAND");
            assertThat(p.path()).extracting(n -> n.qualifiedName())
                    .contains("app.routers.scan.run_nmap", "app.services.scanner.run_nmap");
        });
        assertThat(paths).anySatisfy(p -> assertThat(p.sink().api()).isEqualTo("cursor.execute"));
    }

    @Test
    void 에이전트_도구가_파이썬_그래프를_그대로_보여준다() {
        ToolContext context = new ToolContext(project.getId(), FIXTURE, null, null, "test");

        String entries = listEntryPoints.execute(new ToolInput(Map.of()), context);
        assertThat(entries).contains("POST /api/scan/nmap", "scan.run_nmap", "Depends: deps.get_current_user");
        assertThat(entries).contains("GET /about", "Depends 없음");

        String paths = listSinkPaths.execute(new ToolInput(Map.of()), context);
        assertThat(paths).contains("[COMMAND CWE-78] asyncio.create_subprocess_exec", "scan.run_nmap › scanner.run_nmap");

        // 기본 검색 대상이 **.py 로 바뀐다
        String grep = grepCode.execute(new ToolInput(Map.of("pattern", "shell=True")), context);
        assertThat(grep).contains("app/routers/scan.py:");
    }
}
