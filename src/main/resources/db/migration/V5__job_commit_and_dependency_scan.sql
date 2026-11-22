-- 분석 작업이 어느 커밋을 분석했는지 (시작할 때의 커밋. 모르면 null)
alter table analysis_job add column commit_sha varchar(40);

-- 라이브러리 취약점 검사 기록. 지난 검사와 비교해 버전 변화·새 취약점·해결된 취약점을 보여준다
create table dependency_scan (
    id                  bigserial    primary key,
    project_id          bigint       not null references project,
    commit_sha          varchar(40),
    checked             boolean      not null,
    error               text,
    dependency_count    int          not null,
    vulnerable_count    int          not null,
    vulnerability_count int          not null,
    report              jsonb        not null,
    created_at          timestamptz  not null default now()
);
create index dependency_scan_project_idx on dependency_scan (project_id, id desc);
