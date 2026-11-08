from pathlib import Path
p=Path('src/main/java/com/wormzjl/icheme/science/equipment/CentrifugalCompressorDesign.java');s=p.read_text(encoding='utf-8').replace('double half = cap + .20;','''double half = cap + .20;
        // Compact the visible deck inside the existing placement frame: gas and shaft datums are built facts.
        double priorHalf=Math.max(cap+.20,Math.ceil(cap+.15)+.12);
        u.framePoints.add(new double[]{ax-priorHalf-.08,0,cover-.23});
        u.framePoints.add(new double[]{ax+priorHalf+.08,h,face-.01});''');p.write_text(s,encoding='utf-8')
# Give top-mounted physical column nozzles accepted stage readings, independently of the external boundary buffers.
p=Path('src/main/java/com/wormzjl/icheme/runtime/fluid/ColumnOperation.java');s=p.read_text(encoding='utf-8');s=s.replace('var parts=role.split(":");Port port;try{port=Port.valueOf(parts[1]);}catch(IllegalArgumentException e){return null;}int index=Integer.parseInt(parts[2]);','''var parts=role.split(":");int index=Integer.parseInt(parts[2]);
            if(parts[1].equals("VAPOUR_OUTLET")){
                if(r.inspection==null)return null;var node=r.inspection.nodes().get(1);
                if(kind==com.wormzjl.icheme.runtime.instrument.Sensor.Kind.PRESSURE)value=node.pressurePascal();else if(kind==com.wormzjl.icheme.runtime.instrument.Sensor.Kind.TEMPERATURE)value=node.temperatureKelvin();
                else {double mw=0;for(int c=0;c<node.vaporFractions().size();c++){int k=axis.indexOf(r.inspection.input().componentBasis().componentId(c));if(k>=0)mw+=node.vaporFractions().get(c)*weights[k];}int water=axis.indexOf(SplitTable.WATER);value=(node.vaporMolPerSecond()*mw+(water<0?0:node.waterVaporMolPerSecond()*weights[water]))*r.last.alpha();}return Double.isFinite(value)?new InstrumentSample(value,tick):null;
            }
            Port port;try{port=Port.valueOf(parts[1]);}catch(IllegalArgumentException e){return null;}''');p.write_text(s,encoding='utf-8')
