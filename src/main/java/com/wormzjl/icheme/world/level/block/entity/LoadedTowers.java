package com.wormzjl.icheme.world.level.block.entity;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Paint;
import net.minecraft.core.BlockPos;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The cooling towers of one client level that have a cell loaded. A tower's still parts are one mesh drawn from its origin
 * and it spans many chunks, so it stands while any of its cells is loaded, whichever chunks the view leaves out.
 */
public final class LoadedTowers {
    /** One tower, with how many of its cells are loaded. */
    public static final class Tower {
        private final BlockPos origin;
        private EquipmentShape shape;
        private Paint paint;
        private int cells;

        private Tower(BlockPos origin) {
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

    private final Map<BlockPos, Tower> towers = new HashMap<>();

    /** One more cell of the tower at {@code origin} is loaded; the tower takes the shape and paint it carries. */
    public Tower add(BlockPos origin, EquipmentShape shape, Paint paint) {
        var tower = towers.computeIfAbsent(origin.immutable(), Tower::new);
        tower.shape = shape;
        tower.paint = paint;
        tower.cells++;
        return tower;
    }

    /** One cell of {@code tower} left; its last takes the tower with it. */
    public void remove(Tower tower) {
        if (--tower.cells == 0) towers.remove(tower.origin);
    }

    public Collection<Tower> all() {
        return Collections.unmodifiableCollection(towers.values());
    }
}
