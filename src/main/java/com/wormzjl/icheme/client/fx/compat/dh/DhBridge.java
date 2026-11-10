package com.wormzjl.icheme.client.fx.compat.dh;

import com.wormzjl.icheme.client.fx.FxCompat;
import com.wormzjl.icheme.client.fx.FxGuard;
import com.wormzjl.icheme.client.fx.silhouette.FarRenderer;
import com.wormzjl.icheme.client.fx.silhouette.SilhouetteBox;
import java.util.List;
import java.util.function.IntSupplier;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * The Distant Horizons integration of the far silhouettes (batch 2026-11-10-custom-shader-system, W10, plan sections 4.7
 * and 4.8). Loaded by name through {@link FxCompat#load} only when DH is present. This class names no DH type itself:
 * every DH call sits in {@link DhGroups}, reached only inside {@link #guard}, so even a DH whose API classes are missing or
 * changed leaves this class loadable, the gate false and every method a no-op. The first {@code LinkageError} or
 * {@code RuntimeException} turns the integration off for the session; a group's removal is still tried once after that.
 * <p>Version gate: DH's {@code getApiMajorVersion()} equals {@link #MAJOR} (a new major breaks the API) and
 * {@code getApiMinorVersion()} is at least {@link #MIN_MINOR}, the API 7.2.0 this build compiles against (DH 3.3.3).
 */
public final class DhBridge implements FarRenderer {
    public static final String MOD="distanthorizons";
    public static final int MAJOR=7,MIN_MINOR=2;
    /** Ticks between looks for a render register while none is known. */
    static final int POLL=20;

    private final FxGuard guard;
    private int sincePoll=POLL;

    public DhBridge(){this(new FxGuard("Distant Horizons",()->FxCompat.version(MOD)));}
    DhBridge(FxGuard guard){this.guard=guard;}

    /** Whether an API of {@code major}.{@code minor} passes the gate; false, without throwing, when either read fails. */
    public static boolean gate(IntSupplier major,IntSupplier minor) {
        try{return major.getAsInt()==MAJOR&&minor.getAsInt()>=MIN_MINOR;}
        catch(LinkageError|RuntimeException e){return false;}
    }

    @Override public boolean compatible() {
        // FxCompat.load logs a refusal with status(), so the gate itself does not log.
        // Lambdas, not method references: a reference to DhGroups resolves it where it is created, outside the try.
        if(guard.disabled()||!gate(()->DhGroups.major(),()->DhGroups.minor()))return false;
        return guard.run(()->DhGroups.bind(guard));
    }
    @Override public String status() {
        String version;
        try{version=DhGroups.version();}catch(LinkageError|RuntimeException e){version="API unreadable";}
        String base="Distant Horizons "+FxCompat.version(MOD)+" ("+version+")";
        return guard.disabled()?base+", off: "+guard.failure():base;
    }
    @Override public boolean on(){return !guard.disabled();}
    @Override public int radius(){return guard.call(()->DhGroups.radius(),0);}
    @Override public boolean ready(){return guard.test(()->DhGroups.ready(),false);}
    @Override public void tick() {
        if(guard.disabled()||++sincePoll<POLL)return;
        sincePoll=0;
        guard.run(()->DhGroups.register(true));
    }
    @Override public @Nullable Group add(BlockPos origin,List<SilhouetteBox> boxes) {
        Object handle=guard.call(()->DhGroups.add(origin,boxes),null);
        return handle==null?null:new Registered(handle);
    }

    /** A registered group: its state set only on a change, its removal tried once. */
    private final class Registered implements Group {
        private final Object handle;
        private boolean active,removed;
        Registered(Object handle){this.handle=handle;}
        @Override public void active(boolean on) {
            if(removed||on==active)return;
            if(guard.run(()->DhGroups.active(handle,on)))active=on;
        }
        @Override public void remove() {
            if(removed)return;
            removed=true;
            try{DhGroups.remove(handle);}catch(LinkageError|RuntimeException e){/* DH dropped it with its level, or failed: nothing left to undo */}
        }
    }
}
