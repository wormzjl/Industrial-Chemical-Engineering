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
 * The client's store of the process states the effects draw (batch 2026-11-10-custom-shader-system, W4, W5): the cooling
 * towers' plumes ({@link FluidNetwork.TowerPlume}, watched by {@link FluidNetwork.Watch#TOWERS}) and the fired heaters'
 * flues ({@link FluidNetwork.HeaterFlue}, {@link FluidNetwork.Watch#HEATERS}), each by a {@link CellWatch}. Client thread
 * only. The effects read {@link #towers()} and {@link #heaters()}, and place each emitter by the structure's marks
 * ({@code ShapeRasterizer.marks}: {@code fan_exhaust_<i>}, {@code throat}, {@code stack_top_<i>}); they add no process state.
 */
@EventBusSubscriber(modid=IChemE.MOD_ID,value=Dist.CLIENT)
public final class ProcessStates {
    /** Plumes and flues are watched within this distance of the player, blocks: inside the server's watch range. */
    static final double SIGHT=FluidNetwork.WATCH_RANGE-8;
    private static final CellWatch<FluidNetwork.TowerPlume> TOWERS=new CellWatch<>(FluidNetwork.Watch.TOWERS,EquipmentShape.Kind::coolingTower,SIGHT);
    private static final CellWatch<FluidNetwork.HeaterFlue> HEATERS=new CellWatch<>(FluidNetwork.Watch.HEATERS,HeatSides::burners,SIGHT);
    private static ClientLevel world;
    private static int age;
    private ProcessStates(){}

    /** A shell cell loaded on the client (its call site in {@code EquipmentShellBlockEntity}). */
    public static void track(BlockEntity entity){TOWERS.track(entity);HEATERS.track(entity);}
    /** A shell cell unloaded or removed on the client. */
    public static void forget(BlockEntity entity){TOWERS.forget(entity);HEATERS.forget(entity);}
    /** The server's plume for a tower this client watches. */
    public static void accept(FluidNetwork.TowerPlume state){TOWERS.accept(state.target(),state);}
    /** The server's flue for a heater this client watches. */
    public static void accept(FluidNetwork.HeaterFlue state){HEATERS.accept(state.target(),state);}

    /** The watched towers of the current level with their last plume. */
    public static List<CellWatch.Watched<FluidNetwork.TowerPlume>> towers(){return TOWERS.active(Minecraft.getInstance().level);}
    /** The watched fired heaters of the current level with their last flue. */
    public static List<CellWatch.Watched<FluidNetwork.HeaterFlue>> heaters(){return HEATERS.active(Minecraft.getInstance().level);}
    public static void clear(){TOWERS.clear();HEATERS.clear();world=null;age=0;}

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var mc=Minecraft.getInstance();
        if(world!=mc.level){
            boolean connected=mc.getConnection()!=null;
            TOWERS.levelChanged(mc.level,connected);HEATERS.levelChanged(mc.level,connected);
            world=mc.level;age=0;
        }
        if(mc.level==null||mc.player==null||mc.getConnection()==null||mc.isPaused())return;
        if(age++%20!=0)return;
        var point=mc.player.position();
        TOWERS.request(mc.level,point);HEATERS.request(mc.level,point);
    }
}
