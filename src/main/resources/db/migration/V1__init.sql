-- 분석 대상 저장소
create table project (
    id          bigserial primary key,
    name        text        not null,
    repo_path   text        not null,
    language    varchar(20) not null,
    created_at  timestamptz not null default now()
);

-- 분석 작업 (분석 그래프는 작업 단위로 새로 만듦)
create table analysis_job (
    id             bigserial primary key,
    project_id     bigint      not null references project,
    status         varchar(20) not null, -- PENDING, RUNNING, DONE, STOPPED
    budget_tokens  bigint,
    used_tokens    bigint      not null default 0,
    started_at     timestamptz,
    finished_at    timestamptz
);

-- 코드 그래프: 프로젝트 단위로 계속 유지
create table code_node (
    id              bigserial primary key,
    project_id      bigint      not null references project,
    kind            varchar(20) not null, -- MODULE, FILE, CLASS, METHOD, ENTRY_POINT, SINK
    qualified_name  text        not null,
    file_path       text,
    start_line      int,
    end_line        int,
    props           jsonb       not null default '{}',
    unique (project_id, kind, qualified_name)
);

create table code_edge (
    id          bigserial primary key,
    project_id  bigint      not null references project,
    src_id      bigint      not null references code_node,
    dst_id      bigint      not null references code_node,
    kind        varchar(20) not null, -- CONTAINS, CALLS, ROUTES_TO
    unique (src_id, dst_id, kind)
);
create index idx_code_edge_dst on code_edge (dst_id, kind);

-- 분석 그래프: 목표, 할 일, 사실, 발견, 힌트
create table analysis_node (
    id          bigserial primary key,
    job_id      bigint      not null references analysis_job,
    kind        varchar(20) not null, -- GOAL, INTENTION, FACT, FINDING, HINT
    status      varchar(20) not null, -- OPEN, CLAIMED, DONE, CONFIRMED, REJECTED
    title       text        not null,
    body        text,
    props       jsonb       not null default '{}',
    worker_id   varchar(40),
    created_at  timestamptz not null default now()
);
create index idx_analysis_node_job_kind_status on analysis_node (job_id, kind, status);

create table analysis_edge (
    id      bigserial primary key,
    job_id  bigint      not null references analysis_job,
    src_id  bigint      not null references analysis_node,
    dst_id  bigint      not null references analysis_node,
    kind    varchar(20) not null -- SPAWNS, DERIVED_FROM, YIELDS, PROVES, DEPENDS_ON
);
create index idx_analysis_edge_src on analysis_edge (src_id, kind);

-- 연결점: 분석 노드와 코드 노드를 이음
create table anchor (
    analysis_node_id  bigint      not null references analysis_node,
    code_node_id      bigint      not null references code_node,
    role              varchar(20) not null, -- EXAMINED, SOURCE, SINK, LOCATED_AT
    primary key (analysis_node_id, code_node_id, role)
);
create index idx_anchor_code_node on anchor (code_node_id);

-- 실행 담당 분석 기록 (search_worker_traces 용)
create table worker_trace (
    id            bigserial primary key,
    job_id        bigint      not null references analysis_job,
    intention_id  bigint      references analysis_node,
    worker_id     varchar(40),
    step          int         not null,
    role          varchar(20) not null, -- ASSISTANT, TOOL_CALL, TOOL_RESULT
    tool_name     text,
    content       text,
    created_at    timestamptz not null default now()
);
create index idx_worker_trace_content on worker_trace using gin (to_tsvector('simple', coalesce(content, '')));
