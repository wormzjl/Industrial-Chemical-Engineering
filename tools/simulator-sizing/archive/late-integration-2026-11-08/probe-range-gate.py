from pathlib import Path
p=Path('src/test/java/com/wormzjl/icheme/science/equipment/EquipmentServicesTest.java');s=p.read_text(encoding='utf-8');at=s.index('    @Test void orthogonalSurfaceRuns')
s=s[:at]+'''    @Test void fittedProbeRoutesQualifyAtConstructorSizeExtremesAndBothDetails(){
        var failures=new ArrayList<String>();int checked=0;
        for(var shape:EquipmentLayoutGoldenTest.shapes()){
            var probes=EquipmentInstruments.of(shape).probes();if(probes.isEmpty())continue;
            var ids=probes.stream().map(EquipmentInstruments.Probe::id).collect(java.util.stream.Collectors.toSet());
            for(var detail:RenderDetail.values()){checked++;try{var drawing=ShapeRasterizer.instrumentWiring(shape,ids,detail);if(!drawing.unresolved().isEmpty())failures.add(shape.kind()+" "+shape.diameter()+" "+shape.tangentLength()+" "+detail+" "+drawing.unresolved());}catch(RuntimeException e){failures.add(shape.kind()+" "+shape.diameter()+" "+detail+" "+e.getMessage());}}
        }
        System.out.println("native_instrument_finish_profiles="+checked);assertTrue(failures.isEmpty(),String.join("\\n",failures));
    }
''' +s[at:];p.write_text(s,encoding='utf-8')
