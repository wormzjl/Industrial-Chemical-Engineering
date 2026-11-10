package com.wormzjl.icheme.science.machine;

/**
 * A rotodynamic machine's law as the fluid network reads it (batch 2026-11-03-rotating-machinery, plan sections 3.1 and
 * 3.2): the pressure rise it holds at a suction volume flow and speed, falling with flow at every speed above zero, and the
 * power the fluid and the shaft take. Every quantity reads the suction: its density, its pressure and the gas's heat
 * capacity ratio k, which a pump does not use.
 */
public sealed interface MachineLaw permits CentrifugalPump, CentrifugalCompressor, VacuumPump {
    /** Whether the machine moves liquid (a pump) or gas (a compressor). */
    boolean liquid();

    /** The pressure rise, Pa, at suction volume flow {@code flow} (m³/s) and {@code speed} (share of rated). */
    double rise(double flow, double speed, double density, double pressure, double k);

    /** {@code d(rise)/dQ}, Pa per m³/s. */
    double riseSlope(double flow, double speed, double density, double pressure, double k);

    /** The power the fluid takes, W, for mass flow {@code massFlow} (kg/s) across {@code rise} (Pa); none backwards. */
    double fluidPower(double massFlow, double rise, double density, double pressure, double k);

    /** The law as the {@code MOVER_LAW} row, Pa: zero on the law. The head form by default, whose head derivative is one;
     * a positive-displacement law states the flow form ({@link PositiveDisplacement#residual}). */
    default double residual(double flow, double head, double speed, double density, double pressure, double k) {
        return head - rise(flow, speed, density, pressure, k);
    }

    /** The shaft power, W, at {@code flow} and {@code speed}. */
    double shaftPower(double flow, double speed, double density, double pressure, double k);

    /** The suction volume flow at the rated point, m³/s. */
    double ratedFlow();

    /** The head at the rated point: metres of liquid for a pump, J/kg polytropic for a compressor. */
    double ratedHead();

    /** The efficiency at the rated point. */
    double efficiency();
}
