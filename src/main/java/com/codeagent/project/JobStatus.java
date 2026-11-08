package com.codeagent.project;

public enum JobStatus {
    PENDING, RUNNING,
    /** 계획을 사람이 승인하기를 기다림 (Worker 실행 전) */
    WAITING_APPROVAL,
    DONE, STOPPED, FAILED
}
