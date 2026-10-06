alter table analysis_job add column error text;
alter table analysis_job add column input_tokens bigint not null default 0;
alter table analysis_job add column output_tokens bigint not null default 0;
