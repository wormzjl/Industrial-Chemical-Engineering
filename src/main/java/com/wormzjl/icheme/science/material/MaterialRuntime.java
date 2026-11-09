package com.wormzjl.icheme.science.material;

import java.util.Objects;
import java.util.function.Supplier;

/** Atomically published catalog and explicitly bounded calculation context for legacy static evaluators. */
public final class MaterialRuntime {
    private static volatile MaterialCatalog active;
    private record Context(MaterialCatalog catalog, String packageId) {}
    private static final ThreadLocal<Context> CONTEXT = new ThreadLocal<>();
    private MaterialRuntime() {}
    public static MaterialCatalog active() { MaterialCatalog c=active; return c==null?MaterialCatalog.bundled():c; }
    public static MaterialCatalog current() { Context c=CONTEXT.get(); return c==null?active():c.catalog(); }
    /** Only the server reload adapter publishes; parse/validation must already have succeeded. */
    public static void publish(MaterialCatalog catalog) { active=Objects.requireNonNull(catalog); }
    public static void reset() { active=null; }
    public static MaterialCatalog.Water water() {
        Context c=CONTEXT.get();
        String id=c==null || !c.catalog().packages().containsKey(c.packageId())?"icheme:tjl20_methane":c.packageId();
        return current().requirePackage(id).water();
    }
    /** Restores an enclosing context on every exit, including cancellation and failure. */
    public static <T> T with(MaterialCatalog catalog,String packageId,Supplier<T> action) {
        Context previous=CONTEXT.get();
        CONTEXT.set(new Context(Objects.requireNonNull(catalog),Objects.requireNonNull(packageId)));
        try{return action.get();}finally{if(previous==null)CONTEXT.remove();else CONTEXT.set(previous);}
    }
    public static <T> T pinned(String packageId,Supplier<T> action) {
        return with(current(),packageId,action);
    }
}
