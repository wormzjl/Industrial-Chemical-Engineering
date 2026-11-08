from pathlib import Path
b=Path('src/main/java/com/wormzjl/icheme')
def edit(path,old,new):
 p=b/path;s=p.read_text(encoding='utf-8');assert old in s,(path,old[:65]);p.write_text(s.replace(old,new),encoding='utf-8')
edit('runtime/fluid/PhysicalRegistry.java','var control=PhysicalFluidTopology.defaultMover(kind);var record=registrations().get(id);','var record=registrations().get(id);\n        if(record==null)return false;\n        var control=com.wormzjl.icheme.science.machine.InlineMachines.design(record.device().geometry().diameter(),kind==TopologyCompiler.Kind.PUMP);')
edit('runtime/fluid/ElectricStores.java','MOTOR, GENERATOR, FANS, TOWER;','MOTOR, GENERATOR, FANS, TOWER, INLINE;')
edit('runtime/fluid/ElectricStores.java','        var heat = r.device().heat();\n        if (heat', '        if(r.device().kind()==com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.PIPE&&r.device().control() instanceof FlowControl.Mover)return true;\n        var heat = r.device().heat();\n        if (heat')
edit('runtime/fluid/ElectricStores.java','        return r.device().heat() != null ? Kind.FANS : Kind.TOWER;','        return r.device().control() instanceof FlowControl.Mover ? Kind.INLINE : r.device().heat() != null ? Kind.FANS : Kind.TOWER;')
edit('runtime/fluid/ElectricStores.java','            case FANS -> {','''            case INLINE -> {
                var r=topology.active().get(id);
                yield r==null?0:com.wormzjl.icheme.science.machine.InlineMachines.ratedDraw((FlowControl.Mover)r.device().control());
            }
            case FANS -> {''')
edit('runtime/fluid/ElectricStores.java','        sync();\n        for (var s : stores.values())', '''        // Existing inline movers retain their saved custom duty and former unmetered behaviour until wired.
        for(var r:topology.active().values()) if(r.device().kind()==com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.PIPE&&r.device().control() instanceof FlowControl.Mover&&!(r.device().control() instanceof FlowControl.Machine)&&!stores.containsKey(r.device().id()))
            stores.put(r.device().id(),new Store(Kind.INLINE,ElectricStore.Facts.UNMETERED,now));
        sync();
        for (var s : stores.values())''')
edit('runtime/fluid/ElectricStores.java','            if (!(pipe.heat() instanceof AirCooled)) continue;','''            long moverId=graph.reservoirs().get(pipe.first()).id();var moverStore=stores.get(moverId);
            if(moverStore!=null&&moverStore.kind==Kind.INLINE){out.put(moverId,new ShaftPlan(ShaftPlan.Kind.FAILED,powered(moverId)?1:0));continue;}
            if (!(pipe.heat() instanceof AirCooled)) continue;''')
edit('runtime/fluid/ElectricStores.java','        for (var pipe : solved.pipes()) {\n            if (!(pipe.heat() instanceof AirCooled cooled)) continue;', '''        var average=result.averageMassFlows();var heads=result.endpointHeads();
        for(int edge=0;edge<solved.pipes().size();edge++) {
            var pipe=solved.pipes().get(edge);var node=solved.reservoirs().get(pipe.first());var supply=node.state();
            var store=stores.get(node.id());
            if(store!=null&&store.kind==Kind.INLINE&&pipe.control() instanceof FlowControl.Mover mover) {
                double rho=supply.volume()>0?supply.mass()/supply.volume():0;
                double q=rho>0?Math.max(0,average[edge])/rho:0,shaft=0;
                if(rho>0&&mover.enabled())shaft=mover instanceof FlowControl.Machine m?m.law().shaftPower(q,m.speed(),rho,supply.pressure(),1.4):mover.power(Math.max(0,average[edge]),heads[edge],rho,supply.pressure(),1.4)/.65;
                draw(store,com.wormzjl.icheme.science.machine.InlineMachines.electricPower(shaft),start,end,false);
                continue;
            }
            if (!(pipe.heat() instanceof AirCooled cooled)) continue;''')
edit('runtime/fluid/ElectricStores.java','s.kind == Kind.FANS || s.kind == Kind.TOWER','s.kind == Kind.FANS || s.kind == Kind.TOWER || s.kind == Kind.INLINE')
edit('science/fluid/network/ShaftPlan.java','case FlowControl.Pump pump when plan.kind == Kind.FAILED ->','case FlowControl.Compressor c when plan.kind == Kind.FAILED -> new FlowControl.Compressor(c.requestedVolumeFlow(),c.maximumPressureRatio(),plan.value>0&&c.enabled());\n                case FlowControl.Pump pump when plan.kind == Kind.FAILED ->')
edit('science/fluid/network/ShaftPlan.java','a.maximumPressureRatio() == b.maximumPressureRatio() && a.enabled() == b.enabled();','a.maximumPressureRatio() == b.maximumPressureRatio();')
edit('world/level/block/entity/FluidDeviceBlockEntity.java','public final class FluidDeviceBlockEntity extends BlockEntity', 'public final class FluidDeviceBlockEntity extends BlockEntity')
# The capability is only exposed where a registered inline drive owns an electric store.
p=b/'world/level/block/entity/FluidDeviceBlockEntity.java';s=p.read_text(encoding='utf-8');at=s.index('    public ',s.index('public final class'))
s=s[:at]+'''    public net.neoforged.neoforge.energy.IEnergyStorage energy() {
        if(!(level instanceof net.minecraft.server.level.ServerLevel server))return null;
        var world=com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority.find(server.getServer()).orElse(null);
        return world==null||world.electric().generator(fluidIdentity())==null?null:new InlineEnergy(world.electric(),fluidIdentity());
    }
'''+s[at:];p.write_text(s,encoding='utf-8')
edit('IChemE.java','(source,side)->source);','(source,side)->source);\n        event.registerBlockEntity(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,ModBlockEntities.FLUID_DEVICE.get(),(pipe,side)->pipe.energy());')
# Protect the fixed curve during edits, while allowing its integral drive's On/Off switch.
edit('network/FluidNetwork.java','case PUMP->control=new FlowControl.Pump(controls.volumeFlow,controls.maximumAddedPressure,controls.power==Power.ON);','''case PUMP->{if(control instanceof FlowControl.Machine m)control=new FlowControl.Machine(m.law(),m.speed(),controls.power==Power.ON,d.kind()==TopologyCompiler.Kind.PIPE?0:controls.volumeFlow,m.setPressure());else control=new FlowControl.Pump(controls.volumeFlow,controls.maximumAddedPressure,controls.power==Power.ON);}''')
edit('network/FluidNetwork.java','case COMPRESSOR->control=new FlowControl.Compressor(controls.volumeFlow,controls.maximumPressureRatio,controls.power==Power.ON);','''case COMPRESSOR->{if(control instanceof FlowControl.Machine m)control=new FlowControl.Machine(m.law(),m.speed(),controls.power==Power.ON,d.kind()==TopologyCompiler.Kind.PIPE?0:controls.volumeFlow,m.setPressure());else control=new FlowControl.Compressor(controls.volumeFlow,controls.maximumPressureRatio,controls.power==Power.ON);}''')
