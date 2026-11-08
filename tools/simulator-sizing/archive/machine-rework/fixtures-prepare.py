from pathlib import Path
p=Path('tmp/machine-rework/DriveFactsCorpus.java');s=p.read_text(encoding='utf-8').replace('PipeResistance.Geometry.straight(1,.05)','new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0)').replace('new WorldTopologyLedger.Restated(total,List.of())','new WorldTopologyLedger.Restated(0,Map.of(),Map.of())');p.write_text(s,encoding='utf-8')
p=Path('tmp/machine-rework/MotorCorpus.java');s=p.read_text(encoding='utf-8');start=s.index('        var table=');end=s.index('        Files.write(table,previous);',start)
s=s[:start]+'''        var table=Path.of("src/test/resources/compat/layouts/layouts.txt");var previous=Files.readAllLines(table);int at=0;
        for(var kind:List.of(EquipmentShape.Kind.MOTOR,EquipmentShape.Kind.GENERATOR)) {
            int sizeIndex=0;double low=ProcessUnit.minDiameter(kind),high=ProcessUnit.maxDiameter(kind);
            var sizes=new double[]{low,ProcessUnit.snap(kind,(low+high)/2),high};
            for(int i=0;i<previous.size();i++)if(previous.get(i).startsWith("EquipmentShape[kind="+kind+",")) {
                double size=sizes[sizeIndex++];at++;int length=ProcessUnit.defaultLength(kind,size);
                var shape=EquipmentShape.designed(kind,size,length,EquipmentShape.Heading.NORTH,ProcessUnit.defaultSupport(kind,size,length),ProcessUnit.Bank.ONE,0);
                var layout=ShapeRasterizer.layout(shape);var text=new StringBuilder(shape.toString().replace(", baseSpeed=0.0]","]").replace(", exchangerType=STANDARD]","]"));
                text.append(" | box ").append(shape.sizeX()).append(' ').append(shape.sizeY()).append(' ').append(shape.sizeZ()).append(" | shell ").append(layout.shell().size()).append(" interior ").append(layout.interior().size()).append(" | stamp ").append(Long.toHexString(ShapeRasterizer.stamp(shape))).append(" | pipes");
                for(var nozzle:ShapeRasterizer.ports(shape))text.append(' ').append(nozzle.cell().x()+nozzle.dx()).append(',').append(nozzle.cell().y()+nozzle.dy()).append(',').append(nozzle.cell().z()+nozzle.dz());
                var terminal=ShapeRasterizer.terminal(shape);if(terminal!=null)text.append(" | terminal ").append(terminal.x()).append(',').append(terminal.y()).append(',').append(terminal.z());
                previous.set(i,text.toString());
            }
        }
'''+s[end:];p.write_text(s,encoding='utf-8')
p=Path('src/test/java/com/wormzjl/icheme/science/machine/RotatingDriveReworkTest.java');s=p.read_text(encoding='utf-8');at=s.index('    @Test void nativeCreateShaft');s=s[:at]+'''    @Test void pistonDemandSurvivesAPlannedDisplacementChange() {
        var design=new com.wormzjl.icheme.science.fluid.network.FlowControl.Compressor(.01,3,true,.008,220000);
        var planned=design.withDisplacement(.005).withEnabled(false);
        assertEquals(.008,planned.setFlow());assertEquals(220000,planned.setPressure());
        assertEquals(.01,design.requestedVolumeFlow());
    }
'''+s[at:];p.write_text(s,encoding='utf-8')
