package com.wormzjl.icheme.client.fx.silhouette;

import com.wormzjl.icheme.client.fx.FxCompat;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * The far terrain renderer the silhouettes draw into (batch 2026-11-10-custom-shader-system, W10, plan sections 4.7 and
 * 4.8): Distant Horizons' generic box groups, seen through this small interface so that no DH type leaves
 * {@code client/fx/compat/dh/}. The implementation ({@code DhBridge}) is loaded by name only when DH is present and its API
 * passes the version gate; otherwise {@link #NONE} stands for the session. Every method is guarded in the implementation
 * and never throws: once a call into DH failed, the integration is off ({@link #on()} false) and every call is a no-op.
 * Client thread only.
 */
public interface FarRenderer extends FxCompat.Integration {
    /** No far renderer: DH absent, refused by the gate, or failed. Its own grey voxels stay as they are. */
    FarRenderer NONE = new FarRenderer() {
        @Override public boolean compatible(){return false;}
        @Override public String status(){return "Distant Horizons absent or off";}
        @Override public boolean on(){return false;}
        @Override public int radius(){return 0;}
        @Override public boolean ready(){return false;}
        @Override public @Nullable Group add(BlockPos origin,List<SilhouetteBox> boxes){return null;}
        @Override public void tick(){}
    };

    /** One registered box group: a structure's near boxes or its far hull, positioned at its origin. */
    interface Group {
        /** Draw it or not; a call that matches the current state does nothing. */
        void active(boolean on);
        /** Unregisters it; later calls do nothing. */
        void remove();
    }

    /** Whether the integration still works: false once any call into DH failed. */
    boolean on();
    /** DH's far render radius, blocks (its chunk render distance times 16); 0 when unknown. */
    int radius();
    /** Whether groups can be registered now: DH's renderer and the client level's render register are ready. */
    boolean ready();
    /** Registers {@code boxes} (relative to {@code origin}) as one inactive group; null when not {@link #ready} or failed. */
    @Nullable Group add(BlockPos origin,List<SilhouetteBox> boxes);
    /** Client tick: picks up DH's level load and unload. Cheap. */
    void tick();
}
