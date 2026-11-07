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
}
