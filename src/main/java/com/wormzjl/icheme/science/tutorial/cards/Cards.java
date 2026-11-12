package com.wormzjl.icheme.science.tutorial.cards;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** The cards by name (batch 2026-11-12-tutorial-system): the guide, the tours and the render tool open them by it. */
public final class Cards {
    private static final Map<String, Supplier<Card>> ALL = new LinkedHashMap<>();

    static {
        ALL.put(ThreeParts.NAME, ThreeParts::new);
        ALL.put(Chain.NAME, Chain::new);
        ALL.put(Barrel.NAME, Barrel::new);
        ALL.put(Products.NAME, Products::new);
        ALL.put(Road.NAME, Road::new);
        ALL.put(PotTemperature.NAME, PotTemperature::new);
    }

    private Cards() {
    }

    /** Every card's name, in order. */
    public static List<String> names() {
        return List.copyOf(ALL.keySet());
    }

    /** The card named {@code name}, built anew; null for none. */
    public static @Nullable Card build(String name) {
        var card = ALL.get(name);
        return card == null ? null : card.get();
    }
}
