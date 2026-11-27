-- 색인한 백엔드 언어를 쉼표로 잇는다 (java,python,kotlin,node 처럼 20자를 넘을 수 있다)
alter table project alter column language type varchar(100);
