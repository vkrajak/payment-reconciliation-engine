package com.recon.dashboard.exceptions;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** resolvedBy may also come from the X-Resolved-By header (no auth in v1, see README). */
public record ResolveRequest(
        @Size(max = 100) String resolvedBy,
        @NotBlank @Size(max = 2000) String note) {
}
