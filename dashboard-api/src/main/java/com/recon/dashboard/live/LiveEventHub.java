package com.recon.dashboard.live;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Holds the connected SSE clients of THIS instance. Browsers auto-reconnect when the 30-min timeout fires. */
@Component
public class LiveEventHub {

    private static final Logger log = LoggerFactory.getLogger(LiveEventHub.class);
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final List<SseEmitter> clients = new CopyOnWriteArrayList<>();

    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitter.onCompletion(() -> clients.remove(emitter));
        emitter.onTimeout(() -> {
            clients.remove(emitter);
            emitter.complete();
        });
        emitter.onError(t -> clients.remove(emitter));
        clients.add(emitter);
        send(emitter, "connected", java.util.Map.of("clients", clients.size()));
        return emitter;
    }

    public int clientCount() {
        return clients.size();
    }

    public void broadcast(String eventName, Object data) {
        for (SseEmitter emitter : clients) {
            send(emitter, eventName, data);
        }
    }

    @Scheduled(fixedRateString = "${dashboard.live.keepalive-ms:15000}")
    void keepAlive() {
        for (SseEmitter emitter : clients) {
            try {
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (IOException | IllegalStateException e) {
                clients.remove(emitter);
            }
        }
    }

    private void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            clients.remove(emitter);
            log.debug("Dropped SSE client: {}", e.toString());
        }
    }
}
