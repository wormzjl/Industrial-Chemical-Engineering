from pathlib import Path
b=Path('src/main/java/com/wormzjl/icheme')
def edit(path,old,new):
 p=b/path;s=p.read_text(encoding='utf-8');assert old in s,(path,old[:60]);p.write_text(s.replace(old,new),encoding='utf-8')
edit('runtime/fluid/ElectricStores.java','if(moverStore!=null&&moverStore.kind==Kind.INLINE){out.put(moverId,new ShaftPlan(ShaftPlan.Kind.FAILED,powered(moverId)?1:0));continue;}','''if(moverStore!=null&&moverStore.kind==Kind.INLINE){
                var registration=topology.active().get(moverId);var design=registration==null?null:registration.device().control();
                boolean running=powered(moverId)&&design instanceof FlowControl.Mover mover&&mover.enabled();
                out.put(moverId,new ShaftPlan(design instanceof FlowControl.Machine?ShaftPlan.Kind.SPEED:ShaftPlan.Kind.FAILED,running?1:0));continue;
            }''')
edit('science/fluid/network/ShaftPlan.java','c.withEnabled(plan.value>0&&c.enabled())','c.withEnabled(plan.value>0)')
edit('runtime/fluid/ShaftTrains.java','public void dataChanged(){for(var run:runs.values())run.fresh=true;}','''public void dataChanged(){
        var wake=new TreeSet<Long>();
        for(var run:runs.values()){run.fresh=true;for(var member:run.train.driven()){var u=topology.shaft(member.id());if(u!=null&&u.fluid()>0)owners(u.fluid(),wake);}}
        if(!wake.isEmpty())coordinator.shaftsMoved(wake);
    }''')
# Capability callers receive only the storage interface, not the mutable store owner.
p=b/'world/level/block/entity/InlineEnergy.java';s=p.read_text(encoding='utf-8').replace('public record InlineEnergy(ElectricStores stores,long id) implements IEnergyStorage {','''final class InlineEnergy implements IEnergyStorage {
    private final ElectricStores stores;
    private final long id;
    InlineEnergy(ElectricStores stores,long id){this.stores=stores;this.id=id;}''');p.write_text(s,encoding='utf-8')
p=Path('src/test/resources/compat/equipment/shapes-10.snbt');s=p.read_text(encoding='utf-8');p.write_text(s.replace('\r\n','\n'),encoding='utf-8',newline='\n')
