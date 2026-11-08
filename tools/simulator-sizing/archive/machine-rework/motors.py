from pathlib import Path
base=Path('src/main/java/com/wormzjl/icheme')
def edit(path,old,new):
 p=base/path;s=p.read_text(encoding='utf-8');assert old in s,(path,old[:70]);p.write_text(s.replace(old,new),encoding='utf-8')
p=base/'science/equipment/MachineDuty.java';s=p.read_text(encoding='utf-8').replace('@Nullable Steam steam, double exhaust) {','@Nullable Steam steam, double exhaust, double baseSpeed) {',1)
s=s.replace('    /** A shape whose kind has no design point. */','''    /** Legacy facts have no explicit base speed: their pole-derived speed is retained. */
    public MachineDuty(double flow,double head,double polytropicHead,double power,int poles,Drive drive,@Nullable Steam steam,double exhaust) {
        this(flow,head,polytropicHead,power,poles,drive,steam,exhaust,0);
    }
    /** A VFD motor, with an explicit base speed in rpm. */
    public static MachineDuty motor(double power,double rpm) { return new MachineDuty(0,0,0,power,4,Drive.VARIABLE,null,0,rpm); }
    public MachineDuty withBaseSpeed(double rpm) { return new MachineDuty(flow,head,polytropicHead,power,poles,drive,steam,exhaust,rpm); }
    /** A shape whose kind has no design point. */''',1)
s=s.replace('power, exhaust}', 'power, exhaust, baseSpeed}').replace('return poles == 0 ? 0 : 6000.0 / poles;', 'return baseSpeed > 0 ? baseSpeed : poles == 0 ? 0 : 6000.0 / poles;')
s=s.replace('value, drive, steam, exhaust);','value, drive, steam, exhaust,baseSpeed);').replace('poles, value, steam, exhaust);','poles, value, steam, exhaust,baseSpeed);').replace('drive, value, e);','drive, value, e,baseSpeed);')
p.write_text(s,encoding='utf-8')
edit('science/equipment/ShapeRepair.java','double exhaust) {\n        /** @throws','''double exhaust,double baseSpeed) {
        public SavedDuty(double flow,double head,double polytropicHead,double power,int poles,String drive,String steam,double exhaust) { this(flow,head,polytropicHead,power,poles,drive,steam,exhaust,0); }
        /** @throws''')
edit('science/equipment/ShapeRepair.java','poles, namedDrive, namedSteam, exhaust);','poles, namedDrive, namedSteam, exhaust,baseSpeed);')
edit('world/level/block/entity/EquipmentShellBlockEntity.java','tag.putDouble("Exhaust", duty.exhaust());','tag.putDouble("Exhaust", duty.exhaust());\n        tag.putDouble("BaseSpeed", duty.baseSpeed());')
edit('world/level/block/entity/EquipmentShellBlockEntity.java','d.getDouble("Exhaust"));','d.getDouble("Exhaust"),d.getDouble("BaseSpeed"));')
edit('network/EquipmentNetwork.java','"equipment-19"','"equipment-20"')
edit('network/EquipmentNetwork.java','b.writeDouble(d.exhaust());','b.writeDouble(d.exhaust());\n        b.writeDouble(d.baseSpeed());')
edit('network/EquipmentNetwork.java','MachineDuty.Steam.values()[steam - 1], b.readDouble());','MachineDuty.Steam.values()[steam - 1], b.readDouble(),b.readDouble());')
edit('science/equipment/UnitDesign.java','case PUMP, MOTOR ->','case MOTOR -> duty.baseSpeed()>0 ? duty.baseSpeed() : com.wormzjl.icheme.science.machine.Motor.ratedSpeed(duty.poles()==0?2:duty.poles());\n            case PUMP ->')
edit('science/equipment/MotorDesign.java','MachineDuty.motor(size, DEFAULT_POLES, MachineDuty.Drive.FIXED)','MachineDuty.motor(size,3000.0)')
edit('science/equipment/MotorDesign.java','if (duty.poles() == 0)', 'if (generator && duty.poles() == 0)')
edit('science/equipment/MotorDesign.java','        if (!setting.equals("power")) return duty;', '''        if (setting.equals("base_speed") && !generator) return duty.withBaseSpeed(Math.clamp(duty.speed()+steps*100,300,12000));
        if (!setting.equals("power")) return duty;''')
edit('science/equipment/MotorDesign.java','MachineDuty.motor(power, duty.poles(), duty.drive())','new MachineDuty(0,0,0,power,duty.poles(),duty.drive(),null,0,duty.baseSpeed())')
edit('science/equipment/MotorDesign.java','duty.drive().name().toLowerCase(Locale.ROOT)','"variable"')
edit('client/gui/common/ConstructorColumn.java','if (d.poles() != 0) {','if (d.poles() != 0 && kind != EquipmentShape.Kind.MOTOR) {')
p=base/'client/gui/common/ConstructorColumn.java';s=p.read_text(encoding='utf-8');start=s.index('        if (kind == EquipmentShape.Kind.MOTOR) {',s.index('private static List<Column.Row>') if 'private static List<Column.Row>' in s else 0);end=s.index('        if (d.steam() != null)',start)
s=s[:start]+'''        if (kind == EquipmentShape.Kind.MOTOR)
            rows.add(new Column.Group(t.get(KEY+"group.speed"),t.get(KEY+"motor.vfd.hint"),List.of(step("base_speed","motor.base_speed",amount(d.speed()),"rpm",t))));
'''+s[end:];p.write_text(s,encoding='utf-8')
edit('runtime/fluid/ShaftUnit.java','case MOTOR->variable?ShaftTrain.Drive.VARIABLE_MOTOR:ShaftTrain.Drive.FIXED_MOTOR;','case MOTOR->ShaftTrain.Drive.VARIABLE_MOTOR;')
p=base/'science/machine/Motor.java';s=p.read_text(encoding='utf-8');at=s.index('    private static int poleColumn')
s=s[:at]+'''    /** VFD continuous envelope: constant torque below base speed, constant power above it. */
    public static double availablePower(double ratedPower,double baseRpm,double rpm) {
        if (!(ratedPower>=0)||!(baseRpm>0)||!(rpm>=0)||!Double.isFinite(ratedPower+baseRpm+rpm)) throw new IllegalArgumentException("Invalid motor envelope");
        return ratedPower*Math.min(1,rpm/baseRpm);
    }
    public static double torque(double power,double rpm) { return rpm>0?power*60/(2*Math.PI*rpm):0; }
    public static final double HIGHEST_SPEED=1.5;

'''+s[at:];p.write_text(s,encoding='utf-8')
edit('science/machine/ShaftTrain.java','return drive == Drive.CREATIVE ? powerLimit > 0 ? powerLimit : CREATIVE_LIMIT : ratedPower;', 'return drive == Drive.CREATIVE ? powerLimit > 0 ? powerLimit : CREATIVE_LIMIT : powerLimit>0?Math.min(ratedPower,powerLimit):ratedPower;')
edit('science/machine/ShaftTrain.java','double next = limited(speed, driver.limit(), demand, set);','double limit=driver.drive()==Drive.VARIABLE_MOTOR?Motor.availablePower(driver.limit(),driver.designSpeed(),speed>0?speed:set):driver.limit();\n                double next = limited(speed, limit, demand, set);')
edit('science/machine/ShaftTrain.java','Motor.LOWEST_SPEED, 1);','Motor.LOWEST_SPEED, driver.drive()==Drive.VARIABLE_MOTOR?Motor.HIGHEST_SPEED:1);')
edit('runtime/fluid/ElectricStores.java','u.drive() == com.wormzjl.icheme.science.equipment.MachineDuty.Drive.VARIABLE','true')
edit('client/gui/common/MachineColumn.java','return this == MOTOR ? "%" : "rpm";','return "rpm";')
edit('client/gui/common/MachineColumn.java','return kind == Kind.MOTOR ? variable : kind.driver();','return kind.driver();')
p=base/'client/gui/common/MachineColumn.java';s=p.read_text(encoding='utf-8');start=s.index('        if (in.kind() == Kind.MOTOR)\n');end=s.index('        if (in.regulates())',start);s=s[:start]+s[end:];s=s.replace('if (in.kind() == Kind.CREATIVE_MOTOR)','if (in.kind() == Kind.CREATIVE_MOTOR || in.kind() == Kind.MOTOR)');s=s.replace('case MOTOR -> 100 * (set > 0 ? set : 1);','case MOTOR -> (set>0?set:1)*v.designSpeed();').replace('case MOTOR -> entry / 100;','case MOTOR -> designSpeed>0?entry/designSpeed:0;');p.write_text(s,encoding='utf-8')
edit('client/gui/screens/inventory/MachineScreen.java','kind() == MachineColumn.Kind.CREATIVE_MOTOR || kind() == MachineColumn.Kind.GENERATOR','kind() == MachineColumn.Kind.MOTOR || kind() == MachineColumn.Kind.CREATIVE_MOTOR || kind() == MachineColumn.Kind.GENERATOR')
edit('network/MachineNetwork.java','"machine-2"','"machine-3"')
edit('network/MachineNetwork.java','boolean variable = u.kind() == ShaftNetwork.Kind.STEAM_TURBINE || u.drive() == com.wormzjl.icheme.science.equipment.MachineDuty.Drive.VARIABLE;','boolean variable = true;')
edit('network/MachineNetwork.java','asked.setPoint() > MAXIMUM_SHARE','asked.setPoint() > (u.kind()==ShaftNetwork.Kind.MOTOR?Motor.HIGHEST_SPEED:MAXIMUM_SHARE)')
edit('network/MachineNetwork.java','now.powerLimit(), now.ratio());\n                }','u.kind()==ShaftNetwork.Kind.MOTOR?Math.min(MAXIMUM_LIMIT,asked.powerLimit()):now.powerLimit(), now.ratio());\n                }')
# Keep only English changes during this batch.
p=Path('src/main/resources/assets/icheme/lang/en_us.json');s=p.read_text(encoding='utf-8');s=s.replace('"gui.icheme.machine.regulate.speed": "Speed"','"gui.icheme.machine.regulate.speed": "Manual"').replace('"gui.icheme.machine.regulate.flow": "Flow"','"gui.icheme.machine.regulate.flow": "Auto flow"').replace('"gui.icheme.machine.regulate.pressure": "Pressure"','"gui.icheme.machine.regulate.pressure": "Auto pressure"')
s=s.rstrip()[:-1].rstrip()+',\n  "gui.icheme.constructor.motor.base_speed": "Base speed",\n  "gui.icheme.constructor.motor.vfd.hint": "All motors use a variable-frequency drive. Power and base speed define the torque limit."\n}\n';p.write_text(s,encoding='utf-8')
