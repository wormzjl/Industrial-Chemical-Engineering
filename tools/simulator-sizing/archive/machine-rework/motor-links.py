from pathlib import Path
base=Path('src/main/java/com/wormzjl/icheme')
def edit(path,old,new):
 p=base/path;s=p.read_text(encoding='utf-8');assert old in s,(path,old[:65]);p.write_text(s.replace(old,new),encoding='utf-8')
edit('runtime/control/DataPorts.java','    public static DataPort panel(ControlPanelBlockEntity panel){','''    public static DataPort motor(EquipmentShellBlockEntity cell,com.wormzjl.icheme.runtime.fluid.ShaftUnit unit) {
        var shaft=unit.ends().getFirst();var face=Direction.fromDelta(shaft.dx(),shaft.dy(),shaft.dz());
        var centre=new Vec3(shaft.cell().x()+.5,shaft.cell().y()+.8,shaft.cell().z()+.5);
        return create(UUID.randomUUID(),DataPort.Kind.ACTUATOR,unit.id(),cell.getLevel(),cell.getBlockPos(),centre,face);
    }
    public static DataPort panel(ControlPanelBlockEntity panel){''')
edit('runtime/control/DataPorts.java','import com.wormzjl.icheme.world.level.block.entity.ControlPanelBlockEntity;','import com.wormzjl.icheme.world.level.block.entity.ControlPanelBlockEntity;\nimport com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;')
edit('runtime/control/ControlService.java','r.device().control() instanceof FlowControl.ControlValve||r.device().heat()!=null','r.device().control() instanceof FlowControl.ControlValve||r.device().control() instanceof FlowControl.Mover||r.device().heat()!=null')
edit('runtime/control/ControlService.java','    public void removeHost(long host){','''    public void bindMotor(EquipmentShellBlockEntity cell,ShaftUnit unit) {
        var port=equipmentPorts.get(unit.id());
        if(port==null){port=DataPorts.motor(cell,unit);facts.port(port);equipmentPorts.put(unit.id(),port);}
        if(!port.equals(cell.instruments().dataPort())) {
            cell.instruments().dataPort(port);cell.setChanged();InstrumentHosts.loaded(cell);
            cell.getLevel().sendBlockUpdated(cell.getBlockPos(),cell.getBlockState(),cell.getBlockState(),2);
        }
    }
    /** Connected physical host IDs, independent of chunk loading and menu subscriptions. */
    public Set<Long> connectedHosts(long host) {
        var port=equipmentPorts.get(host);if(port==null)return Set.of();
        var result=new HashSet<Long>();for(var id:bus().connected(port.id())){var p=facts.port(id);if(p!=null&&p.host()>0&&p.host()!=host)result.add(p.host());}return Set.copyOf(result);
    }
    public void removeHost(long host){''')
edit('runtime/fluid/FluidWorldAuthority.java','        controls().tick(topology.onlineTick());','''        if(topology.onlineTick()%100==0) for(var motor:shafts.units()) if(motor.kind()==com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.MOTOR) {
            var motorLevel=server.getLevel(dimension(motor.position().dimension()));
            var pos=new BlockPos(motor.position().x(),motor.position().y(),motor.position().z());
            if(motorLevel!=null&&motorLevel.hasChunkAt(pos)&&motorLevel.getBlockEntity(pos) instanceof com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity cell)controls().bindMotor(cell,motor);
        }
        controls().tick(topology.onlineTick());''')
edit('runtime/fluid/FluidWorldAuthority.java','    private final class ShaftHost implements ShaftTrains.Host {','''    private final class ShaftHost implements ShaftTrains.Host {
        @Override public Set<Long> linked(long motor){return controls().connectedHosts(motor);}''')
edit('runtime/fluid/ShaftTrains.java','        Host NONE = new Host() {','''        /** Hosts connected to a VFD over its data cable. */
        default Set<Long> linked(long motor) { return Set.of(); }
        Host NONE = new Host() {''')
edit('runtime/fluid/ShaftTrains.java','        final Map<Long, Double> output = new HashMap<>();','''        final Map<Long, Double> output = new HashMap<>();
        final Map<Long,Duty> duties=new HashMap<>();''')
edit('runtime/fluid/ShaftTrains.java','    /** One train\'s engine state. */','''    private record Duty(double flow,double pressure,double setFlow,double setPressure) {}
    /** One train's engine state. */''')
edit('runtime/fluid/ShaftTrains.java','                    if (first(run, unit)) {','''                    run.duties.put(unit,new Duty(q,solved.reservoirs().get(pipe.second()).state().pressure(),m.setFlow(),m.setPressure()));
                    if (first(run, unit)) {''')
edit('runtime/fluid/ShaftTrains.java','        var measure = new ShaftTrain.Measure(demand, run.available, steamShort, run.measured, run.target, state.previousSpeed(), state.previousMeasured(), run.available > 0 || idleSupply);','''        String automatic=null;
        if(d.kind()==ShaftNetwork.Kind.MOTOR&&d.settings().mode()!=ShaftTrain.Mode.SPEED) {
            var linked=host.linked(d.id());
            var candidates=train.driven().stream().map(m->topology.shaft(m.id())).filter(Objects::nonNull).filter(u->u.fluid()>0&&linked.contains(u.fluid())).toList();
            automatic=candidates.isEmpty()?"NO DATA LINK":candidates.size()>1?"MULTIPLE DUTIES":null;
            if(automatic==null) {
                var sample=run.duties.get(candidates.getFirst().id());boolean flow=d.settings().mode()==ShaftTrain.Mode.REGULATE_FLOW;
                run.measured=sample==null?Double.NaN:flow?sample.flow():sample.pressure();
                run.target=sample==null?Double.NaN:flow?sample.setFlow():sample.setPressure();
                if(!(run.target>0))automatic="NO DUTY TARGET";
            }
        }
        var measure = new ShaftTrain.Measure(demand, run.available, steamShort, run.measured, run.target, state.previousSpeed(), state.previousMeasured(), run.available > 0 || idleSupply);''')
edit('runtime/fluid/ShaftTrains.java','        var step = ShaftTrain.step(driver, state.speed(), measure);','''        var step = ShaftTrain.step(driver, state.speed(), measure);
        if(automatic!=null&&driver.running()&&driver.powered()&&step.trip()==ShaftTrain.Trip.NONE)
            step=new ShaftTrain.Step(Math.min(state.speed(),step.speed()),ShaftTrain.Trip.NONE,automatic+" / LAST SPEED",0,state.speed()>0?step.electricPower():0);''')
# Cable edits wake the train once, independent of whether a menu is open.
edit('runtime/fluid/ShaftTrains.java','    public List<ShaftUnit> units() {','''    public void dataChanged(){for(var run:runs.values())run.fresh=true;}
    public List<ShaftUnit> units() {''')
edit('runtime/control/ControlService.java','    private long busRevision=-1;','    private long busRevision=-1,driveRevision=-1;')
edit('runtime/control/ControlService.java','        bus();\n        while(!edits.isEmpty())','        bus();\n        if(driveRevision!=busRevision){driveRevision=busRevision;world.shafts().dataChanged();}\n        while(!edits.isEmpty())')
