-- 코드 그래프를 다시 만들 때 노드 id를 유지하기 위한 칸
-- indexed_at: 마지막으로 파싱 결과에 나타난 시각
-- removed_at: 파싱 결과에서 사라졌지만 분석 그래프(anchor)가 참조하고 있어 남겨 둔 노드
alter table code_node add column indexed_at timestamptz;
alter table code_node add column removed_at timestamptz;
update code_node set indexed_at = now();
alter table code_node alter column indexed_at set not null;
create index idx_code_node_project_kind_live on code_node (project_id, kind) where removed_at is null;
