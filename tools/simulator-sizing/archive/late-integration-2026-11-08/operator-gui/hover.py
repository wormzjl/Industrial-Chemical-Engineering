from pathlib import Path
r=Path('src/main/java/com/wormzjl/icheme')
p=r/'client/gui/common/kit/Column.java';s=p.read_text(encoding='utf-8').replace('public record Paragraph(String text, int colour) implements Row {\n    }','''public record Paragraph(String text, int colour, @Nullable String hint) implements Row {
        public Paragraph(String text,int colour){this(text,colour,null);}
    }''').replace('lines.size() * Measure.LINE + 4), slots, lines, lines.size(), null);','lines.size() * Measure.LINE + 4), slots, lines, lines.size(), p.hint());');p.write_text(s,encoding='utf-8')
p=r/'client/gui/common/ControlColumns.java';s=p.read_text(encoding='utf-8').replace('new Column.Paragraph(v.input(),Palette.TEXT)','new Column.Paragraph(v.input(),Palette.TEXT,sensorHint)');i=s.index('    public record Layout');s=s[:i]+'''    public record SensorLocation(boolean available,boolean mount,int x,int y,int z,long equipment){}
    /** Sensor identity stays on the label; its physical block and attached equipment identity belong to the hover. */
    public static String sensorHint(SensorLocation at,BiFunction<String,Object[],String> text){
        var location=at.available()?text.apply(at.mount()?"control.sensor_location":"control.sensor_host_location",new Object[]{at.x(),at.y(),at.z()}):text.apply("control.location_unknown",new Object[0]);
        return at.equipment()>0?location+"\\n"+text.apply("control.sensor_equipment",new Object[]{String.format(Locale.ROOT,"#%04d",at.equipment())}):location;
    }
'''+s[i:];p.write_text(s,encoding='utf-8')
p=r/'client/gui/screens/inventory/ControlPanelScreen.java';s=p.read_text(encoding='utf-8');a=s.index('    private static String sensorHint(');b=s.index('    private static List<Column.Row> withNavigation',a);s=s[:a]+'''    private static String sensorHint(CompoundTag input){
        boolean located=input!=null&&input.contains("position",4);var p=located?net.minecraft.core.BlockPos.of(input.getLong("position")):net.minecraft.core.BlockPos.ZERO;
        return ControlColumns.sensorHint(new ControlColumns.SensorLocation(located,input!=null&&input.getBoolean("mountLocated"),p.getX(),p.getY(),p.getZ(),input==null?0:input.getLong("equipment")),ControlPanelScreen::tr);
    }
'''+s[b:];p.write_text(s,encoding='utf-8')
# Keep comments aligned with the operator-facing controls.
p=r/'client/gui/common/ConstructorColumn.java';s=p.read_text(encoding='utf-8').replace("Footer: a drum's or a column's Nozzles, Paint, Build.","Footer: Paint and Build; the main door overload inserts nozzle controls before Place.").replace("an electric machine's or a pump's Speed (its poles, latched keys two a row)","an electric machine's or a pump's rated RPM").replace('{@code poles:<n>}, {@code drive:fixed}, {@code drive:variable},','{@code base_speed},');p.write_text(s,encoding='utf-8')
