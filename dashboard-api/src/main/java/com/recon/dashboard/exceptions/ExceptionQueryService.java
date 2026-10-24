package com.recon.dashboard.exceptions;

import com.recon.dashboard.web.NotFoundException;
import com.recon.events.exception.ExceptionCode;
import java.util.Locale;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExceptionQueryService {

    static final Set<String> STATUSES = Set.of("OPEN", "ACKNOWLEDGED", "RESOLVED");
    static final int MAX_PAGE_SIZE = 100;

    private final ExceptionJpaRepository repo;
    private final ExceptionDetailAssembler assembler;

    public ExceptionQueryService(ExceptionJpaRepository repo, ExceptionDetailAssembler assembler) {
        this.repo = repo;
        this.assembler = assembler;
    }

    @Transactional(readOnly = true)
    public PagedResponse<ExceptionSummary> list(String status, String code, int page, int size) {
        if (page < 0 || size < 1) {
            throw new IllegalArgumentException("page must be >= 0 and size >= 1");
        }
        Specification<ExceptionEntity> spec = (root, query, cb) -> cb.conjunction();
        if (status != null && !status.isBlank()) {
            String s = status.trim().toUpperCase(Locale.ROOT);
            if (!STATUSES.contains(s)) {
                throw new IllegalArgumentException("status must be one of " + STATUSES);
            }
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), s));
        }
        if (code != null && !code.isBlank()) {
            String c = code.trim().toUpperCase(Locale.ROOT);
            try {
                ExceptionCode.valueOf(c);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("unknown exception code '" + code + "'");
            }
            spec = spec.and((root, query, cb) -> cb.equal(root.get("exceptionCode"), c));
        }
        var pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE),
                Sort.by(Sort.Order.desc("firstDetectedAt"), Sort.Order.desc("id")));
        return PagedResponse.of(repo.findAll(spec, pageable).map(ExceptionSummary::of));
    }

    @Transactional(readOnly = true)
    public ExceptionDetail detail(long id) {
        ExceptionEntity e = repo.findById(id).orElseThrow(() -> new NotFoundException("Exception " + id + " not found"));
        return assembler.assemble(e);
    }
}
