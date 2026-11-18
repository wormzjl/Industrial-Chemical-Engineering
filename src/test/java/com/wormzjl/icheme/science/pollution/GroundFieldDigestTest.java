package com.wormzjl.icheme.science.pollution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * The ground step's bits (batch 2026-11-18-pollution-propagation, P2): the digest was taken from the TreeMap-based step
 * (the compiled classes of a1cfd5268) before it moved to sorted arrays. tools/pollution-bench/GroundDigest.java computes
 * the same digest.
 */
class GroundFieldDigestTest {
    static final long DIGEST = 0xabdc36913b706f53L;

    @Test
    void theGroundStepKeepsItsStoredBits() {
        assertEquals(Long.toHexString(DIGEST), Long.toHexString(digest()));
    }

    static final String[] NAMES = {"Ammonia", "Benzene", "Hydrogen sulfide", "Phenol", "crude_pc08", "no_such_compound"};

    /**
     * 120 passes over a 40 × 40 block: amounts and up to three compounds per column, a stripe of unloaded columns, water
     * columns, rain every seventh pass, deposits for 80 passes (some outside the block, some with compounds only), a
     * spread 100 times the default so the front grows, a 600 s half-life so columns truncate, and a cap of 900 columns.
     */
    static long digest() {
        Random random = new Random(5);
        long[] columns = new long[1600];
        int n = 0;
        for (int x = -20; x < 20; x++) for (int z = -20; z < 20; z++) columns[n++] = MapColumns.column(x, z);
        Arrays.sort(columns);
        double[] amounts = new double[columns.length];
        List<GroundField.Entry> entries = new ArrayList<>();
        for (int i = 0; i < columns.length; i++) {
            double u = random.nextDouble();
            amounts[i] = u < 0.5 ? 0.0 : GroundField.GROUND_REFERENCE * 1e-3 * random.nextDouble();
            int count = random.nextInt(4);
            int first = random.nextInt(NAMES.length);
            for (int c = 0; c < count; c++) {
                entries.add(new GroundField.Entry(columns[i], NAMES[(first + c) % NAMES.length], 1e3 * random.nextDouble()));
            }
        }
        entries.sort(Comparator.comparingLong(GroundField.Entry::column).thenComparing(GroundField.Entry::name));
        GroundField field = GroundField.of(columns, amounts, entries);
        Set<Long> loaded = new HashSet<>();
        Set<Long> water = new HashSet<>();
        for (int x = -30; x < 30; x++) {
            for (int z = -30; z < 30; z++) {
                long c = MapColumns.column(x, z);
                if (x != 3 && x != -7) loaded.add(c);
                if ((x * 7 + z * 3) % 11 == 0) water.add(c);
            }
        }
        GroundEnvironment dry = GroundEnvironment.of(loaded, water, false);
        GroundEnvironment wet = GroundEnvironment.of(loaded, water, true);
        GroundSettings d = GroundSettings.DEFAULT;
        GroundSettings s = new GroundSettings(d.reference(), d.nominalSeconds(), 0.05, d.wetSpread(), d.waterSpread(), 600,
                d.washout(), d.cutoff(), 900);
        List<GroundField.Deposit> deposits = List.of(
                new GroundField.Deposit(MapColumns.column(0, 0), 3e4, List.of(new GroundField.Compound("Benzene", 500), new GroundField.Compound("Ammonia", 20))),
                GroundField.Deposit.of(MapColumns.column(25, -25), 8e4),
                new GroundField.Deposit(MapColumns.column(-22, 5), 0.0, List.of(new GroundField.Compound("Phenol", 40))),
                GroundField.Deposit.of(MapColumns.column(0, 0), 1e3),
                new GroundField.Deposit(MapColumns.column(3, 3), 5e4, List.of(new GroundField.Compound("Hydrogen sulfide", 70))));
        GroundLedger ledger = GroundLedger.EMPTY;
        long h = 0xcbf29ce484222325L;
        for (int p = 0; p < 120; p++) {
            GroundField.Result r = GroundField.step(field, ledger, p < 80 ? deposits : List.of(), p % 7 == 6 ? wet : dry, 20.0, s);
            field = r.field();
            ledger = r.ledger();
            if (p % 20 == 19) {
                for (long c : field.columns()) h = mix(h, c);
                for (double a : field.amounts()) h = mix(h, Double.doubleToRawLongBits(a));
                for (GroundField.Entry e : field.entries()) {
                    h = mix(h, e.column());
                    h = mix(h, e.name().hashCode());
                    h = mix(h, Double.doubleToRawLongBits(e.moles()));
                }
                for (double v : ledger.totals()) h = mix(h, Double.doubleToRawLongBits(v));
                for (Map.Entry<String, double[]> e : ledger.compounds().entrySet()) {
                    h = mix(h, e.getKey().hashCode());
                    for (double v : e.getValue()) h = mix(h, Double.doubleToRawLongBits(v));
                }
                h = mix(h, field.size());
            }
        }
        return h;
    }

    static long mix(long h, long v) {
        for (int b = 0; b < 8; b++) {
            h ^= (v >>> (8 * b)) & 0xff;
            h *= 0x100000001b3L;
        }
        return h;
    }
}
