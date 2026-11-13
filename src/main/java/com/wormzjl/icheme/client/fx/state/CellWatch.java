package com.wormzjl.icheme.client.fx.state;

import com.wormzjl.icheme.network.FluidNetwork;
import com.wormzjl.icheme.network.FluidNetwork.Target;
import com.wormzjl.icheme.runtime.fluid.FluidView;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Client-thread-only watch of one kind of equipment's published state (batch 2026-11-10-custom-shader-system, W3 to W6;
 * the flares' and stills' since W3 and W6 replaced {@code FlareFlames} and {@code StillFires}, whose watches this one
 * generalised): a structure is tracked at its fluid cell ({@link ShapeRasterizer#fluidCell}), where its
 * identity binds, through the shell cells' load and remove call sites; once a second the client asks for the nearest
 * {@link FluidNetwork#MAXIMUM_WATCHED} within {@link #sight} ({@link FluidNetwork.Watch}), and asks again for a watched one
 * until its first state arrives (registration is queued). The server publishes each state on its presentation cadence;
 * nothing here infers process state. A level change drops every watch. The watched set, the states and the request's
 * difference are a {@link ClientWatch} (C11); this keeps the cells, their targets and the range.
 *
 * @param <S> the published state
 */
public final class CellWatch<S> {
    /** A watched structure in the current level: its fluid cell's shell, its target and its last published state. */
    public record Watched<S>(EquipmentShellBlockEntity cell,Target target,S state) {}

    private final CustomPacketPayload.Type<FluidNetwork.Watch> kind;
    private final Predicate<EquipmentShape.Kind> kinds;
    private final double sight;
    private final Set<EquipmentShellBlockEntity> cells=Collections.newSetFromMap(new WeakHashMap<>());
    private final ClientWatch<Target,S> watch;

    /** A watch of the structures of {@code kinds} by the payload {@code kind}, within {@code sight} blocks of the player. */
    CellWatch(CustomPacketPayload.Type<FluidNetwork.Watch> kind,Predicate<EquipmentShape.Kind> kinds,double sight) {
        this.kind=Objects.requireNonNull(kind);this.kinds=Objects.requireNonNull(kinds);this.sight=sight;
        watch=new ClientWatch<>(FluidNetwork.MAXIMUM_WATCHED,(in,out)->FluidNetwork.request(kind,in,out));
    }

    /** Lifecycle callers must be on the client thread. A structure is tracked only at its fluid cell. */
    void track(BlockEntity entity){if(entity instanceof EquipmentShellBlockEntity shell){if(target(shell)!=null)cells.add(shell);else cells.remove(shell);}}
    void forget(BlockEntity entity) {
        if(!(entity instanceof EquipmentShellBlockEntity shell)||!cells.remove(shell))return;
        var gone=watch.forget(t->t.position().equals(shell.getBlockPos()));
        if(!gone.isEmpty()&&Minecraft.getInstance().getConnection()!=null)FluidNetwork.request(kind,List.of(),gone);
    }
    /** The server's state for {@code target}, kept while this client watches it. */
    void accept(Target target,S state){watch.accept(target,state);}

    /** The level changed (or the client left): every watch ends; cells of another level are dropped. */
    void levelChanged(@Nullable ClientLevel level,boolean connected) {
        watch.reset(connected);cells.removeIf(cell->cell.getLevel()!=level);
    }
    void clear(){watch.clear();cells.clear();}

    /** The once-a-second request: the nearest structures within sight of {@code point}, watched; the rest, unwatched. */
    void request(ClientLevel level,Vec3 point) {
        var near=new ArrayList<Target>();
        for(var cell:List.copyOf(cells)){var t=target(cell);if(t!=null&&cell.getLevel()==level&&t.position().distToCenterSqr(point)<=sight*sight)near.add(t);}
        watch.request(near,Comparator.comparingDouble((Target t)->t.position().distToCenterSqr(point)).thenComparingLong(Target::identity));
    }

    /** The watched structures of {@code level} whose first state has arrived, in no particular order. */
    public List<Watched<S>> active(@Nullable ClientLevel level) {
        if(level==null)return List.of();
        var out=new ArrayList<Watched<S>>();
        for(var cell:List.copyOf(cells)) {
            var t=target(cell);if(t==null||cell.getLevel()!=level)continue;
            var s=watch.state(t);if(s!=null)out.add(new Watched<>(cell,t,s));
        }
        return out;
    }
    /** The states held now ({@code FxStats}). */
    int stored(){return watch.stored();}
    /** The last state published for {@code target}, or null. */
    public @Nullable S state(Target target){return watch.state(target);}

    /** {@code shell}'s target: its fluid cell's position and identity, for a structure of this watch's kinds; null otherwise. */
    @Nullable Target target(EquipmentShellBlockEntity shell) {
        var shape=shell.shape();var origin=shell.origin();
        if(shell.isRemoved()||shape==null||origin==null||!kinds.test(shape.kind())||!(shell instanceof FluidView.Receiver receiver)||receiver.fluidIdentity()<=0)return null;
        var cell=ShapeRasterizer.fluidCell(shape);
        return cell!=null&&origin.offset(cell.x(),cell.y(),cell.z()).equals(shell.getBlockPos())?new Target(shell.getBlockPos(),receiver.fluidIdentity()):null;
    }
}
