package com.recon.dashboard.engine;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Not in the blueprint's endpoint table: backs the "is the engine keeping up" widget from §7.2. */
@RestController
@RequestMapping("/api/v1/engine")
public class EngineHealthController {

    private final EngineHealthService service;

    public EngineHealthController(EngineHealthService service) {
        this.service = service;
    }

    @GetMapping("/health")
    public EngineHealth health() {
        return service.health();
    }
}
