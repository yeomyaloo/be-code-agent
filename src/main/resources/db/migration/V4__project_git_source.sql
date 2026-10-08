-- Git 주소로 등록한 저장소 정보 (로컬 경로로 등록하면 모두 null)
alter table project add column git_url text;
alter table project add column git_branch text;
alter table project add column commit_sha varchar(40);
alter table project add column synced_at timestamptz;
