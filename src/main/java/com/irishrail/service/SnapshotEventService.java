package com.irishrail.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class SnapshotEventService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotEventService.class);

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));

        // An immediate event flushes response headers, so the browser resolves the connection as
        // open instead of sitting in "pending" until the first collection cycle broadcasts.
        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException e) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    public void broadcast() {
        send("snapshot", "tick");
    }

    /**
     * Idle SSE connections are silently dropped by proxies and load balancers after ~30–60 s. A
     * comment frame keeps the stream warm and, when the client really is gone, surfaces the dead
     * emitter here so it gets pruned.
     */
    @Scheduled(fixedDelayString = "${irishrail.sse.heartbeat-ms:25000}")
    public void heartbeat() {
        send(null, null);
    }

    private void send(String eventName, String data) {
        if (emitters.isEmpty()) return;
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                if (eventName == null) {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                } else {
                    emitter.send(SseEmitter.event().name(eventName).data(data));
                }
            } catch (Exception e) {
                dead.add(emitter);
            }
        }
        emitters.removeAll(dead);
        if (!dead.isEmpty()) log.debug("Removed {} stale SSE connections", dead.size());
    }
}
