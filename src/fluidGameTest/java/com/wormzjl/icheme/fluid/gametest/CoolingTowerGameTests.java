package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** One placed bank is one conserved open basin, with independent inlet port roles. */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class CoolingTowerGameTests {
    private CoolingTowerGameTests() {}
    @GameTest(template="empty",timeoutTicks=400,batch="cooling-tower")
    public static void parallelCellsRegisterAsOneEmptyOpenBasin(GameTestHelper helper){
        var shape=EquipmentShape.unit(EquipmentShape.Kind.INDUCED_DRAFT_COOLING_TOWER,8,2,EquipmentShape.Heading.NORTH,0).withTowerBank(new TowerBank(2,1));
        var origin=helper.absolutePos(new BlockPos(1,1,1));var level=helper.getLevel();
        EquipmentStructure.place(level,origin,shape,Paint.DEFAULT,shape.sizeY());
        var cell=ShapeRasterizer.fluidCell(shape);var node=origin.offset(cell.x(),cell.y(),cell.z());
        var position=new PhysicalFluidTopology.Position(level.dimension().location().toString(),node.getX(),node.getY(),node.getZ());
        var world=FluidWorldAuthority.find(level.getServer()).orElseThrow();
        helper.startSequence().thenWaitUntil(()->{
            var registration=world.at(position).orElseThrow();
            helper.assertTrue(registration.spec().open(),"The basin is open to the atmosphere");
            helper.assertTrue(registration.spec().tower().cells()==2,"Both parallel cells share this basin");
            helper.assertTrue(registration.spec().volume()==128,"Capacity comes from both cell footprints");
            helper.assertTrue(registration.device().ports().size()==2,"Two bank nozzles");
            helper.assertTrue(registration.device().ports().stream().anyMatch(p->p.port()==PassiveNetwork.PhasePort.SPRAY),"Hot return is an atmospheric spray inlet");
        })
        // The bank leaves with its registration: GameTest areas are not cleared between batches, and a later test needs a
        // world with no registration (FluidHarnessGameTests' cleanup check; the basin stayed as a live island before).
        .thenExecute(()->EquipmentTestCleanup.remove(level,node))
        .thenWaitUntil(()->helper.assertTrue(world.at(position).isEmpty(),"Waiting for the basin's registration to leave"))
        .thenSucceed();
    }
}
