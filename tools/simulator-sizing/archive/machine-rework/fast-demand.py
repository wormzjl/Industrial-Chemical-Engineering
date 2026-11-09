from pathlib import Path
b=Path('src/main/java/com/wormzjl/icheme')
def edit(path,old,new):
 p=b/path;s=p.read_text(encoding='utf-8');assert old in s,(path,old[:60]);p.write_text(s.replace(old,new),encoding='utf-8')
p=b/'science/machine/SpeedControl.java';s=p.read_text(encoding='utf-8');at=s.index('    private static double clamp')
s=s[:at]+'''    /** Invert one finite speed line at the requested duty, rather than ramping through multiple controller slices. */
    public static double requiredSpeed(MachineLaw law,double flow,double head,double density,double pressure,double k,double lowest,double highest) {
        if(!(flow>=0)||!(density>0)||!(pressure>0)||!Double.isFinite(flow+head+density+pressure))return Double.NaN;
        double low=lowest,high=highest;
        if(law.rise(flow,low,density,pressure,k)>=head)return low;
        if(law.rise(flow,high,density,pressure,k)<=head)return high;
        for(int i=0;i<40;i++){double middle=(low+high)/2;if(law.rise(flow,middle,density,pressure,k)<head)low=middle;else high=middle;}
        return (low+high)/2;
    }
'''+s[at:];p.write_text(s,encoding='utf-8')
edit('science/machine/ShaftTrain.java','        /** The speed the driver aims at before any limit, rpm. */','''        public Driver atSpeed(double rpm){return new Driver(drive,ratedPower,designSpeed,poles,running,Mode.SPEED,rpm/designSpeed,powerLimit,trip,governed,powered);}
        /** The speed the driver aims at before any limit, rpm. */''') if False else None
p=b/'science/machine/ShaftTrain.java';s=p.read_text(encoding='utf-8');at=s.index('        public double setSpeed()');s=s[:at]+'''        public Driver atSpeed(double rpm){return new Driver(drive,ratedPower,designSpeed,poles,running,Mode.SPEED,rpm/designSpeed,powerLimit,trip,governed,powered);}
'''+s[at:];p.write_text(s,encoding='utf-8')
edit('runtime/fluid/ShaftTrains.java','private record Duty(double flow,double pressure,double setFlow,double setPressure) {}','private record Duty(double flow,double pressure,double setFlow,double setPressure,double flowSpeed,double pressureSpeed) {}')
edit('runtime/fluid/ShaftTrains.java','new Duty(q,solved.reservoirs().get(pipe.second()).state().pressure(),m.setFlow(),m.setPressure())','''new Duty(q,solved.reservoirs().get(pipe.second()).state().pressure(),m.setFlow(),m.setPressure(),
                            m.setFlow()>0?com.wormzjl.icheme.science.machine.SpeedControl.requiredSpeed(m.law(),m.setFlow(),heads[e],density,suction.pressure(),1.4,com.wormzjl.icheme.science.machine.Motor.LOWEST_SPEED,com.wormzjl.icheme.science.machine.Motor.HIGHEST_SPEED):Double.NaN,
                            m.setPressure()>0?com.wormzjl.icheme.science.machine.SpeedControl.requiredSpeed(m.law(),q,m.setPressure()-suction.pressure(),density,suction.pressure(),1.4,com.wormzjl.icheme.science.machine.Motor.LOWEST_SPEED,com.wormzjl.icheme.science.machine.Motor.HIGHEST_SPEED):Double.NaN)''')
edit('runtime/fluid/ShaftTrains.java','        String automatic=null;','        String automatic=null;double requestedRpm=Double.NaN;')
edit('runtime/fluid/ShaftTrains.java','                if(!(run.target>0))automatic="NO DUTY TARGET";','''                if(!(run.target>0))automatic="NO DUTY TARGET";
                else if(sample!=null){double share=flow?sample.flowSpeed():sample.pressureSpeed();var member=train.driven().stream().filter(m->m.id()==candidates.getFirst().id()).findFirst().orElseThrow();requestedRpm=share*candidates.getFirst().designSpeed()/member.ratio();}''')
edit('runtime/fluid/ShaftTrains.java','        var step = ShaftTrain.step(driver, state.speed(), measure);','        if(Double.isFinite(requestedRpm))driver=driver.atSpeed(Math.clamp(requestedRpm,driver.designSpeed()*com.wormzjl.icheme.science.machine.Motor.LOWEST_SPEED,driver.designSpeed()*com.wormzjl.icheme.science.machine.Motor.HIGHEST_SPEED));\n        var step = ShaftTrain.step(driver, state.speed(), measure);')
# Both setpoints belong to the designed casing; choosing Auto flow or Auto pressure picks which is used.
edit('network/FluidNetwork.java','d.kind()==TopologyCompiler.Kind.PIPE?0:controls.volumeFlow,m.setPressure()','d.kind()==TopologyCompiler.Kind.PIPE?0:controls.volumeFlow,d.kind()==TopologyCompiler.Kind.PIPE?0:controls.pressure')
edit('network/FluidNetwork.java','var valve=d.control() instanceof FlowControl.ControlValve v?v:null;','if(d.control() instanceof FlowControl.Machine m&&m.setPressure()>0)pressure=m.setPressure();\n            var valve=d.control() instanceof FlowControl.ControlValve v?v:null;')
edit('client/gui/common/DeviceColumn.java','case PUMP -> List.of(group(setPoint, suctionFlow(in, t, volume)),','case PUMP -> in.drive()!=null?List.of(group(setPoint,suctionFlow(in,t,volume),field("pressure",t,"discharge_target",p))):List.of(group(setPoint, suctionFlow(in, t, volume)),')
edit('client/gui/common/DeviceColumn.java','case COMPRESSOR -> List.of(group(setPoint, suctionFlow(in, t, volume)),','case COMPRESSOR -> in.drive()!=null?List.of(group(setPoint,suctionFlow(in,t,volume),field("pressure",t,"discharge_target",p))):List.of(group(setPoint, suctionFlow(in, t, volume)),')
# New material is part of the normal model texture catalog.
p=Path('src/main/resources/assets/icheme/models/block/equipment_shell.json');s=p.read_text(encoding='utf-8').replace('"motor_panel": "icheme:block/motor_panel",','"motor_panel": "icheme:block/motor_panel",\n    "generator_panel": "icheme:block/generator_panel",');p.write_text(s,encoding='utf-8')
p=Path('src/main/resources/assets/icheme/lang/en_us.json');s=p.read_text(encoding='utf-8');s=s.rstrip()[:-1].rstrip()+',\n  "gui.icheme.fluid.device_field.discharge_target": "Discharge target",\n  "gui.icheme.fluid.device_field.discharge_target.hint": "Absolute discharge pressure used by the linked VFD in Auto pressure mode. Then press Apply."\n}\n';p.write_text(s,encoding='utf-8')
import sys;sys.path.insert(0,'tmp/process-control');from PIL import Image,ImageDraw
im=Image.new('RGB',(64,64),(143,151,153));d=ImageDraw.Draw(im)
d.rectangle((1,1,62,62),outline=(85,94,97),width=2)
for y in (9,38):
 d.rectangle((7,y,56,y+15),fill=(72,84,88),outline=(182,191,190),width=1)
 for row in range(y+3,y+14,4):d.rectangle((9,row,54,row+1),fill=(29,39,43))
d.rectangle((23,27,40,35),fill=(28,87,96),outline=(209,191,94),width=1)
# Output electricity symbol, visible without relying on the equipment's paint.
d.line((33,28,28,31,35,31,30,34),fill=(235,207,100),width=2)
for x in (4,59):
 for y in (4,59):d.rectangle((x,y,x+1,y+1),fill=(47,55,58))
im.save(Path('src/main/resources/assets/icheme/textures/block/generator_panel.png'))
