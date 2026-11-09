from pathlib import Path
base=Path('src/main/java/com/wormzjl/icheme')
def edit(p,fn):
 p=base/p;s=p.read_text(encoding='utf-8');p.write_text(fn(s),encoding='utf-8')
edit(Path('science/column/operation/ColumnSolve.java'),lambda s:s.replace('String route, String note) implements Outcome {','String route, String note, com.wormzjl.icheme.science.column.v3.V3ColumnInspection inspection) implements Outcome {\n        public Solved(SplitTable table,double[][] enthalpy,V3NeuralSeed seed,double duty,String route,String note){this(table,enthalpy,seed,duty,route,note,null);}') .replace('success.diagnostics().solvePath(), note);','success.diagnostics().solvePath(), note, success.result().inspection().orElse(null));'))
edit(Path('runtime/fluid/ColumnOperation.java'),lambda s:s.replace('SplitTable table;','SplitTable table;\n        com.wormzjl.icheme.science.column.v3.V3ColumnInspection inspection;',1).replace('r.seed=s.seed();','r.seed=s.seed();r.inspection=s.inspection();'))
# Controller-independent immutable readings. Accepted table, accepted cycle and accepted inspection only.
p=base/'runtime/fluid/ColumnOperation.java';s=p.read_text(encoding='utf-8');at=s.index('    /** Installs a solved split table')
s=s[:at]+'''    record InstrumentSample(double value,long tick) {}
    InstrumentSample instrument(long unit,String role,com.wormzjl.icheme.runtime.instrument.Sensor.Kind kind){
        var u=topology.column(unit);var r=runs.get(unit);if(u==null||r==null||r.table==null||r.last==null||r.last.trip()!=null)return null;
        double value=Double.NaN;long tick=r.applied;
        if(role.startsWith("COLUMN:")){
            if(r.inspection==null)return null;var nodes=r.inspection.nodes();var node=role.endsWith("bottom")?nodes.getLast():nodes.get(1);
            value=kind==com.wormzjl.icheme.runtime.instrument.Sensor.Kind.PRESSURE?node.pressurePascal():node.temperatureKelvin();
        }else if(role.equals("REFLUX")){
            if(r.inspection==null)return null;var node=r.inspection.nodes().getFirst();
            if(kind==com.wormzjl.icheme.runtime.instrument.Sensor.Kind.PRESSURE)value=node.pressurePascal();
            else if(kind==com.wormzjl.icheme.runtime.instrument.Sensor.Kind.TEMPERATURE)value=node.temperatureKelvin();
            else {double mw=0;for(int c=0;c<node.liquidFractions().size();c++){int k=axis.indexOf(r.inspection.input().componentBasis().componentId(c));if(k>=0)mw+=node.liquidFractions().get(c)*weights[k];}value=node.liquidMolPerSecond()*mw*r.last.alpha();}
        }else if(role.startsWith("COLUMN_PORT:")){
            var parts=role.split(":");Port port;try{port=Port.valueOf(parts[1]);}catch(IllegalArgumentException e){return null;}int index=Integer.parseInt(parts[2]);
            var stream=view(unit,topology.onlineTick(),id->Double.NaN).streams().stream().filter(p->p.port()==port&&p.index()==index).findFirst().orElse(null);if(stream==null)return null;
            if(kind==com.wormzjl.icheme.runtime.instrument.Sensor.Kind.PRESSURE)value=stream.pressure();
            else if(kind==com.wormzjl.icheme.runtime.instrument.Sensor.Kind.TEMPERATURE)value=stream.temperature();
            else {ColumnCycle.Account account=null;if(port==Port.FEED)account=r.last.feed();else if(port==Port.STEAM&&index<r.last.steam().size())account=r.last.steam().get(index);else {int k=r.table.product(port,index);if(k>=0&&k<r.last.products().size())account=r.last.products().get(k);}if(account!=null)value=mass(account.moles())/(CYCLE_TICKS/20.0);}
        }
        return Double.isFinite(value)?new InstrumentSample(value,tick):null;
    }
''' +s[at:];p.write_text(s,encoding='utf-8')
edit(Path('runtime/fluid/FluidWorldAuthority.java'),lambda s:s.replace('    /** Stops column unit', '''    public record ColumnInstrument(double value,long tick,com.wormzjl.icheme.runtime.instrument.Instrument.Availability availability) {}
    public ColumnInstrument columnInstrument(PhysicalFluidTopology.Position master,String role,com.wormzjl.icheme.runtime.instrument.Sensor.Kind kind){owned();var u=column(master);var sample=u==null?null:columns.instrument(u.id(),role,kind);return sample==null?null:new ColumnInstrument(sample.value(),sample.tick(),com.wormzjl.icheme.runtime.instrument.Instrument.Availability.AVAILABLE);}
    /** Stops column unit''',1))
# Native inline gland wins over a sensor's gland on actuated pipes.
p=base/'runtime/control/DataPorts.java';s=p.read_text(encoding='utf-8');start=s.index('            for(var sensor:');end=s.index('            if(host.entity()',start);old=s[start:end];s=s[:start]+s[end:];at=s.index('\n        }\n        var mount=',start);s=s[:at]+'\n'+old+s[at:];s=s.replace('                List<Mesh.Polygon> polygons=List.of();\n','');p.write_text(s,encoding='utf-8')
