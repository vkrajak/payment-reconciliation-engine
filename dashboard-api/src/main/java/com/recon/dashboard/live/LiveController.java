package com.recon.dashboard.live;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Server-Sent Events: "connected", "exception" (one per new exception), "matches" (batched). */
@RestController
@RequestMapping("/api/v1/stream")
public class LiveController {

    private final LiveEventHub hub;

    public LiveController(LiveEventHub hub) {
        this.hub = hub;
    }

    @GetMapping(path = "/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter live() {
        return hub.register();
    }
}
