from pathlib import Path
b=Path('src/main/java/com/wormzjl/icheme')
def edit(path,old,new):
 p=b/path;s=p.read_text(encoding='utf-8');assert old in s,(path,old[:55]);p.write_text(s.replace(old,new),encoding='utf-8')
edit('client/gui/screens/inventory/MachineScreen.java','        if (v != null) nameplate(canvas, v);','''        if (v != null) {
            if(kind()==MachineColumn.Kind.MOTOR)com.wormzjl.icheme.client.gui.common.MotorCurve.draw(canvas,glass(),v.ratedPower(),v.designSpeed(),v.speed(),v.shaftPower(),MachineScreen::tr);
            else nameplate(canvas,v);
        }''')
edit('client/gui/common/MachineColumn.java','if (v.poles() > 0)','if (v.poles() > 0 && v.kind()!=ShaftNetwork.Kind.MOTOR)')
edit('client/gui/common/MachineColumn.java','(v.variable() ? "drive.variable" : "drive.fixed")','"drive.variable"')
edit('client/gui/common/MachineColumn.java','status.equals("UNGOVERNED")','status.equals("UNGOVERNED") || status.contains("NO DATA LINK") || status.contains("MULTIPLE DUTIES") || status.contains("NO DUTY TARGET")')
edit('runtime/fluid/ShaftTrains.java','if (u != null && u.kind() == ShaftNetwork.Kind.CREATE_COUPLING) impact(u, 0);','if (u != null){if(u.kind()==ShaftNetwork.Kind.CREATE_COUPLING||create.containsKey(u.position()))host.output(u.position(),0,0);if(u.kind() == ShaftNetwork.Kind.CREATE_COUPLING) impact(u, 0);}')
edit('science/equipment/ShapeRepair.java','        MachineDuty duty = null;','''        MachineDuty duty = kind==Kind.MOTOR&&saved.duty()==null&&Double.isFinite(saved.diameter())
                ?MachineDuty.motor(UnitDesign.of(kind).snap(saved.diameter()),4,MachineDuty.Drive.FIXED):null;''')
edit('science/equipment/MotorDesign.java','        if (generator && duty.poles() == 0)','        if (!generator && duty.baseSpeed()!=0 && (duty.baseSpeed()<300||duty.baseSpeed()>12000))throw new IllegalArgumentException("Motor base speed is 300 to 12000 rpm");\n        if (generator && duty.poles() == 0)')
# Changing the drive model invalidates only rebuildable engine state; facts and fluid contents remain.
edit('runtime/fluid/FluidCheckpointCodec.java','public static final int VERSION=37;','public static final int VERSION=38;')
p=Path('src/main/resources/assets/icheme/lang/en_us.json');s=p.read_text(encoding='utf-8');s=s.rstrip()[:-1].rstrip()+',\n  "gui.icheme.machine.envelope": "VFD capability",\n  "gui.icheme.machine.envelope.power": "Power",\n  "gui.icheme.machine.envelope.torque": "Torque"\n}\n';p.write_text(s,encoding='utf-8')
