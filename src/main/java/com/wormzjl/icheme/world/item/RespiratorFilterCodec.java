package com.wormzjl.icheme.world.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.wormzjl.icheme.science.pollution.Respirator;

/**
 * The saved form of a respirator's fitted filter (batch 2026-11-18-respirator): its remaining capacity in index-seconds,
 * 0 (spent) to {@link Respirator#FILTER_CAPACITY}. Item data, not an engine format. No Minecraft type, so a plain test reads it.
 */
public final class RespiratorFilterCodec {
    public static final Codec<Double> CODEC = Codec.DOUBLE.validate(remaining -> remaining >= 0 && remaining <= Respirator.FILTER_CAPACITY
            ? DataResult.success(remaining)
            : DataResult.error(() -> "A respirator filter's remaining capacity is 0 to " + Respirator.FILTER_CAPACITY + ", not " + remaining));

    private RespiratorFilterCodec() {
    }
}
