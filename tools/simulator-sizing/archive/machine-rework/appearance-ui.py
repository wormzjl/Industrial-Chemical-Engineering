from pathlib import Path
b=Path('src/main/java/com/wormzjl/icheme')
def edit(path,old,new):
 p=b/path;s=p.read_text(encoding='utf-8');assert old in s,(path,old[:60]);p.write_text(s.replace(old,new),encoding='utf-8')
edit('science/machine/InlineMachines.java','1.4,CentrifugalCompressor.DEFAULT_EFFICIENCY),','CentrifugalCompressor.sigma(1.4,CentrifugalCompressor.DEFAULT_EFFICIENCY)),')
edit('runtime/fluid/ElectricStores.java','import com.wormzjl.icheme.science.fluid.network.AirCooled;','import com.wormzjl.icheme.science.fluid.network.AirCooled;\nimport com.wormzjl.icheme.science.fluid.network.FlowControl;')
# A wire field identifies the fixed integral-drive rating. Old constructors remain useful to callers.
p=b/'network/FluidNetwork.java';s=p.read_text(encoding='utf-8');old='com.wormzjl.icheme.science.fluid.network.CoolingTower tower) {';new='com.wormzjl.icheme.science.fluid.network.CoolingTower tower,boolean fixedMover) {';assert old in s;s=s.replace(old,new,1)
at=s.index('        public Controls(',s.index('public record Controls'))
s=s[:at]+'''        public Controls(double temperature,double pressure,double diameter,double roughness,double volumeFlow,double maximumAddedPressure,double[] composition,SlurryFeed solids,double maximumPressureRatio,Power power,FlowControl.ValveSource valveSource,double massFlow,HeatUnit.Control heat,com.wormzjl.icheme.science.fluid.network.CoolingTower tower){this(temperature,pressure,diameter,roughness,volumeFlow,maximumAddedPressure,composition,solids,maximumPressureRatio,power,valveSource,massFlow,heat,tower,false);}
'''+s[at:]
s=s.replace('heat!=null&&heat.controllable()?heat.control():null,record.spec().tower());','heat!=null&&heat.controllable()?heat.control():null,record.spec().tower(),d.kind()==TopologyCompiler.Kind.PIPE&&d.control() instanceof FlowControl.Machine);')
s=s.replace('var d=record.device();double flow=d.control() instanceof FlowControl.Mover m?m.requestedVolumeFlow():.01;','var d=record.device();double flow=d.control() instanceof FlowControl.Machine m?m.law().ratedFlow():d.control() instanceof FlowControl.Mover m?m.requestedVolumeFlow():.01;')
p.write_text(s,encoding='utf-8')
p=b/'client/gui/common/DeviceColumn.java';s=p.read_text(encoding='utf-8');old='@Nullable Power power, @Nullable Suction suction) {';assert old in s;s=s.replace(old,'@Nullable Power power, @Nullable Suction suction,@Nullable String fixedDuty) {',1)
at=s.index('        public Input {')
s=s[:at]+'''        public Input(Kind kind,boolean canEdit,boolean switchable,boolean powerOn,String pressureUnit,String temperatureUnit,String flowTime,Valve valve,@Nullable Opening opening,boolean recoverable,@Nullable Level level,@Nullable String junction,List<Column.Row> lead,@Nullable Phases phases,List<Meter> readings,@Nullable Drive drive,@Nullable Power power,@Nullable Suction suction) {this(kind,canEdit,switchable,powerOn,pressureUnit,temperatureUnit,flowTime,valve,opening,recoverable,level,junction,lead,phases,readings,drive,power,suction,null);}
        public Input withFixedDuty(String text){return new Input(kind,canEdit,switchable,powerOn,pressureUnit,temperatureUnit,flowTime,valve,opening,recoverable,level,junction,lead,phases,readings,drive,power,suction,text);}
'''+s[at:]
s=s.replace('        String p = in.pressureUnit()', '        if(in.fixedDuty()!=null)return List.of(new Column.Group(t.get("inline.rating"),null,List.of(new Column.Paragraph(in.fixedDuty(),Palette.TEXT))));\n        String p = in.pressureUnit()')
p.write_text(s,encoding='utf-8')
p=b/'client/gui/screens/inventory/FluidDeviceScreen.java';s=p.read_text(encoding='utf-8');old='return new DeviceColumn.Input(data.kind(),canEdit(),switchable(),powerOn,pressureUnit.symbol()';assert old in s;s=s.replace(old,'var input=new DeviceColumn.Input(data.kind(),canEdit(),switchable(),powerOn,pressureUnit.symbol()',1)
old='view.filter()!=null&&!view.filter().captured().empty(),level(),junction,lead,phases(),meters(),drive(),power(),suction());';assert old in s;s=s.replace(old,old+'''
        return data.controls().fixedMover()?input.withFixedDuty(tr(data.kind()==TopologyCompiler.Kind.PUMP?"inline.pump_rating":"inline.compressor_rating",value(data.controls().volumeFlow()*3600))):input;''',1);p.write_text(s,encoding='utf-8')
# Alternators use broad ventilated panels; the casting, shaft, feet and terminal positions are retained.
edit('science/equipment/ProcessUnit.java','    void motor(Frame f, double r, double z0, double z1, double shaft, double floor, MotorDetail detail) {','''    void generator(Frame f,double r,double z0,double z1,double floor) {
        int first=mesh.polygons().size();
        motor(f,r,z0,z1,0,floor,MotorDetail.FULL);
        // Shared frame geometry, a distinct smooth ventilated stator instead of the motor's longitudinal fins.
        generatorPanels(first);
    }
    private void generatorPanels(int first) {
        var polygons=mesh.polygons();
        for(int i=first;i<polygons.size();i++) {var p=polygons.get(i);if(p.material()==Material.MOTOR_FRAME||p.material()==Material.MOTOR_PANEL)polygons.set(i,new Mesh.Polygon(p.positions(),p.normals(),p.uvs(),Material.GENERATOR_PANEL));}
    }
    void largeGenerator(double x,double y,double z0,double across,double length,double floor){int first=mesh.polygons().size();largeMotor(x,y,z0,across,length,floor);generatorPanels(first);}
    void motor(Frame f, double r, double z0, double z1, double shaft, double floor, MotorDetail detail) {''')
edit('science/equipment/MotorDesign.java','            u.motor(Frame.of(new double[]{a, y, drive}, 2, -1), r, 0, length, 0, deck, ProcessUnit.MotorDetail.FULL);','''            if(generator)u.generator(Frame.of(new double[]{a,y,drive},2,-1),r,0,length,deck);
            else u.motor(Frame.of(new double[]{a, y, drive}, 2, -1), r, 0, length, 0, deck, ProcessUnit.MotorDetail.FULL);''')
edit('science/equipment/MotorDesign.java','            u.largeMotor(a, y, front, across, length, deck);','            if(generator)u.largeGenerator(a,y,front,across,length,deck);else u.largeMotor(a, y, front, across, length, deck);')
edit('science/equipment/Mesh.java','MOTOR_PANEL(1, Paint.BODY), MOTOR_PLAIN','MOTOR_PANEL(1, Paint.BODY), GENERATOR_PANEL(1, Paint.BODY), MOTOR_PLAIN')
p=Path('src/main/resources/assets/icheme/lang/en_us.json');s=p.read_text(encoding='utf-8').replace('gui.icheme.constructor.motor.','gui.icheme.equipment_constructor.motor.');s=s.rstrip()[:-1].rstrip()+',\n  "gui.icheme.fluid.inline.rating": "Fixed rating",\n  "gui.icheme.fluid.inline.pump_rating": "%s m³/h at 25 m head. The integral drive draws electrical power.",\n  "gui.icheme.fluid.inline.compressor_rating": "%s m³/h at inlet conditions; nominal air pressure ratio 1.5. The integral drive draws electrical power."\n}\n';p.write_text(s,encoding='utf-8')
