package com.wormzjl.icheme.science.tutorial.equipment;

import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.ConstructorDefaults;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.MachineDuty;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.TowerBank;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.science.equipment.VesselPlacement;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Each {@link EquipmentShape.Kind} at its native reference size (batch 2026-11-12-tutorial-system, P3 icons, P5 films): the
 * equipment constructor's default size of the kind's first form (tools/equipment-drawings' EngineeringExport scene; a drum
 * lying), facing north. The reference items' icons and the equipment pages' films are drawn on it. No Minecraft type.
 */
public final class ReferenceShapes {
    private ReferenceShapes() {
    }

    /** The kind's constructor form: its lengthwise one where it has two; null for a kind the constructor does not build. */
    public static @Nullable VesselPlacement.Form form(EquipmentShape.Kind kind) {
        VesselPlacement.Form form = null;
        for (var f : VesselPlacement.Form.values())
            if (f != VesselPlacement.Form.CROSSWISE && f.kind() == kind && (form == null || f == VesselPlacement.Form.LENGTHWISE)) form = f;
        return form;
    }

    /** The kind at its reference size, or null where the constructor has no form for it (nothing to draw: a page shows its header only). */
    public static @Nullable EquipmentShape of(EquipmentShape.Kind kind) {
        return of(kind, false);
    }

    /** As {@link #of(EquipmentShape.Kind)}; a drum with {@code boot} has the water boot of a column's reflux drum under its front end. */
    public static @Nullable EquipmentShape of(EquipmentShape.Kind kind, boolean boot) {
        var heading = EquipmentShape.Heading.NORTH;
        var form = form(kind);
        if (form == null) return null;
        var size = ConstructorDefaults.sizes(form);
        double d = size.diameter();
        if (kind == EquipmentShape.Kind.DRUM) {
            int support = EquipmentShape.defaultSupport(d, form.orientation());
            if (boot) support = Math.max(support, EquipmentShape.minimumBootSupport(d, size.length()));
            return EquipmentShape.drum(d, size.length(), form.orientation(), heading, support,
                    EquipmentShape.defaultConnections(d, size.length(), form.orientation(), support, boot), boot);
        }
        if (kind == EquipmentShape.Kind.COLUMN)
            return EquipmentShape.column(d, new ColumnTrays(32, 18, List.of(10, 24), List.of(33), List.of(new ColumnTrays.Pumparound(26, 20))), heading, EquipmentShape.minimumSkirt(d));
        var design = UnitDesign.of(kind);
        if (kind.storage())
            return new EquipmentShape(kind, d, size.length(), design.orientation(), heading, design.minSupport(d, size.length()), null, List.of(), ProcessUnit.Bank.ONE, false, 0,
                    TowerBank.ONE, MachineDuty.NONE);
        return EquipmentShape.unit(kind, d, size.length(), heading, design.minSupport(d, size.length()));
    }
}
