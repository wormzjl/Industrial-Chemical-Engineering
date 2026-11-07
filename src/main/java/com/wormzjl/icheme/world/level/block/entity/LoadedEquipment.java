package com.wormzjl.icheme.world.level.block.entity;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Paint;
import net.minecraft.core.BlockPos;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The equipment structures of one client level that have a cell loaded. A structure's still parts are one mesh drawn from its origin
 * and it spans many chunks, so it stands while any of its cells is loaded, whichever chunks the view leaves out.
 */
public final class LoadedEquipment {
    /** One structure, with how many of its cells are loaded. */
    public static final class Structure {
        private final BlockPos origin;
        private EquipmentShape shape;
        private Paint paint;
        private int cells;

        private Structure(BlockPos origin) {
            this.origin = origin;
        }

        public BlockPos origin() {
            return origin;
        }

        public EquipmentShape shape() {
            return shape;
        }

        public Paint paint() {
            return paint;
        }
    }

    private final Map<BlockPos, Structure> structures = new HashMap<>();

    /** One more cell of the structure at {@code origin} is loaded; the structure takes the shape and paint it carries. */
    public Structure add(BlockPos origin, EquipmentShape shape, Paint paint) {
        var structure = structures.computeIfAbsent(origin.immutable(), Structure::new);
        structure.shape = shape;
        structure.paint = paint;
        structure.cells++;
        return structure;
    }

    /** One cell of {@code structure} left; its last takes the structure with it. */
    public void remove(Structure structure) {
        if (--structure.cells == 0) structures.remove(structure.origin);
    }

    public Collection<Structure> all() {
        return Collections.unmodifiableCollection(structures.values());
    }
}
