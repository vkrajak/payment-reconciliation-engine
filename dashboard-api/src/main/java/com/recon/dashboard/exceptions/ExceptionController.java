package com.recon.dashboard.exceptions;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/exceptions")
public class ExceptionController {

    private final ExceptionQueryService queries;
    private final ExceptionResolveService resolver;

    public ExceptionController(ExceptionQueryService queries, ExceptionResolveService resolver) {
        this.queries = queries;
        this.resolver = resolver;
    }

    @GetMapping
    public PagedResponse<ExceptionSummary> list(@RequestParam(required = false) String status,
                                                @RequestParam(required = false) String code,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return queries.list(status, code, page, size);
    }

    @GetMapping("/{id}")
    public ExceptionDetail detail(@PathVariable long id) {
        return queries.detail(id);
    }

    @PostMapping("/{id}/resolve")
    public ExceptionDetail resolve(@PathVariable long id,
                                   @Valid @RequestBody ResolveRequest request,
                                   @RequestHeader(value = "X-Resolved-By", required = false) String resolvedBy) {
        return resolver.resolve(id, request, resolvedBy);
    }
}
