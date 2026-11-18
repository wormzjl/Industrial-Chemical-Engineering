package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.PlantTag;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/**
 * Default plant tags (batch 2026-11-18-default-equipment-names): an unnamed build takes the next free tag of its kind in
 * its dimension, on every shell cell and in the registry's record; a second build takes the next number; a named build
 * keeps its name; a tag cleared by a rename stays cleared when the structure restates. Every drum is dismantled at the end
 * (the GameTest world is reused).
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class DefaultNameGameTests {
    private DefaultNameGameTests() {}

    @GameTest(template="empty",timeoutTicks=300,batch="equipment-default-names")
    public static void anUnnamedBuildTakesTheNextFreeTagOfItsKind(GameTestHelper helper) {
        var level=helper.getLevel();var world=FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var shape=EquipmentShape.drum(3,3,EquipmentShape.Orientation.VERTICAL,EquipmentShape.Heading.NORTH,0);
        var layout=ShapeRasterizer.layout(shape);var step=shape.sizeX()+2;
        var dimension=level.dimension().location().toString();
        var origins=List.of(helper.absolutePos(new BlockPos(1,1,1)),helper.absolutePos(new BlockPos(1+step,1,1)),helper.absolutePos(new BlockPos(1+2*step,1,1)));
        java.util.function.Function<BlockPos,PhysicalFluidTopology.Position> at=o->new PhysicalFluidTopology.Position(dimension,o.getX(),o.getY(),o.getZ());
        String[] failure={null};
        var before=world.structureNames(dimension);
        var first=PlantTag.tag(EquipmentShape.Kind.DRUM,before);
        EquipmentStructure.place(level,origins.get(0),shape,Paint.DEFAULT,Integer.MAX_VALUE);
        var taken=new HashSet<>(before);taken.add(first);
        var second=PlantTag.tag(EquipmentShape.Kind.DRUM,taken);
        EquipmentStructure.place(level,origins.get(1),shape,Paint.DEFAULT,Integer.MAX_VALUE);
        EquipmentStructure.place(level,origins.get(2),shape,Paint.DEFAULT,"Feed drum",Integer.MAX_VALUE);
        var wanted=List.of(first,second,"Feed drum");
        for(int i=0;i<3&&failure[0]==null;i++) {
            var held=world.structure(at.apply(origins.get(i)));
            if(held==null||!held.name().equals(wanted.get(i)))failure[0]="drum "+i+" is recorded as "+(held==null?null:held.name())+", not "+wanted.get(i);
            for(var c:layout.shell())
                if(failure[0]==null&&!((EquipmentShellBlockEntity)level.getBlockEntity(origins.get(i).offset(c.x(),c.y(),c.z()))).name().equals(wanted.get(i)))failure[0]="cell "+c+" of drum "+i+" lost its name";
        }
        if(failure[0]==null&&!first.startsWith("V-"))failure[0]="a drum's tag is "+first;
        var vessel=new EquipmentStructure.Vessel(origins.get(0),shape);
        if(failure[0]==null&&!EquipmentStructure.rename(level,vessel,""))failure[0]="the rename to no name was refused";
        EquipmentStructure.restateLater(level,origins.get(0),shape);
        helper.startSequence().thenIdle(10).thenExecute(()->{
            var held=world.structure(at.apply(origins.get(0)));
            if(failure[0]==null&&(held==null||!held.name().isEmpty()))failure[0]="a cleared tag came back in the record: "+(held==null?null:held.name());
            if(failure[0]==null&&!EquipmentStructure.name(level,vessel).isEmpty())failure[0]="a cleared tag came back on the cells";
            var c=layout.controller();
            for(var o:origins)EquipmentTestCleanup.remove(level,o.offset(c.x(),c.y(),c.z()));
        }).thenWaitUntil(()->{for(var o:origins)helper.assertTrue(world.structure(at.apply(o))==null,"Waiting for the drums' records to leave");})
        .thenExecute(()->{if(failure[0]!=null)helper.fail(failure[0]);}).thenSucceed();
    }
}
