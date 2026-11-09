from pathlib import Path
p=Path('src/main/java/com/wormzjl/icheme/science/equipment/EquipmentServices.java');s=p.read_text(encoding='utf-8').replace('static Plan build(EquipmentShape shape,Mesh nativeMesh,ProcessUnit unit) {','static Plan build(EquipmentShape shape,Mesh nativeMesh,ProcessUnit unit) {return build(shape,nativeMesh,unit,null);}\n    static Plan build(EquipmentShape shape,Mesh nativeMesh,ProcessUnit unit,Plan physical) {')
s=s.replace('        if(!reuse)enclosure(mesh,low,high,front,true);','''        if(physical!=null&&!physical.cases.isEmpty()){var c=physical.cases.getFirst();low=toNorth.point(c.low);high=toNorth.point(c.high);for(int a=0;a<3;a++)if(low[a]>high[a]){double x=low[a];low[a]=high[a];high[a]=x;}front=face(toNorth.direction(LineGeometry.step(c.face)));}
        if(low[1]<.035){double lift=.035-low[1];low[1]+=lift;high[1]+=lift;}
        if(!reuse)enclosure(mesh,low,high,front,true);''')
s=s.replace('a=surface.ray(axis,a,sign,MOUNT);}var elbow','a=mountingPoint(surface,axis,a,sign,MOUNT,anchor[other]);}var elbow')
start=s.index('    private static double[] mountingPoint(');end=s.index('    private static double[] support(',start)
s=s[:start]+'''    private static double[] mountingPoint(ServiceSurface surface,int axis,double[] wanted,int sign,Set<Material> materials,double centre){
        try{return surface.ray(axis,wanted,sign,materials);}catch(IllegalArgumentException interrupted){
            double best=Double.POSITIVE_INFINITY;double[] chosen=null;for(var polygon:surface.polygons){if(!materials.contains(polygon.material()))continue;var v=polygon.positions();var n=cross(sub(Arrays.copyOfRange(v,3,6),Arrays.copyOfRange(v,0,3)),sub(Arrays.copyOfRange(v,6,9),Arrays.copyOfRange(v,0,3)));if(sign*n[axis]<1e-10)continue;var p=new double[3];for(int i=0;i<v.length;i++)p[i%3]+=v[i]/polygon.size();double score=0;for(int a=0;a<3;a++)if(a!=axis)score+=Math.pow(p[a]-wanted[a],2);if(score<best){best=score;chosen=p;}}
            if(chosen!=null)return surface.ray(axis,chosen,sign,materials);throw interrupted;
        }
    }
''' +s[end:]
p.write_text(s,encoding='utf-8')
p=Path('src/main/java/com/wormzjl/icheme/science/equipment/ShapeRasterizer.java');s=p.read_text(encoding='utf-8').replace('services=detail==RenderDetail.NORMAL?EquipmentServices.build(shape,mesh,unit):geometry(shape).services;','services=detail==RenderDetail.NORMAL?EquipmentServices.build(shape,mesh,unit):EquipmentServices.build(shape,mesh,unit,geometry(shape).services);');p.write_text(s,encoding='utf-8')
