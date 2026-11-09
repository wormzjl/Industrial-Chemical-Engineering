from pathlib import Path
b=Path('src/main/java/com/wormzjl/icheme')
def edit(path,old,new):
 p=b/path;s=p.read_text(encoding='utf-8');assert old in s,(path,old[:60]);p.write_text(s.replace(old,new),encoding='utf-8')
edit('runtime/fluid/ShaftTrains.java','double stress,double demand,boolean connected) implements Event {','double stress,double demand,boolean connected,double actualPower) implements Event {\n        public CreateInput(PhysicalFluidTopology.Position cell,double rpm,double stress,double demand,boolean connected){this(cell,rpm,stress,demand,connected,demand);}')
edit('runtime/fluid/ShaftTrains.java','!Double.isFinite(demand))','!Double.isFinite(demand)||!(actualPower>=0)||!Double.isFinite(actualPower)||actualPower>demand+1e-6)')
edit('runtime/fluid/ShaftTrains.java','        if (motor && electric.draw(d.id(), step.electricPower(), now))','''        if(motor) {
            double actualDemand=0;for(var member:train.driven()) {
                var unit=topology.shaft(member.id());var input=unit==null?null:create.get(unit.position());
                actualDemand+=(createLoads.contains(member.id())?(input==null?0:input.actualPower()):run.power.getOrDefault(member.id(),0.0))/member.efficiency();
            }
            step=new ShaftTrain.Step(step.speed(),step.trip(),step.status(),step.governorPower(),step.speed()>0?com.wormzjl.icheme.science.machine.Motor.electricPower(actualDemand,d.ratedPower(),d.poles(),true):0);
        }
        if (motor && electric.draw(d.id(), step.electricPower(), now))''')
edit('runtime/fluid/ShaftTrains.java','Math.min(driver.limit(),Math.max(run.available,step.governorPower()))','Math.min(driver.limit(),run.available)')
edit('compat/create/CreateBridgeBlockEntity.java','private double sentRpm=Double.NaN,sentStress=Double.NaN,sentDemand=Double.NaN;','private double sentRpm=Double.NaN,sentStress=Double.NaN,sentDemand=Double.NaN,sentActual=Double.NaN;')
edit('compat/create/CreateBridgeBlockEntity.java','queue(0,0,0,false);','queue(0,0,0,false,0);')
edit('compat/create/CreateBridgeBlockEntity.java','double demand,boolean connected){','double demand,boolean connected,double actual){')
edit('compat/create/CreateBridgeBlockEntity.java','rpm,spare,demand,connected)))','rpm,spare,demand,connected,actual)))')
edit('compat/create/CreateBridgeBlockEntity.java','Math.min(1,contribution/capacity):0','Math.min(1,contribution/capacity):hasSource()?0:1')
edit('compat/create/CreateBridgeBlockEntity.java','double demand=generated!=0&&rpm>0?','double demand=generated!=0?')
edit('compat/create/CreateBridgeBlockEntity.java','        if(!CreateCoupling.moved','        double actual=rpm>0?demand:0;\n        if(Math.abs(actual-sentActual)<=Math.max(1,Math.abs(sentActual))*.001&&!CreateCoupling.moved')
edit('compat/create/CreateBridgeBlockEntity.java','sentDemand=demand;sentConnection=link;queue(rpm,spare,demand,link);','sentDemand=demand;sentActual=actual;sentConnection=link;queue(rpm,spare,demand,link,actual);')
edit('compat/create/CreateBridgeBlockEntity.java','float speed=power>0&&rpm>0?(float)rpm:0;','float speed=rpm>0?(float)rpm:0;')
# Name a native Create endpoint correctly on the existing read-only coupling page.
edit('runtime/fluid/ShaftTrains.java','    public Coupling coupling(long unit) {','    public ShaftNetwork.Kind kindOf(long unit){var u=topology.shaft(unit);return u!=null&&createDrivers.contains(unit)?ShaftNetwork.Kind.CREATE_COUPLING:u==null?null:u.kind();}\n    public Coupling coupling(long unit) {')
edit('runtime/fluid/ShaftTrains.java','if (u == null || u.kind() != ShaftNetwork.Kind.CREATE_COUPLING) return null;','if (u == null || kindOf(unit) != ShaftNetwork.Kind.CREATE_COUPLING) return null;')
edit('runtime/fluid/MachineView.java','new MachineView(unit, v.kind(), status','new MachineView(unit, trains.kindOf(unit), status')
edit('runtime/fluid/FluidWorldAuthority.java','return new FluidView.Drive(driver.kind().name(),','return new FluidView.Drive(shafts.kindOf(driver.id()).name(),')
edit('runtime/fluid/FluidWorldAuthority.java','driver.kind()==com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.MOTOR&&driver.drive()==com.wormzjl.icheme.science.equipment.MachineDuty.Drive.FIXED','false')
p=b/'network/MachineNetwork.java';s=p.read_text(encoding='utf-8');# Later review verifies both open and validation use the effective page kind.
s=s.replace('var kind = unit.kind();','var kind = world.shafts().kindOf(unit.id());');p.write_text(s,encoding='utf-8')
