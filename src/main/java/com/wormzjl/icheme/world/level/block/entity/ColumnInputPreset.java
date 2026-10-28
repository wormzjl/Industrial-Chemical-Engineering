package com.wormzjl.icheme.world.level.block.entity;

import com.wormzjl.icheme.science.column.v3.*;
import com.wormzjl.icheme.science.material.*;
import java.util.*;

/** Stable choice identity; every production operating definition comes from the captured catalog. */
public final class ColumnInputPreset {
    private final String id;
    private ColumnInputPreset(String id){this.id=id;}
    public String id(){return id;}
    public String label(){return descriptor(MaterialRuntime.current()).label();}
    public MaterialPresets.Descriptor descriptor(MaterialCatalog catalog) {
        return catalog.presets().column(id).descriptor();
    }
    public boolean matches(V3ColumnInput input){return descriptor(MaterialRuntime.current()).matches(input);}
    public static List<MaterialPresets.Descriptor> descriptors(MaterialCatalog catalog) {
        return List.copyOf(catalog.presets().visibleColumns());
    }
    public static ColumnInputPreset[] values(){return descriptors(MaterialRuntime.current()).stream().map(d->fromId(d.id())).toArray(ColumnInputPreset[]::new);}
    public static ColumnInputPreset fromId(String id){return fromId(MaterialRuntime.current(),id);}
    public static ColumnInputPreset fromId(MaterialCatalog catalog,String id) {
        if(!catalog.presets().column(id).visible())throw new IllegalArgumentException("Hidden column input preset: "+id);
        return new ColumnInputPreset(id);
    }
    public V3ColumnInput input(MaterialCatalog catalog){return catalog.presets().column(id).input(catalog);}
    @Override public boolean equals(Object other){return other instanceof ColumnInputPreset p&&id.equals(p.id);}
    @Override public int hashCode(){return id.hashCode();}
}
