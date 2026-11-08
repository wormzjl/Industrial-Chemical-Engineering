from pathlib import Path
p=Path('src/test/java/com/wormzjl/icheme/runtime/control/BuiltInInputsTest.java');s=p.read_text(encoding='utf-8');at=s.index('    @Test void builtInsDoNotAllocate')
s=s[:at]+'''    @Test void columnChannelsHaveUniqueStableRolesForNativeAndExternalStreams(){
        var column=EquipmentShape.column(4,ColumnTrays.simple(20,10),EquipmentShape.Heading.NORTH,4);var inputs=BuiltInInputs.channels(binding(column));
        assertEquals(inputs.size(),inputs.stream().map(ControlInput::instance).distinct().count());var names=inputs.stream().map(ControlInput::channel).collect(java.util.stream.Collectors.toSet());
        assertTrue(names.containsAll(Set.of("top_pressure","top_temperature","bottom_pressure","bottom_temperature","reflux_pressure","reflux_temperature","reflux_flow_rate","boundary_feed_flow_rate","boundary_distillate_pressure","boundary_off_gas_temperature","boundary_sour_water_flow_rate","boundary_bottoms_flow_rate")));
        assertTrue(inputs.stream().filter(i->i.role().equals("REFLUX")).count()==3);
        assertFalse(BuiltInInputs.volumeAvailable(inputs.stream().filter(i->i.channel().equals("reflux_flow_rate")).findFirst().orElseThrow()));
        var air=EquipmentShape.unit(EquipmentShape.Kind.AIR_COOLER,3,6,EquipmentShape.Heading.NORTH,4);assertEquals(6,BuiltInInputs.member(binding(air,10)).stream().filter(i->i.role().startsWith("AIR:")).count());
    }
''' +s[at:];p.write_text(s,encoding='utf-8')
p=Path('src/test/java/com/wormzjl/icheme/runtime/fluid/HeatPresentationTest.java');s=p.read_text(encoding='utf-8');at=s.index('    /**\n     * An air cooler')
s=s[:at]+'''    @Test void acceptedPortVolumesAreRatesAtTheSameDensityAsTheirMassFlow(){
        var state=water(310,200000);var node=new PassiveNetwork.Reservoir(10,0,state);var source=source(Map.of(),null,Map.of(),Map.of());
        var publicState=FluidView.State.from(state,state.volume());
        for(double flow:new double[]{-3,0,7}){
            var port=HeatPresentation.port("OUTLET",node,flow,source);assertEquals(flow,port.massFlow());
            assertEquals(Math.abs(flow)*state.volume()/publicState.mass(),Arrays.stream(port.phaseVolumes()).sum(),1e-10,"Volume rate must not be the node inventory");
            double mass=0;var n=port.phaseMoles();var weights=model.molecularWeights();for(var phase:n)for(int c=0;c<phase.length;c++)mass+=phase[c]*weights[c];assertEquals(Math.abs(flow),mass,1e-10);
        }
    }
''' +s[at:];p.write_text(s,encoding='utf-8')
