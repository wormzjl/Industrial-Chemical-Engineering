from pathlib import Path
r=Path('src/main/java/com/wormzjl/icheme')
p=r/'client/gui/common/ControlColumns.java';s=p.read_text().replace('public record Choice(String key,String label,boolean selected){}','''public record Choice(String key,String label,boolean selected,String hint){
        public Choice(String key,String label,boolean selected){this(key,label,selected,null);}
    }''').replace('new Column.Choice(choice.label(),null,choice.selected(),choice.key())','new Column.Choice(choice.label(),choice.hint(),choice.selected(),choice.key())');s=s.replace('    public static List<Column.Row> loop(View v,BiFunction<String,Object[],String> text){','''    public static List<Column.Row> loop(View v,BiFunction<String,Object[],String> text){return loop(v,null,text);}
    public static List<Column.Row> loop(View v,String sensorHint,BiFunction<String,Object[],String> text){''');s=s.replace('new Column.Reading("pv",t.apply("reading"),v.value(),v.secondary()?v.secondUnit():v.unit(),null)','new Column.Reading("pv",t.apply("reading"),v.value(),v.secondary()?v.secondUnit():v.unit(),sensorHint)').replace('new Column.Group(t.apply("input"),null,input)','new Column.Group(t.apply("input"),sensorHint,input)');p.write_text(s)
p=r/'client/gui/common/ConstructorColumn.java';s=p.read_text();s=s.replace('        if (!form.designed()) footer.add(push("nozzles", form.column() ? "column_nozzles.hint" : "connections.hint", t));\n','');a=s.index('        if (d.poles() != 0 && kind != EquipmentShape.Kind.MOTOR)');b=s.index('        if (d.steam() != null)',a);s=s[:a]+'''        if (kind == EquipmentShape.Kind.MOTOR || kind == EquipmentShape.Kind.PUMP || kind == EquipmentShape.Kind.GENERATOR)
            rows.add(new Column.Group(t.get(KEY+"group.speed"),t.get(KEY+(kind==EquipmentShape.Kind.MOTOR?"motor.vfd.hint":"rated_speed.hint")),List.of(step("base_speed",kind==EquipmentShape.Kind.MOTOR?"motor.base_speed":"rated_speed",amount(UnitDesign.designSpeed(kind,d)),"rpm",t))));
'''+s[b:];i=s.index('    private static Column.Button importKey(');s=s[:i]+'''    /** The same design door includes its nozzle editor before placement; one footer and scroll view own all controls. */
    public static Page design(Design in,@Nullable Connections connections,@Nullable Nozzles nozzles,HeatControlColumn.Texts t){
        var base=design(in,t);if(in.form().designed())return base;
        Page editor=in.form().column()?ConstructorColumn.nozzles(java.util.Objects.requireNonNull(nozzles),t):ConstructorColumn.connections(java.util.Objects.requireNonNull(connections),t);
        var rows=new ArrayList<>(base.rows());int beforePlacement=rows.size()-1;rows.addAll(beforePlacement,editor.rows());
        if(in.form().column()){
            var reset=editor.footer().stream().filter(button->button.key().equals("reset")).findFirst().orElseThrow();
            var group=(Column.Group)rows.get(beforePlacement);var controls=new ArrayList<>(group.rows());controls.add(new Column.Buttons(List.of(reset)));
            rows.set(beforePlacement,new Column.Group(group.title(),group.hint(),controls));
        }
        return new Page(rows,base.footer(),base.display());
    }

'''+s[i:];p.write_text(s)
p=r/'science/equipment/UnitDesign.java';s=p.read_text();s=s.replace('            case MOTOR -> duty.baseSpeed()>0 ? duty.baseSpeed() : com.wormzjl.icheme.science.machine.Motor.ratedSpeed(duty.poles()==0?2:duty.poles());\n            case PUMP -> com.wormzjl.icheme.science.machine.Motor.ratedSpeed(duty.poles() == 0 ? 2 : duty.poles());\n            case GENERATOR -> duty.poles() == 0 ? 3000 : duty.speed();','            case MOTOR, PUMP, GENERATOR -> designSpeed(shape.kind(),duty);');i=s.index('    /** A driver\'s, generator\'s or gearbox');s=s[:i]+'''    /** Explicit constructor RPM takes precedence; absent RPM retains each legacy machine's speed convention. */
    public static double designSpeed(EquipmentShape.Kind kind,MachineDuty duty){
        if(duty.baseSpeed()>0)return duty.baseSpeed();
        return switch(kind){
            case MOTOR, PUMP -> com.wormzjl.icheme.science.machine.Motor.ratedSpeed(duty.poles()==0?2:duty.poles());
            case GENERATOR -> duty.poles()==0?3000:duty.speed();
            default -> throw new IllegalArgumentException("This machine derives its speed from its geometry");
        };
    }

'''+s[i:];s=s.replace('        int poles = shape.duty() == null ? 0 : shape.duty().poles();','        if(shape.duty()!=null&&shape.duty().baseSpeed()>0)return shape.duty().baseSpeed();\n        int poles = shape.duty() == null ? 0 : shape.duty().poles();');p.write_text(s)
p=Path('src/main/resources/assets/icheme/lang/en_us.json');s=p.read_text();import json
new={
 'gui.icheme.control.sensor_location':'Sensor block: %s, %s, %s\nDimension: %s',
 'gui.icheme.control.sensor_host_location':'Equipment block: %s, %s, %s\nDimension: %s',
 'gui.icheme.control.location_unknown':'Location unavailable',
 'gui.icheme.equipment_constructor.rated_speed':'Rated speed',
 'gui.icheme.equipment_constructor.rated_speed.hint':'Shaft speed at the rated design point, in rpm. The connected driver determines the running speed.'}
end=s.rfind('}');s=s[:end].rstrip()+',\n'+',\n'.join('  '+json.dumps(k)+': '+json.dumps(v,ensure_ascii=False) for k,v in new.items())+'\n}\n';p.write_text(s)
