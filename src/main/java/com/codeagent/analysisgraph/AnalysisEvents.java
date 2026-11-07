package com.codeagent.analysisgraph;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 분석 작업의 변화를 화면에 바로 알린다 (Server-Sent Events).
 * 분석 그래프·에이전트 기록이 바뀌면 "change", 작업이 끝나면 "finished" 를 보낸다.
 * 화면은 이벤트를 받으면 데이터를 다시 불러온다. 이벤트에는 무엇이 바뀌었는지만 담는다.
 */
@Slf4j
@Component
public class AnalysisEvents {

    /** 연결 하나를 유지하는 최대 시간. 끝나면 브라우저(EventSource)가 알아서 다시 연결한다 */
    static final Duration TIMEOUT = Duration.ofMinutes(30);
    /** 프록시가 조용한 연결을 끊지 않도록 보내는 주석 줄 간격 */
    private static final long HEARTBEAT_SECONDS = 20;

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "analysis-events-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public AnalysisEvents() {
        heartbeat.scheduleAtFixedRate(this::sendHeartbeats, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 작업의 이벤트를 받을 연결을 연다.
     *
     * @param finished 이미 끝난 작업이면 "finished" 를 보내고 바로 닫는다
     */
    public SseEmitter subscribe(Long jobId, String status, boolean finished) {
        SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
        List<SseEmitter> list = emitters.computeIfAbsent(jobId, id -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable remove = () -> list.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        if (!send(emitter, "ready", status)) {
            list.remove(emitter);
        } else if (finished) {
            send(emitter, "finished", status);
            emitter.complete();
        }
        return emitter;
    }

    /** 작업의 무언가가 바뀌었다. 트랜잭션 안이면 커밋된 뒤에 알린다 (화면이 다시 불러올 때 보이도록) */
    public void changed(Long jobId, String what) {
        afterCommit(() -> broadcast(jobId, "change", what));
    }

    /** 작업이 끝났다. 알린 뒤 연결을 닫는다 */
    public void finished(Long jobId, String status) {
        afterCommit(() -> {
            List<SseEmitter> list = emitters.remove(jobId);
            if (list == null) {
                return;
            }
            for (SseEmitter emitter : list) {
                send(emitter, "finished", status);
                emitter.complete();
            }
        });
    }

    int subscribers(Long jobId) {
        return emitters.getOrDefault(jobId, List.of()).size();
    }

    private void broadcast(Long jobId, String event, String data) {
        List<SseEmitter> list = emitters.get(jobId);
        if (list == null) {
            return;
        }
        for (SseEmitter emitter : list) {
            if (!send(emitter, event, data)) {
                list.remove(emitter);
            }
        }
    }

    private void sendHeartbeats() {
        emitters.values().forEach(list -> list.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException e) {
                list.remove(emitter);
            }
        }));
    }

    /** @return 보내지 못했으면(연결이 끊김) false */
    private static boolean send(SseEmitter emitter, String event, String data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data == null ? "" : data));
            return true;
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 전송 실패 (연결 끊김): {}", e.getMessage());
            return false;
        }
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    @PreDestroy
    void shutdown() {
        heartbeat.shutdownNow();
        emitters.values().forEach(list -> list.forEach(SseEmitter::complete));
    }
}
