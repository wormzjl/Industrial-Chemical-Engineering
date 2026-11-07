package com.wormzjl.icheme.science.equipment;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConduitGeometryTest {
    @Test void motorAndGeneratorConnectorsFaceOppositeSidesAtEveryHeading(){
        for(var kind:new EquipmentShape.Kind[]{EquipmentShape.Kind.MOTOR,EquipmentShape.Kind.GENERATOR})
            for(var heading:EquipmentShape.Heading.values())for(double size:new double[]{.75,22,400,500,1000,20000}){
                var shape=EquipmentShape.unit(kind,size,1,heading,0);
                var data=DataSocketGeometry.equipment(shape);var power=DataSocketGeometry.power(shape);
                assertNotNull(data,shape.toString());assertNotNull(power,shape.toString());
                assertEquals(data.face()^1,power.face(),shape.toString());
                assertEquals(data.y(),power.y(),1e-12,shape.toString());
                var transverse=LineGeometry.step(data.face());
                if(transverse[0]!=0)assertEquals(data.z(),power.z(),1e-12,shape.toString());
                else assertEquals(data.x(),power.x(),1e-12,shape.toString());
                var n=LineGeometry.step(power.face());
                assertTrue((power.x()-data.x())*n[0]+(power.z()-data.z())*n[2]>.25,shape.toString());
            }
    }
    @Test void fixedPowerDiameterRetainsThePipeFaceCoordinates(){
        assertEquals(2*CableMedium.DATA.outside(),CableMedium.POWER.outside());
        var data=new ConduitGeometry.Key(0,0).connect(4,-7,9).connect(5,3,-4);
        var power=new ConduitGeometry.Key(0,0,CableMedium.POWER).connect(4,-7,9).connect(5,3,-4);
        assertArrayEquals(data.point(4),power.point(4));assertArrayEquals(data.point(5),power.point(5));
        for(var medium:CableMedium.values())for(int joints:new int[]{48,36,52})for(var p:ConduitGeometry.polygons(new ConduitGeometry.Key(joints,0,medium))){
            for(double v:p.positions())assertTrue(Double.isFinite(v));
            assertEquals(p.size()*2,p.uvs().length);assertEquals(p.size()*3,p.normals().length);
        }
    }
    @Test void mountsPreferBlockFaceCentresAndKeepOffsetsOnlyForSmallSpans(){
        assertEquals(1.5,DataSocketGeometry.centered(1.3,.6,2.1));
        assertEquals(.5,DataSocketGeometry.centered(.7,.1,.9));
        assertEquals(.8,DataSocketGeometry.centered(.8,.7,.95));
        var broad=new Mesh();broad.box(new double[]{0,.1,.1},new double[]{1,2.1,2.1},63,Mesh.Material.MACHINE);
        var socket=DataSocketGeometry.surface(broad.facets().stream().map(Mesh::whole).toList());
        assertNotNull(socket);assertEquals(.5,socket.y()%1,1e-12);assertEquals(.5,socket.z()%1,1e-12);
        var small=new Mesh();small.box(new double[]{0,.68,.68},new double[]{.2,.92,.92},63,Mesh.Material.MACHINE);
        socket=DataSocketGeometry.surface(small.facets().stream().map(Mesh::whole).toList());
        assertNotNull(socket);assertEquals(.8,socket.y(),1e-12);assertEquals(.8,socket.z(),1e-12);
        var choice=new Mesh();choice.box(new double[]{0,.2,.7},new double[]{1,2.8,.9},63,Mesh.Material.MACHINE);
        choice.box(new double[]{0,.3,2.3},new double[]{1,.7,2.7},63,Mesh.Material.MACHINE);
        socket=DataSocketGeometry.surface(choice.facets().stream().map(Mesh::whole).toList());
        assertNotNull(socket);assertEquals(.5,socket.y(),1e-12);assertEquals(2.5,socket.z(),1e-12,"An available centered face takes precedence over a larger off-centre face");
        for(var kind:new EquipmentShape.Kind[]{EquipmentShape.Kind.MOTOR,EquipmentShape.Kind.GENERATOR})for(var heading:EquipmentShape.Heading.values()){
            socket=DataSocketGeometry.equipment(EquipmentShape.unit(kind,1000,1,heading,0));
            int axis=LineGeometry.axis(socket.face());double[] p={socket.x(),socket.y(),socket.z()};
            for(int k=0;k<3;k++)if(k!=axis)assertEquals(.5,p[k]%1,1e-12);
        }
    }
    @Test void serviceSocketsClearTheCastingsAcrossEveryEquipmentFamily(){
        int checked=0,nativeData=0,powered=0;
        var kinds=java.util.EnumSet.noneOf(EquipmentShape.Kind.class);
        var buried=new java.util.ArrayList<String>();
        for(var shape:EquipmentLayoutGoldenTest.shapes()){
            kinds.add(shape.kind());
            var data=DataSocketGeometry.equipment(shape);var power=DataSocketGeometry.power(shape);
            if(data!=null){checkClear(shape,data,buried);nativeData++;}
            if(power!=null){checkClear(shape,power,buried);powered++;}
            var node=ShapeRasterizer.fluidCell(shape);
            for(var socket:InstrumentGeometry.sockets(shape,node)){
                assertTrue(Double.isFinite(socket.x()+socket.y()+socket.z()),shape.toString());
            }
            checked++;
        }
        assertEquals(java.util.EnumSet.allOf(EquipmentShape.Kind.class),kinds);
        System.out.printf("SERVICE_PORT_SWEEP shapes=%d native_data=%d power=%d%n",checked,nativeData,powered);
        assertTrue(buried.isEmpty(),"Sockets buried in castings:\n"+String.join("\n",buried));
    }
    @Test void largeMotorPortsUseTheCabinetAndTheSolidStripBetweenVents(){
        for(var kind:new EquipmentShape.Kind[]{EquipmentShape.Kind.MOTOR,EquipmentShape.Kind.GENERATOR})for(double kw:new double[]{500,1000,20000}){
            var shape=EquipmentShape.unit(kind,kw,1,EquipmentShape.Heading.NORTH,0);var u=ProcessUnit.of(shape);
            var data=u.dataSocket;var power=u.powerSocket;double length=1.7*MotorDesign.frame(kw)+.2;
            assertEquals(4,data.face());assertEquals(5,power.face());assertTrue(data.junctionBox());assertFalse(power.junctionBox());
            assertTrue(data.z()-.14>=.35+.42*length-1e-9&&data.z()+.14<=.35+.58*length+1e-9,"Accessory box obstructs a vent or end cover: "+kw);
            double terminal=Math.min(1.2,.3+.2*MotorDesign.frame(kw));
            assertTrue(Math.abs(power.z()-u.terminalBox[2])+.11<=terminal/2+1e-9,"Power gland is outside the existing terminal cabinet");
            assertTrue(Math.abs(power.y()-u.terminalBox[1])+.11<=.35*terminal+1e-9,"Power gland is outside the existing terminal cabinet");
        }
    }
    @Test void shortConnectorLeadsDoNotFoldBackAndOverlappingGlandsNeedNoExtraLead(){
        for(var medium:CableMedium.values()){
            var n=new double[]{1,0,0};assertTrue(ConduitGeometry.lead(n,new double[]{-.02,0,0},n,medium).isEmpty());
            var end=new double[]{.2,0,0};var expected=LineGeometry.conduit(java.util.List.of(new double[3],end),medium.outside());
            var actual=ConduitGeometry.lead(n,end,n,medium);assertEquals(expected.size(),actual.size());
            for(int i=0;i<actual.size();i++)assertArrayEquals(expected.get(i).positions(),actual.get(i).positions());
        }
    }
    private static void checkClear(EquipmentShape shape,DataSocketGeometry.Socket socket,java.util.List<String> buried){
        assertTrue(Double.isFinite(socket.x()+socket.y()+socket.z()),shape.toString());
        var n=LineGeometry.step(socket.face());
        if(ShapeRasterizer.inside(shape,socket.x()+.06*n[0],socket.y()+.06*n[1],socket.z()+.06*n[2]))buried.add(shape+" / "+socket);
    }
}
