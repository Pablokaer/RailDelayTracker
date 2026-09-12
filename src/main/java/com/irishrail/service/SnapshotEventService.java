package com.irishrail.service;

import com.irishrail.config.IrishRailProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
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
    private final ThreadPoolTaskExecutor broadcastExecutor;
    private final IrishRailProperties properties;

    public SnapshotEventService(@Qualifier("sseBroadcastExecutor") ThreadPoolTaskExecutor broadcastExecutor,
                                IrishRailProperties properties,
                                MeterRegistry meters) {
        this.broadcastExecutor = broadcastExecutor;
        this.properties = properties;
        meters.gauge("irishrail.sse.subscribers", emitters, List::size);
    }

    /**
     * @return null when the subscriber cap is reached, which the controller turns into a 503.
     *     Without a cap the list grew with every connection a client opened and never closed
     *     cleanly, and each broadcast then walked all of them.
     */
    public SseEmitter subscribe() {
        if (emitters.size() >= properties.sse().maxClients()) {
            log.warn("SSE subscriber cap of {} reached; refusing new stream", properties.sse().maxClients());
            return null;
        }

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

    /**
     * Called at the end of every collection cycle.
     *
     * <p>The fan-out is handed to a dedicated single worker rather than run inline: this used to
     * execute on the collector's scheduler thread, so a single slow or half-dead subscriber
     * delayed the next collection cycle for everybody. The executor drops a tick when one is
     * already queued, which is correct here — the event carries no payload, so a client that
     * misses one still re-reads the same current state on the next.
     */
    public void broadcast() {
        if (emitters.isEmpty()) return;
        broadcastExecutor.execute(() -> send("snapshot", "tick"));
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
