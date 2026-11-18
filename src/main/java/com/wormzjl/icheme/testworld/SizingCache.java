package com.wormzjl.icheme.testworld;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.science.material.MaterialCatalog;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * The review plant's sizes ({@link CrudeUnitPlant#sized}) kept for the game's run, by the material catalog's sources
 * ({@link MaterialCatalog#resources}): equal sources parse to an equal catalog, so the sizes are equal. The client starts them
 * on a background thread once the Create World screen has the column test world type ({@link #prepare}), as the process
 * simulator runs its sheets on its own thread ({@code SimulatorRunner}); the world's build waits for them or makes them
 * ({@link #plant}). State only: nothing is saved, and a game started again sizes again.
 */
public final class SizingCache {
    private SizingCache() {}

    /** The background thread's name. */
    public static final String THREAD = "IChemE column test sizing";

    private record Entry(Map<String, String> resources, CompletableFuture<CrudeUnitPlant.SizedPlant> plant) {
        boolean holds(MaterialCatalog catalog) {
            return !plant.isCompletedExceptionally() && !plant.isCancelled() && resources.equals(catalog.resources());
        }
    }

    private static Entry entry;

    /**
     * Starts {@code catalog}'s sizes on a background thread, unless they are made, being made, or failed on these sources
     * (the build makes them again, and reports the failure).
     */
    public static void prepare(MaterialCatalog catalog) {
        CompletableFuture<CrudeUnitPlant.SizedPlant> plant;
        synchronized (SizingCache.class) {
            if (entry != null && entry.resources().equals(catalog.resources())) return;
            plant = new CompletableFuture<>();
            entry = new Entry(catalog.resources(), plant);
        }
        var thread = new Thread(() -> make(catalog, plant), THREAD);
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
        IChemE.LOGGER.info("column_test_world sizing=started");
    }

    /** {@code catalog}'s sizes: the ones made, the ones being made once they are, or made now on this thread. */
    public static CrudeUnitPlant.SizedPlant plant(MaterialCatalog catalog) {
        CompletableFuture<CrudeUnitPlant.SizedPlant> plant;
        boolean make = false;
        synchronized (SizingCache.class) {
            if (entry != null && entry.holds(catalog)) plant = entry.plant();
            else {
                plant = new CompletableFuture<>();
                entry = new Entry(catalog.resources(), plant);
                make = true;
            }
        }
        IChemE.LOGGER.info("column_test_world sizing={}", make ? "made" : plant.isDone() ? "kept" : "waited");
        if (make) make(catalog, plant);
        try {
            return plant.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            if (e.getCause() instanceof Error r) throw r;
            throw e;
        }
    }

    private static void make(MaterialCatalog catalog, CompletableFuture<CrudeUnitPlant.SizedPlant> plant) {
        try {
            plant.complete(CrudeUnitPlant.sized(catalog));
        } catch (RuntimeException | Error e) {
            plant.completeExceptionally(e);
        }
    }
}
