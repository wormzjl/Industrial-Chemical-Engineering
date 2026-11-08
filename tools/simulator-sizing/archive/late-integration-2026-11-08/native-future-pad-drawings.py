from pathlib import Path
p=Path('tools/equipment-drawings/service_details.py');s=p.read_text(encoding='utf-8').replace("        r.txt(c,q[0]+6,q[1]+5,text,7.5,colour,'Helvetica-Bold')","        r.txt(c,q[0]+6,q[1]+5,text,7.5,colour,'Helvetica-Bold')\n    return origin,scale")
s=s.replace("        top=clip(polys,lo,hi);tags=", "        reserved=d.get('instrumentPlan',{}).get('reserved',[])\n        for box in reserved:\n            for axis in [0,2]:lo[axis]=min(lo[axis],box['low'][axis]-.15);hi[axis]=max(hi[axis],box['high'][axis]+.15)\n        top=clip(polys,lo,hi);tags=")
s=s.replace("        panel(c,top,np.array([[1,0,0],[0,0,-1],[0,1,0]]),(45,110,500,240),views/f'{d[\"id\"]}-probe-plan-detail.png','SERVICE PROBE PLAN',tags)","""        plan_basis=np.array([[1,0,0],[0,0,-1],[0,1,0]])
        origin,scale=panel(c,top,plan_basis,(45,110,500,240),views/f'{d["id"]}-probe-plan-detail.png','SERVICE PROBE PLAN',tags)
        for box in reserved:
            low=np.array(box['low']);high=np.array(box['high']);a=origin+(low@plan_basis.T)[:2]*scale;b=origin+(high@plan_basis.T)[:2]*scale
            c.setStrokeColor(r.HexColor(r.POWER));c.setLineWidth(.6);c.setDash([3,2]);c.rect(min(a[0],b[0]),min(a[1],b[1]),abs(b[0]-a[0]),abs(b[1]-a[1]),fill=0,stroke=1);c.setDash([])
            r.txt(c,min(a[0],b[0]),max(a[1],b[1])+5,box['id']+' / '+box['purpose'],7,r.POWER)
""")
p.write_text(s,encoding='utf-8')
p=Path('tools/equipment-drawings/EngineeringExport.java');s=p.read_text(encoding='utf-8');old='data.put("builtIn",builtin);finish(id,data);}'
new='''data.put("builtIn",builtin);if(Set.of("INLINE_PUMP","INLINE_COMPRESSOR","CONTROL_VALVE").contains(id)){var key=new LineGeometry.Key(com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.PIPE,5,48,PipeSizes.code(PipeSizes.outside(.15)),0,0,false);data.put("existingData",id.equals("CONTROL_VALVE")?ValveGeometry.socket(key):InlineMoverGeometry.socket(key,id.equals("INLINE_COMPRESSOR"),false));if(power)data.put("existingPower",InlineMoverGeometry.socket(key,id.equals("INLINE_COMPRESSOR"),true));}finish(id,data);}'''
s=s.replace(old,new).replace('InlineMoverGeometry.polygons(line,false,false),true,false','InlineMoverGeometry.polygons(line,false,false),true,true').replace('InlineMoverGeometry.polygons(line,true,false),true,false','InlineMoverGeometry.polygons(line,true,false),true,true');p.write_text(s,encoding='utf-8')
