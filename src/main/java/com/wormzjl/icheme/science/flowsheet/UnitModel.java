package com.wormzjl.icheme.science.flowsheet;

import java.util.List;

/**
 * What a tile does (batch 2026-11-02-process-simulator, plan sections 3.2 and 3.11): its ports, a pure function of its door's
 * settings, and the calculations a wired unit gives the run's graph. A law reads its settings when it runs, so a bad setting
 * refuses the unit, not the sheet.
 */
public interface UnitModel {
    /** The roles its calculations read and write. A passage's two roles stand here, and the sheet shows the passage's ends instead. */
    List<Port> ports(Params params);

    /** Its reversible passages (PORT_SIDES_REVIEW.md option B): each one's roles are two of {@link #ports}. None by default. */
    default List<Passage> passages(Params params) {
        return List.of();
    }

    List<Calculation> calculations(WiredUnit unit);
}
