from pathlib import Path
b=Path('src/main/java/com/wormzjl/icheme')
def edit(path,old,new):
 p=b/path;s=p.read_text(encoding='utf-8');assert old in s,(path,old[:60]);p.write_text(s.replace(old,new),encoding='utf-8')
edit('science/fluid/network/FlowControl.java','record Compressor(double requestedVolumeFlow,double maximumPressureRatio,boolean enabled) implements Mover {','''record Compressor(double requestedVolumeFlow,double maximumPressureRatio,boolean enabled,double setFlow,double setPressure) implements Mover {
        public Compressor(double requestedVolumeFlow,double maximumPressureRatio,boolean enabled){this(requestedVolumeFlow,maximumPressureRatio,enabled,0,0);}
        public Compressor withDisplacement(double flow){return new Compressor(flow,maximumPressureRatio,enabled,setFlow,setPressure);}
        public Compressor withEnabled(boolean value){return new Compressor(requestedVolumeFlow,maximumPressureRatio,value,setFlow,setPressure);}''')
edit('science/fluid/network/FlowControl.java','        public Compressor {','''        public Compressor {
            if(!(setFlow>=0)||setFlow>MAXIMUM_VOLUME_FLOW||!(setPressure>=0)||!Double.isFinite(setFlow+setPressure))throw new IllegalArgumentException("Invalid compressor demand");''')
edit('runtime/fluid/FluidFacts.java','static final int SCHEMA=13;','static final int SCHEMA=14;')
edit('runtime/fluid/FluidFacts.java','f64(compressor.maximumPressureRatio()).bool(compressor.enabled()).f64(0);','f64(compressor.maximumPressureRatio()).bool(compressor.enabled()).f64(0).f64(compressor.setFlow()).f64(compressor.setPressure());')
edit('runtime/fluid/FluidFacts.java','case "compressor"->new FlowControl.Compressor(flow,limit,enabled);','case "compressor"->new FlowControl.Compressor(flow,limit,enabled,f.f64(0),f.f64(0));')
edit('runtime/fluid/FluidCheckpointCodec.java','w.bool(compressor.enabled());}','w.bool(compressor.enabled());w.f64(compressor.setFlow());w.f64(compressor.setPressure());}')
edit('runtime/fluid/FluidCheckpointCodec.java','case 3->new FlowControl.Compressor(r.f64(),r.f64(),r.bool());','case 3->new FlowControl.Compressor(r.f64(),r.f64(),r.bool(),r.f64(),r.f64());')
edit('science/fluid/network/ShaftPlan.java','new FlowControl.Compressor(c.requestedVolumeFlow(),c.maximumPressureRatio(),plan.value>0&&c.enabled())','c.withEnabled(plan.value>0&&c.enabled())')
edit('science/fluid/network/ShaftPlan.java','new FlowControl.Compressor(flow, compressor.maximumPressureRatio(), compressor.enabled())','compressor.withDisplacement(flow)')
edit('science/fluid/network/ShaftPlan.java','a.maximumPressureRatio() == b.maximumPressureRatio();','a.maximumPressureRatio() == b.maximumPressureRatio() && a.setFlow()==b.setFlow() && a.setPressure()==b.setPressure();')
edit('runtime/fluid/PhysicalFluidTopology.java','new FlowControl.Compressor(0,compressor.maximumPressureRatio())','compressor.withDisplacement(0)')
edit('network/FluidNetwork.java','if(d.control() instanceof FlowControl.Machine m&&m.setPressure()>0)pressure=m.setPressure();','if(d.control() instanceof FlowControl.Machine m&&m.setPressure()>0)pressure=m.setPressure();\n            if(d.control() instanceof FlowControl.Compressor c){if(c.setFlow()>0)flow=c.setFlow();if(c.setPressure()>0)pressure=c.setPressure();}')
old='else control=new FlowControl.Compressor(controls.volumeFlow,controls.maximumPressureRatio,controls.power==Power.ON);'
edit('network/FluidNetwork.java',old,'''else if(control instanceof FlowControl.Compressor c&&world.shafts().unitOf(d.id()))control=new FlowControl.Compressor(c.requestedVolumeFlow(),c.maximumPressureRatio(),controls.power==Power.ON,controls.volumeFlow,controls.pressure);else control=new FlowControl.Compressor(controls.volumeFlow,controls.maximumPressureRatio,controls.power==Power.ON);''')
# The piston law already expresses flow/head at a displacement share. Invert it inside the same bounded scalar solve.
p=b/'science/machine/SpeedControl.java';s=p.read_text(encoding='utf-8');at=s.index('    private static double clamp')
s=s[:at]+'''    public static double requiredSpeed(PositiveDisplacement law,double flow,double head,double lowest,double highest) {
        if(!(flow>=0)||!Double.isFinite(flow+head))return Double.NaN;
        double low=lowest,high=highest;
        if(law.rise(flow,low)>=head)return low;
        if(law.rise(flow,high)<=head)return high;
        for(int i=0;i<40;i++){double middle=(low+high)/2;if(law.rise(flow,middle)<head)low=middle;else high=middle;}
        return (low+high)/2;
    }
'''+s[at:];p.write_text(s,encoding='utf-8')
edit('runtime/fluid/ShaftTrains.java','                case FlowControl.Compressor c -> run.power.put(unit, density > 0 ? c.power(Math.max(0, flows[e]), heads[e], density, suction.pressure(), 1.4) : 0);','''                case FlowControl.Compressor c -> {
                    run.power.put(unit,density>0?c.power(Math.max(0,flows[e]),heads[e],density,suction.pressure(),1.4):0);
                    var registration=topology.latest().get(node.id());
                    if(density>0&&registration!=null&&registration.device().control() instanceof FlowControl.Compressor design){
                        double q=Math.max(0,flows[e])/density,pressure=solved.reservoirs().get(pipe.second()).state().pressure();
                        var law=new com.wormzjl.icheme.science.machine.PositiveDisplacement(design.requestedVolumeFlow(),design.riseLimit(density,suction.pressure(),density,1.4));
                        run.duties.put(unit,new Duty(q,pressure,design.setFlow(),design.setPressure(),
                                design.setFlow()>0?com.wormzjl.icheme.science.machine.SpeedControl.requiredSpeed(law,design.setFlow(),heads[e],.1,1.5):Double.NaN,
                                design.setPressure()>0?com.wormzjl.icheme.science.machine.SpeedControl.requiredSpeed(law,q,design.setPressure()-suction.pressure(),.1,1.5):Double.NaN));
                    }
                }''')
# Validate a zero-flow design without constructing a positive-displacement law of zero capacity.
edit('runtime/fluid/ShaftTrains.java','if(density>0&&registration!=null&&registration.device().control() instanceof FlowControl.Compressor design){','if(density>0&&registration!=null&&registration.device().control() instanceof FlowControl.Compressor design&&design.requestedVolumeFlow()>0){')
