from pathlib import Path
p=Path('src/test/java/com/wormzjl/icheme/science/equipment/ConduitGeometryTest.java');s=p.read_text(encoding='utf-8');start=s.index('    @Test void largeMotorPorts');end=s.index('    @Test',start+5);s=s[:start]+'''    @Test void largeMotorPortsUseMatchedHousingsOnTheSolidCentreStrip(){
        for(var kind:new EquipmentShape.Kind[]{EquipmentShape.Kind.MOTOR,EquipmentShape.Kind.GENERATOR})for(double kw:new double[]{500,1000,20000}){
            var shape=EquipmentShape.unit(kind,kw,1,EquipmentShape.Heading.NORTH,0);var service=ShapeRasterizer.services(shape);var data=service.data();var power=service.power();
            assertEquals(5,data.face());assertEquals(4,power.face());assertFalse(data.junctionBox());assertFalse(power.junctionBox());assertEquals(2,service.enclosures().size());
            var a=service.enclosures().getFirst();var b=service.enclosures().getLast();var lo=a.low();var hi=a.high();var low=b.low();var high=b.high();for(int k=0;k<3;k++)assertEquals(hi[k]-lo[k],high[k]-low[k],1e-10,"Matched DATA and FE housing sizes");
            assertEquals(data.y(),power.y(),1e-10);assertEquals(data.z(),power.z(),1e-10);
        }
    }
''' +s[end:]
s=s.replace('var back=new double[]{socket.x()-.18*n[0],socket.y()-.18*n[1],socket.z()-.18*n[2]};','var back=new double[]{socket.x()-.04*n[0],socket.y()-.04*n[1],socket.z()-.04*n[2]};')
start=s.index('                if(length>=2)');end=s.index('\n',start);s=s[:start]+'''                var housing=ShapeRasterizer.services(s).enclosures().getFirst().centre();for(int k=0;k<3;k++)if(k!=axis)assertEquals(housing[k],p[k],1e-10,"Gland is centred on the actual enclosure face");'''+s[end:]
p.write_text(s,encoding='utf-8')
