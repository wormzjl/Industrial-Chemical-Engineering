package com.wormzjl.icheme.runtime.control;

import java.util.Collection;

/**
 * An alarm beacon's decision at the control cadence (batch 2026-11-15-fluid-interfaces, W9, plan section 4): a pure
 * function of its settings, its sample and its last state, with no Minecraft class.
 *
 * <ul>
 *   <li>ABOVE: active at x &ge; t, clear at x &lt; t &minus; b; BELOW: active at x &le; t, clear at x &gt; t + b; the band
 *       b is {@link #BAND} of |t| (decision A3, the automated valve's band).</li>
 *   <li>An alarm channel, and {@link AlarmSettings.Source#ANY_ALARM} (any raised alarm on the bus): active while raised.</li>
 *   <li>Not enabled: {@link Status#OFF}; no sample (no socket on a bus, the input not on it, a reading that is not
 *       available): {@link Status#NO_SIGNAL}; either is not active and forgets the last state (decision A4).</li>
 *   <li>An acknowledgement holds while the alarm stays active; it drops when the alarm clears, and the next rise is
 *       {@link Status#ACTIVE} again (decision A5, no latch).</li>
 * </ul>
 */
public final class AlarmLaw {
    private AlarmLaw() {}

    /** The clearing band, a share of the threshold's magnitude (decision A3). */
    public static final double BAND = .02;

    /** What the beacon shows. Saved nowhere: engine state, published to its menu by name. */
    public enum Status {
        CLEAR,
        ACTIVE,
        ACKNOWLEDGED,
        NO_SIGNAL,
        OFF;

        /** Whether the lamp flashes. */
        public boolean lit() {
            return this == ACTIVE || this == ACKNOWLEDGED;
        }
    }

    /** One beacon's state between cadences: whether its condition stands and whether a hand acknowledged it. */
    public record State(boolean active, boolean acknowledged) {
        public static final State NONE = new State(false, false);

        public State {
            if (acknowledged && !active) throw new IllegalArgumentException("Only an active alarm is acknowledged");
        }
    }

    /**
     * A sample in the settings' unit: {@code value}, and whether it is an alarm channel's (1 raised, 0 clear). Null where
     * there is no signal.
     */
    public record Sample(double value, boolean alarmChannel) {}

    /** A decision: what the beacon shows and the state it keeps. */
    public record Decision(Status status, State state) {}

    /** The decision for {@code settings} on {@code sample} (null: no signal), after {@code previous}. */
    public static Decision decide(AlarmSettings settings, Sample sample, State previous) {
        if (!settings.enabled()) return new Decision(Status.OFF, State.NONE);
        if (sample == null || !Double.isFinite(sample.value())) return new Decision(Status.NO_SIGNAL, State.NONE);
        boolean active;
        if (sample.alarmChannel() || settings.source() == AlarmSettings.Source.ANY_ALARM) active = sample.value() >= .5;
        else {
            double t = settings.threshold(), b = BAND * Math.abs(t), x = sample.value();
            boolean above = settings.comparison() == AlarmSettings.Comparison.ABOVE;
            active = previous.active() ? (above ? x >= t - b : x <= t + b) : (above ? x >= t : x <= t);
        }
        if (!active) return new Decision(Status.CLEAR, State.NONE);
        boolean acknowledged = previous.active() && previous.acknowledged();
        return new Decision(acknowledged ? Status.ACKNOWLEDGED : Status.ACTIVE, new State(true, acknowledged));
    }

    /**
     * The sample of {@link AlarmSettings.Source#ANY_ALARM}: 1 when any of {@code channels} (the alarm channels of the hosts
     * the beacon's bus reaches) reads raised, else 0; null where the beacon is on no bus.
     */
    public static Sample anyAlarm(boolean onBus, Collection<Double> channels) {
        if (!onBus) return null;
        for (double v : channels) if (v >= .5) return new Sample(1, true);
        return new Sample(0, true);
    }

    /** A hand's acknowledgement: it holds on an active alarm, and does nothing otherwise. */
    public static State acknowledge(State state) {
        return state.active() ? new State(true, true) : state;
    }
}
