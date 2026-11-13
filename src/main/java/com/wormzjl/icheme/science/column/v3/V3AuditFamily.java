package com.wormzjl.icheme.science.column.v3;

/**
 * The families of the acceptance checks the solver itself produces ({@link V3AcceptanceAuditor} and the
 * calculator's failure audits). A {@link V3AcceptanceAudit.Check} keeps its family as text: the inspection codec
 * decodes it from text on the wire and in NBT, and fixtures build checks with families of their own. Each constant
 * carries the exact text it is published as.
 */
public enum V3AuditFamily {
    FINITE_TOPOLOGY("FINITE_TOPOLOGY"),
    LOCAL_COMPONENT_BALANCE("LOCAL_COMPONENT_BALANCE"),
    EQUILIBRIUM("EQUILIBRIUM"),
    ENERGY_BALANCE("ENERGY_BALANCE"),
    GLOBAL_ENERGY_BALANCE("GLOBAL_ENERGY_BALANCE"),
    CONDENSER_PHASE("CONDENSER_PHASE"),
    CONDENSER_ENERGY_BALANCE("CONDENSER_ENERGY_BALANCE"),
    SIDE_DRAW_SPLIT("SIDE_DRAW_SPLIT"),
    TRUNCATION_MASS_DEFECT("TRUNCATION_MASS_DEFECT"),
    PHASE_TRUNCATION_DEFECT("PHASE_TRUNCATION_DEFECT"),
    WATER_PROFILE("WATER_PROFILE"),
    WATER_BALANCE("WATER_BALANCE"),
    WATER_DEW_POINT("WATER_DEW_POINT"),
    FREE_WATER_SPLIT("FREE_WATER_SPLIT"),
    INTERIOR_PHASE_IDENTITY("INTERIOR_PHASE_IDENTITY"),
    CARRY_OVER("CARRY_OVER"),
    /** A failure audit: no candidate state was there to audit. */
    UNAVAILABLE("UNAVAILABLE");

    private final String text;

    V3AuditFamily(String text) {
        this.text = text;
    }

    /** The published text of the family, {@link V3AcceptanceAudit.Check#family()}. */
    public String text() {
        return text;
    }

    @Override
    public String toString() {
        return text;
    }
}
