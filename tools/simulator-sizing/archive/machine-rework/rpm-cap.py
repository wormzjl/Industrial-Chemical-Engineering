from pathlib import Path
import json
b=Path('src/main/java/com/wormzjl/icheme')
def edit(path,old,new):
 p=b/path;s=p.read_text(encoding='utf-8');assert old in s,(path,old[:65]);p.write_text(s.replace(old,new),encoding='utf-8')
edit('science/machine/Motor.java','    public static final double HIGHEST_SPEED=1.5;','''    /** Absolute operating-speed ceiling; a saved base-speed rating is retained independently. */
    public static final double MAXIMUM_RPM=3000;
    public static double maximumShare(double baseRpm) {
        if(!(baseRpm>0)||!Double.isFinite(baseRpm))throw new IllegalArgumentException("Invalid motor base speed");
        return MAXIMUM_RPM/baseRpm;
    }''')
edit('science/machine/SpeedControl.java','    private static double clamp(','''    /** Search bound for a driven machine's curve, independent of the motor's absolute rpm limit. */
    public static final double MAXIMUM_CURVE_SPEED=1.5;
    private static double clamp(''')
edit('science/machine/ShaftTrain.java','return drive == Drive.CREATIVE ? setPoint > 0 ? setPoint : CREATIVE_SPEED : (setPoint > 0 ? setPoint : 1) * designSpeed;','''double target=drive == Drive.CREATIVE ? setPoint > 0 ? setPoint : CREATIVE_SPEED : (setPoint > 0 ? setPoint : 1) * designSpeed;
            return drive==Drive.VARIABLE_MOTOR||drive==Drive.FIXED_MOTOR?Math.min(Motor.MAXIMUM_RPM,target):target;''')
edit('science/machine/ShaftTrain.java','new Step(driver.designSpeed(), Trip.NONE, "RUNNING"','new Step(set, Trip.NONE, "RUNNING"')
edit('science/machine/ShaftTrain.java','driver.drive()==Drive.VARIABLE_MOTOR?Motor.HIGHEST_SPEED:1','driver.drive()==Drive.VARIABLE_MOTOR?Motor.maximumShare(design):1')
edit('runtime/fluid/ShaftTrains.java','com.wormzjl.icheme.science.machine.Motor.HIGHEST_SPEED','com.wormzjl.icheme.science.machine.SpeedControl.MAXIMUM_CURVE_SPEED')
edit('runtime/fluid/ShaftTrains.java','driver.designSpeed()*com.wormzjl.icheme.science.machine.SpeedControl.MAXIMUM_CURVE_SPEED','com.wormzjl.icheme.science.machine.Motor.MAXIMUM_RPM')
edit('science/equipment/MotorDesign.java','Math.clamp(duty.speed()+steps*100,300,12000)','Math.clamp(duty.speed()+steps*100,300,com.wormzjl.icheme.science.machine.Motor.MAXIMUM_RPM)')
p=b/'network/MachineNetwork.java';s=p.read_text(encoding='utf-8');old='''                    if (asked.mode() == ShaftTrain.Mode.SPEED && (asked.setPoint() < Motor.LOWEST_SPEED || asked.setPoint() > (u.kind()==ShaftNetwork.Kind.MOTOR?Motor.HIGHEST_SPEED:MAXIMUM_SHARE)))
                        yield refuse(String.format(java.util.Locale.ROOT, "the speed lies between %.0f %% and %.0f %%", 100 * Motor.LOWEST_SPEED, 100 * (u.kind()==ShaftNetwork.Kind.MOTOR?Motor.HIGHEST_SPEED:MAXIMUM_SHARE)));''';assert old in s;s=s.replace(old,'''                    if(asked.mode()==ShaftTrain.Mode.SPEED) {
                        if(u.kind()==ShaftNetwork.Kind.MOTOR&&(asked.setPoint()<Motor.LOWEST_SPEED||asked.setPoint()*u.designSpeed()>Motor.MAXIMUM_RPM))
                            yield refuse(String.format(java.util.Locale.ROOT,"the speed lies between %.0f and %.0f rpm",Motor.LOWEST_SPEED*u.designSpeed(),Motor.MAXIMUM_RPM));
                        if(u.kind()!=ShaftNetwork.Kind.MOTOR&&(asked.setPoint()<Motor.LOWEST_SPEED||asked.setPoint()>MAXIMUM_SHARE))
                            yield refuse(String.format(java.util.Locale.ROOT,"the speed lies between %.0f %% and %.0f %%",100*Motor.LOWEST_SPEED,100*MAXIMUM_SHARE));
                    }''');p.write_text(s,encoding='utf-8')
p=b/'client/gui/common/MotorCurve.java';s=p.read_text(encoding='utf-8').replace('int x=box.x()+25','double maximumShare=Motor.maximumShare(base);\n        int x=box.x()+25').replace('Motor.HIGHEST_SPEED*i/w','maximumShare*i/w').replace('Math.clamp(rpm/base,0,Motor.HIGHEST_SPEED)/Motor.HIGHEST_SPEED','Math.clamp(rpm,0,Motor.MAXIMUM_RPM)/Motor.MAXIMUM_RPM').replace('base*Motor.HIGHEST_SPEED','Motor.MAXIMUM_RPM');p.write_text(s,encoding='utf-8')
edit('client/gui/common/MachineColumn.java','KEY + "speed." + in.kind().speedUnit().replace("%", "share") + ".hint"','KEY + "speed." + (in.kind()==Kind.MOTOR?"motor":in.kind().speedUnit().replace("%", "share")) + ".hint"')
edit('runtime/fluid/FluidCheckpointCodec.java','public static final int VERSION=38;','public static final int VERSION=39;')
p=Path('src/main/resources/assets/icheme/lang/en_us.json');s=p.read_text(encoding='utf-8');d=json.loads(s)
k='gui.icheme.equipment_constructor.motor.vfd.hint';s=s.replace(json.dumps(d[k]),json.dumps('All motors use a variable-frequency drive with a maximum speed of 3000 rpm. Power and base speed define the torque limit.'))
s=s.rstrip()[:-1].rstrip()+',\n  "gui.icheme.machine.speed.motor.hint": "Set the motor speed in rpm, up to 3000 rpm. Then press Apply."\n}\n';p.write_text(s,encoding='utf-8')
