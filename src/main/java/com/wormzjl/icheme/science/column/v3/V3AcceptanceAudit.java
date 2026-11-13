package com.wormzjl.icheme.science.column.v3;

import java.util.List;
import java.util.Objects;

/** Immutable acceptance evidence consumed by the sole future V3 success gate. */
public record V3AcceptanceAudit(List<Check> checks, List<String> advisoryEvidence) {
    public V3AcceptanceAudit(List<Check> checks) {
        this(checks, List.of());
    }

    public V3AcceptanceAudit {
        checks = List.copyOf(checks);
        if (checks.isEmpty() || checks.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("A V3 acceptance audit must contain non-null checks");
        }
        advisoryEvidence = List.copyOf(advisoryEvidence);
        if (advisoryEvidence.size() > 16 || advisoryEvidence.stream().anyMatch(
                evidence -> evidence == null || evidence.isBlank() || evidence.length() > 256)) {
            throw new IllegalArgumentException("V3 advisory evidence exceeds the bounded contract");
        }
    }

    public boolean accepted() {
        return checks.stream().allMatch(Check::passed);
    }

    /** One independently recomputed acceptance condition. */
    public record Check(String family, double value, double limit, boolean passed, String detail) {
        public Check {
            family = bounded(family, "family", 96);
            detail = bounded(detail, "detail", 512);
            if (!Double.isFinite(value) || value < 0.0 || !Double.isFinite(limit) || limit < 0.0) {
                throw new IllegalArgumentException("V3 acceptance metrics must be finite and nonnegative");
            }
        }

        public static Check pass(String family, double value, double limit, String detail) {
            return new Check(family, value, limit, true, detail);
        }

        public static Check fail(String family, double value, double limit, String detail) {
            return new Check(family, value, limit, false, detail);
        }

        /** A passed check of one of the solver's own families, published as the family's text. */
        public static Check pass(V3AuditFamily family, double value, double limit, String detail) {
            return pass(family.text(), value, limit, detail);
        }

        /** A failed check of one of the solver's own families, published as the family's text. */
        public static Check fail(V3AuditFamily family, double value, double limit, String detail) {
            return fail(family.text(), value, limit, detail);
        }

        /** Whether this check's family is {@code family}: its text equals the family's text. */
        public boolean hasFamily(V3AuditFamily family) {
            return this.family.equals(family.text());
        }

        private static String bounded(String value, String name, int maximumLength) {
            value = Objects.requireNonNull(value, name);
            if (value.isBlank() || value.length() > maximumLength) {
                throw new IllegalArgumentException(name + " is blank or exceeds the bounded contract");
            }
            return value;
        }
    }
}
