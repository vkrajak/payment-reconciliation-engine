package com.recon.dashboard.exceptions;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExceptionJpaRepository
        extends JpaRepository<ExceptionEntity, Long>, JpaSpecificationExecutor<ExceptionEntity> {

    /** Atomic and idempotent: only flips rows that are not already RESOLVED. Returns rows changed (0 or 1). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ExceptionEntity e
               set e.status = 'RESOLVED', e.resolvedAt = :at, e.resolvedBy = :by,
                   e.resolutionNote = :note, e.lastUpdatedAt = :at
             where e.id = :id and e.status <> 'RESOLVED'
            """)
    int markResolved(@Param("id") Long id, @Param("at") Instant at,
                     @Param("by") String by, @Param("note") String note);

    @Query("select coalesce(max(e.id), 0) from ExceptionEntity e")
    long maxId();

    List<ExceptionEntity> findTop200ByIdGreaterThanOrderByIdAsc(Long id);
}
