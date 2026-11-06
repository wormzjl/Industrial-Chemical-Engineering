package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.fluid.network.HeatSide;
import com.wormzjl.icheme.science.fluid.network.HeatRouting;
import com.wormzjl.icheme.science.fluid.network.PipeResistance;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class CrossflowEquipmentTest {
    @Test void everyBuildableSizeAndHeadingLeavesSeparateFreePipeBlocks() {
        var kind = EquipmentShape.Kind.EXCHANGER;
        int checked = 0;
        for (double d = ProcessUnit.minDiameter(kind); d <= ProcessUnit.maxDiameter(kind); d += .25)
            for (int length = ProcessUnit.minLength(kind, d); length <= ProcessUnit.maxLength(kind, d); length++)
                for (var heading : EquipmentShape.Heading.values()) {
                    var shape = EquipmentShape.unit(kind, d, length, heading, ProcessUnit.minSupport(kind, d, length)).withExchangerType(ExchangerType.CROSSFLOW);
                    var layout = ShapeRasterizer.layout(shape);
                    var pipes = new HashSet<ShapeRasterizer.Cell>();
                    for (var port : ShapeRasterizer.ports(shape)) {
                        var pipe = new ShapeRasterizer.Cell(port.cell().x() + port.dx(), port.cell().y() + port.dy(), port.cell().z() + port.dz());
                        assertTrue(pipes.add(pipe), shape.toString());
                        assertFalse(layout.shell().contains(pipe), shape + ": occupied pipe " + pipe);
                        assertFalse(layout.interior().contains(pipe), shape + ": interior pipe " + pipe);
                    }
                    ProcessUnitTest.pipesApart(shape);
                    assertEquals(4, shape.nozzles().size());
                    assertEquals(2, ShapeRasterizer.fluidCells(shape).size());
                    checked++;
                }
        assertTrue(checked > 200);
    }

    @Test void crossflowKeepsTwoTubePassesAndReducesOnlyTheShellResistance() {
        var shape=EquipmentShape.unit(EquipmentShape.Kind.EXCHANGER,1,3,EquipmentShape.Heading.NORTH,2);
        assertEquals("",UnitDesign.of(shape.kind()).variant(shape));
        assertEquals(".crossflow",UnitDesign.of(shape.kind()).variant(shape.withExchangerType(ExchangerType.CROSSFLOW)));
        for (double d : new double[]{.5, 1, 1.5, 2.5}) for (int length : new int[]{(int)Math.ceil(3*d), 12}) {
            var standard = ExchangerType.STANDARD;
            var cross = ExchangerType.CROSSFLOW;
            assertEquals(standard.tubePassage(d, length), cross.tubePassage(d, length));
            assertEquals(2*length, cross.tubePassage(d, length).length());
            assertEquals(.1*d*length, cross.shellPassage(d, length).area(), .00013);
            assertEquals(2.5*d, cross.shellPassage(d, length).length());
            for (double density : new double[]{1.2, 998}) {
                double a = PipeResistance.evaluate(standard.shellPassage(d, length), 10, density, .0001).pressureDrop();
                double b = PipeResistance.evaluate(cross.shellPassage(d, length), 10, density, .0001).pressureDrop();
                assertTrue(b < a / 3, d + " x " + length + ": " + b + " vs " + a);
            }
        }
        for (var side : new HeatSides.Side[]{HeatSides.Side.TUBE, HeatSides.Side.SHELL}) {
            var law = assertInstanceOf(HeatSide.class, HeatSides.heatSide(HeatSides.Unit.EXCHANGER, side, 450, 1, 0, 99, null, ExchangerType.CROSSFLOW));
            assertEquals(64, law.cells());
            assertEquals(side == HeatSides.Side.TUBE ? HeatRouting.TWO_PASS_TUBES : HeatRouting.MIXED_CROSSFLOW, law.routing());
        }
    }

    @Test void constructorSubTypeSurvivesCopiesAndDormantSelections() {
        var fresh = com.wormzjl.icheme.world.level.block.entity.EquipmentConstructorBlockEntity.Design.DEFAULT;
        var design = fresh.withExchangerType(ExchangerType.CROSSFLOW);
        var tag = new CompoundTag();
        com.wormzjl.icheme.world.level.block.entity.EquipmentConstructorBlockEntity.writeDesign(tag, design);
        var read = com.wormzjl.icheme.world.level.block.entity.EquipmentConstructorBlockEntity.readDesign(tag, fresh, repair -> fail(repair));
        assertEquals(design, read);
        assertEquals(ExchangerType.CROSSFLOW, read.withName("E-1").exchangerType());
        tag.remove("ExchangerType");
        assertEquals(ExchangerType.STANDARD, com.wormzjl.icheme.world.level.block.entity.EquipmentConstructorBlockEntity.readDesign(tag, fresh, repair -> fail(repair)).exchangerType());
    }

    @Test void constructorCorpusKeepsBuiltAndDormantConfigurations() throws Exception {
        var corpus=java.nio.file.Path.of("src/test/resources/compat/constructor/designs-1.snbt");
        var fresh=com.wormzjl.icheme.world.level.block.entity.EquipmentConstructorBlockEntity.Design.DEFAULT;
        if(!java.nio.file.Files.exists(corpus)) {
            var shape=EquipmentShape.unit(EquipmentShape.Kind.EXCHANGER,2.25,7,EquipmentShape.Heading.NORTH,2).withExchangerType(ExchangerType.CROSSFLOW);
            var built=fresh.landed(new SentDesign("E-1","",1,shape,null));
            var lines=new java.util.ArrayList<String>();
            for(var design:java.util.List.of(fresh,built,fresh.withExchangerType(ExchangerType.CROSSFLOW))) {
                var tag=new CompoundTag();com.wormzjl.icheme.world.level.block.entity.EquipmentConstructorBlockEntity.writeDesign(tag,design);
                if(design==fresh)tag.remove("ExchangerType");
                var image=new CompoundTag();image.put("Saved",tag);image.putString("Expected",design.toString());lines.add(image.toString());
            }
            var written=java.nio.file.Path.of("build/compat/constructor/designs-1.snbt");java.nio.file.Files.createDirectories(written.getParent());java.nio.file.Files.write(written,lines);
            fail("Copy constructor corpus from "+written+" to "+corpus);
        }
        for(var line:java.nio.file.Files.readAllLines(corpus)) {
            var image=net.minecraft.nbt.TagParser.parseTag(line);
            var read=com.wormzjl.icheme.world.level.block.entity.EquipmentConstructorBlockEntity.readDesign(image.getCompound("Saved"),fresh,repair->fail(repair));
            assertEquals(image.getString("Expected"),read.toString());
        }
    }
}
