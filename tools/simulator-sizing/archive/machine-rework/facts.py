from pathlib import Path
b=Path('src/main/java/com/wormzjl/icheme')
def edit(path,old,new):
 p=b/path;s=p.read_text(encoding='utf-8');assert old in s,(path,old[:50]);p.write_text(s.replace(old,new),encoding='utf-8')
edit('runtime/fluid/PhysicalFluidTopology.java','public Device withGeometry(PipeResistance.Geometry value){return new Device(id,position,kind,facing,value,control,ports,generatorEnabled,cutoff,inlineFilter,joints,slots,points,heat,suspendedBy);}','''public Device withGeometry(PipeResistance.Geometry value){
            var changed=control;
            if(kind==TopologyCompiler.Kind.PIPE&&control instanceof FlowControl.Machine m&&value.diameter()!=geometry.diameter()) {
                var fixed=com.wormzjl.icheme.science.machine.InlineMachines.design(value.diameter(),m.liquid());changed=new FlowControl.Machine(fixed.law(),1,m.enabled());
            }
            return new Device(id,position,kind,facing,value,changed,ports,generatorEnabled,cutoff,inlineFilter,joints,slots,points,heat,suspendedBy);
        }''')
edit('runtime/fluid/MachineView.java','u.drive() == com.wormzjl.icheme.science.equipment.MachineDuty.Drive.VARIABLE','u.kind()==ShaftNetwork.Kind.MOTOR||u.drive() == com.wormzjl.icheme.science.equipment.MachineDuty.Drive.VARIABLE')
edit('runtime/fluid/ShaftTrains.java','        default void output(PhysicalFluidTopology.Position port,double rpm,double watts) {}','        default void output(PhysicalFluidTopology.Position port,double rpm,double watts) {}\n        default void removed(long id) {}')
edit('runtime/fluid/ShaftTrains.java','if (old != null) topology.removeShaft(old.id());','if (old != null){host.removed(old.id());topology.removeShaft(old.id());}')
# Removal branch uses its own bound name.
p=b/'runtime/fluid/ShaftTrains.java';s=p.read_text(encoding='utf-8');s=s.replace('topology.removeShaft(u.id());','host.removed(u.id());topology.removeShaft(u.id());');p.write_text(s,encoding='utf-8')
edit('runtime/fluid/FluidWorldAuthority.java','        @Override public Set<Long> linked(long motor){return controls().connectedHosts(motor);}','        @Override public Set<Long> linked(long motor){return controls().connectedHosts(motor);}\n        @Override public void removed(long id){controls().removeHost(id);}')
# Advance the owning shape-field identity; old string snapshots omit the appended default.
p=Path('src/test/java/com/wormzjl/icheme/science/equipment/EquipmentShapeCompatTest.java');s=p.read_text(encoding='utf-8').replace('SHAPE_FIELDS = 9','SHAPE_FIELDS = 10');s=s.replace('                    assertEquals(expected, read.shape().toString(), file.toString());','''                    if(!expected.contains("baseSpeed="))expected=expected.replaceAll("(exhaust=[0-9.Ee+-]+)\\\\]","$1, baseSpeed=0.0]");
                    assertEquals(expected, read.shape().toString(), file.toString());''');# Legacy pre-duty defaults remain the old pole-defined motor.
s=s.replace('if (condensing) duty = MachineDuty.turbine','if(shape.kind()==Kind.MOTOR)duty=MachineDuty.motor(shape.diameter(),4,MachineDuty.Drive.FIXED);\n                        if (condensing) duty = MachineDuty.turbine');p.write_text(s,encoding='utf-8')
# Keep the existing layout table readable for zero-valued new fields; new motors get their explicit speed recorded.
p=Path('src/test/java/com/wormzjl/icheme/science/equipment/EquipmentLayoutGoldenTest.java');s=p.read_text(encoding='utf-8').replace('var printed = shape.toString();','var printed = shape.toString().replace(", baseSpeed=0.0]","]");');p.write_text(s,encoding='utf-8')
