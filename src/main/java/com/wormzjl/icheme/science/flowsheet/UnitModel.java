package com.wormzjl.icheme.science.flowsheet;

import java.util.List;

/**
 * What a tile does (batch 2026-11-02-process-simulator, plan sections 3.2 and 3.11): its ports, a pure function of its door's
 * settings, and the calculations a wired unit gives the run's graph. A law reads its settings when it runs, so a bad setting
 * refuses the unit, not the sheet.
 */
public interface UnitModel {
    List<Port> ports(Params params);

    List<Calculation> calculations(WiredUnit unit);
}
