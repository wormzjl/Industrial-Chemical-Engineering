package com.wormzjl.icheme.client.fx.state;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.network.FluidNetwork;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.HeatSides;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * The client's store of the process states the effects and the process sounds draw (batch 2026-11-10-custom-shader-system,
 * W3 to W6), each by a {@link CellWatch}: the flare stacks' flames ({@link FluidNetwork.FlareState}, watched by
 * {@link FluidNetwork.Watch#FLARES}), the batch stills' fires ({@link FluidNetwork.StillState}, {@link FluidNetwork.Watch#STILLS}),
 * the cooling towers' plumes ({@link FluidNetwork.TowerPlume}, {@link FluidNetwork.Watch#TOWERS}) and the fired heaters'
 * flues ({@link FluidNetwork.HeaterFlue}, {@link FluidNetwork.Watch#HEATERS}). Client thread only. The effects
 * ({@code client/fx/emitter/}) and the sounds read {@link #flares()}, {@link #stills()}, {@link #towers()} and
 * {@link #heaters()}, and place their parts by the structure's marks ({@code ShapeRasterizer.marks}: {@code flare_tip},
 * {@code door}, {@code flue_top}, {@code fan_exhaust_<i>}, {@code throat}, {@code stack_top_<i>}); they add no process state.
 * Replaces the flares' and stills' own watches ({@code FlareFlames}, {@code StillFires}, deleted with their particles).
 */
@EventBusSubscriber(modid=IChemE.MOD_ID,value=Dist.CLIENT)
public final class ProcessStates {
    /** Plumes and flues are watched within this distance of the player, blocks: inside the server's watch range. */
    static final double SIGHT=FluidNetwork.WATCH_RANGE-8;
    /** Flares are watched within this distance, blocks: the server keeps a flare watch only within its mover range. */
    static final double FLARE_SIGHT=FluidNetwork.MOVER_RANGE-8;
    /** Stills are watched within this distance, blocks, as before: a still is a few metres across. */
    static final double STILL_SIGHT=48;
    private static final CellWatch<FluidNetwork.FlareState> FLARES=new CellWatch<>(FluidNetwork.Watch.FLARES,k->k==EquipmentShape.Kind.FLARE_STACK,FLARE_SIGHT);
    private static final CellWatch<FluidNetwork.StillState> STILLS=new CellWatch<>(FluidNetwork.Watch.STILLS,k->k==EquipmentShape.Kind.BATCH_STILL,STILL_SIGHT);
    private static final CellWatch<FluidNetwork.TowerPlume> TOWERS=new CellWatch<>(FluidNetwork.Watch.TOWERS,EquipmentShape.Kind::coolingTower,SIGHT);
    private static final CellWatch<FluidNetwork.HeaterFlue> HEATERS=new CellWatch<>(FluidNetwork.Watch.HEATERS,HeatSides::burners,SIGHT);
    private static final List<CellWatch<?>> ALL=List.of(FLARES,STILLS,TOWERS,HEATERS);
    private static ClientLevel world;
    private static int age;
    private ProcessStates(){}

    /** A shell cell loaded on the client (its call site in {@code EquipmentShellBlockEntity}). */
    public static void track(BlockEntity entity){for(var w:ALL)w.track(entity);}
    /** A shell cell unloaded or removed on the client. */
    public static void forget(BlockEntity entity){for(var w:ALL)w.forget(entity);}
    /** The server's flame state for a flare this client watches. */
    public static void accept(FluidNetwork.FlareState state){FLARES.accept(state.target(),state);}
    /** The server's fire state for a still this client watches. */
    public static void accept(FluidNetwork.StillState state){STILLS.accept(state.target(),state);}
    /** The server's plume for a tower this client watches. */
    public static void accept(FluidNetwork.TowerPlume state){TOWERS.accept(state.target(),state);}
    /** The server's flue for a heater this client watches. */
    public static void accept(FluidNetwork.HeaterFlue state){HEATERS.accept(state.target(),state);}

    /** The watched flare stacks of the current level with their last flame state. */
    public static List<CellWatch.Watched<FluidNetwork.FlareState>> flares(){return FLARES.active(Minecraft.getInstance().level);}
    /** The watched batch stills of the current level with their last fire state. */
    public static List<CellWatch.Watched<FluidNetwork.StillState>> stills(){return STILLS.active(Minecraft.getInstance().level);}
    /** The watched towers of the current level with their last plume. */
    public static List<CellWatch.Watched<FluidNetwork.TowerPlume>> towers(){return TOWERS.active(Minecraft.getInstance().level);}
    /** The watched fired heaters of the current level with their last flue. */
    public static List<CellWatch.Watched<FluidNetwork.HeaterFlue>> heaters(){return HEATERS.active(Minecraft.getInstance().level);}
    /** The client left the world: every watch and state goes. */
    public static void clear(){for(var w:ALL)w.clear();world=null;age=0;}

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var mc=Minecraft.getInstance();
        if(world!=mc.level){
            boolean connected=mc.getConnection()!=null;
            for(var w:ALL)w.levelChanged(mc.level,connected);
            world=mc.level;age=0;
        }
        if(mc.level==null||mc.player==null||mc.getConnection()==null||mc.isPaused())return;
        if(age++%20!=0)return;
        var point=mc.player.position();
        long stored=0;for(var w:ALL){w.request(mc.level,point);stored+=w.stored();}
        com.wormzjl.icheme.client.fx.FxStats.set(com.wormzjl.icheme.client.fx.FxStats.Counter.STATES,stored);
    }
}
