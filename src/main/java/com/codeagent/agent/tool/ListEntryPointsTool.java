package com.codeagent.agent.tool;

import com.codeagent.agent.WorkerPrompts;
import com.codeagent.codegraph.query.CodeGraphQuery;
import com.codeagent.codegraph.query.CodeGraphQuery.EntryPointView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 계획 담당 전용. HTTP 진입점 전체 목록.
 */
@Component
@RequiredArgsConstructor
public class ListEntryPointsTool implements AgentTool {

    public static final String NAME = "list_entry_points";

    private final CodeGraphQuery codeGraphQuery;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "HTTP 진입점 전체 목록 (메서드, 경로, 처리 메서드, 위치): Spring MVC 컨트롤러, FastAPI 라우트(라우터 prefix 포함), Flask·Django, "
                + "Node.js(Express·Koa·Fastify 라우터와 app.use 마운트 경로, NestJS @Controller, Next.js API 라우트), Go(net/http·gin·echo·chi·gorilla/mux·fiber 그룹·마운트 경로), PHP(Laravel Route·Symfony #[Route]·Slim·CodeIgniter, $_GET 을 읽는 .php 스크립트), Ruby(Rails routes.rb·Sinatra). "
                + "Flask·Django·Node.js·Go·PHP·Ruby 는 인증 단서(login_required, 라우트·마운트 미들웨어, @UseGuards 등)를 보여준다. 단서가 없으면 전역 설정(app.use(auth) 등)을 확인한다. "
                + "FastAPI 는 Depends(...) 의존 함수(인증 확인 등)도 보여준다. Depends 가 없는 라우트는 인증 누락 후보다. "
                + "위험 지점까지 이어지지 않는 진입점도 나온다. 경로 변수({id})로 데이터를 조회·수정·삭제하는 API 는 "
                + "소유자 확인 누락(IDOR, CWE-639) 같은 권한 문제를 점검할 후보다.";
    }

    @Override
    public Map<String, Map<String, Object>> properties() {
        return Map.of();
    }

    @Override
    public List<String> required() {
        return List.of();
    }

    static String dependencies(EntryPointView e) {
        if (e.guards() != null) {
            // Flask·Django: 비어 있어도 전역 설정(미들웨어, DEFAULT_PERMISSION_CLASSES)이 막을 수 있다
            return e.guards().isEmpty() ? " · 인증 단서 없음(전역 설정은 따로 확인)" : " · 인증 단서: " + String.join(", ", e.guards());
        }
        if (e.dependencies() == null) {
            return "";
        }
        return e.dependencies().isEmpty() ? " · Depends 없음"
                : " · Depends: " + e.dependencies().stream().map(WorkerPrompts::shortName).collect(Collectors.joining(", "));
    }

    @Override
    public String execute(ToolInput input, ToolContext context) {
        List<EntryPointView> entries = codeGraphQuery.entryPoints(context.projectId());
        if (entries.isEmpty()) {
            return "진입점 없음";
        }
        return "진입점 " + entries.size() + "개\n" + entries.stream()
                .map(e -> "- " + e.httpMethod() + " " + e.path() + " (노드 " + e.id() + ") · "
                        + WorkerPrompts.shortName(e.handler()) + " · " + e.filePath() + ":" + e.line() + dependencies(e))
                .collect(Collectors.joining("\n"));
    }
}
