from pathlib import Path
p=Path('src/main/java/com/wormzjl/icheme/science/equipment/EquipmentServices.java');s=p.read_text(encoding='utf-8')
s=s.replace('anchor=surface.ray(0,new double[]{0,y,z},1,skirt?Set.of(Material.SUPPORT):Set.of(Material.SHELL,Material.HEAD));','anchor=mountingPoint(surface,0,new double[]{0,y,z},1,skirt?Set.of(Material.SUPPORT):Set.of(Material.SHELL,Material.HEAD),a);')
s=s.replace('anchor=surface.ray(0,new double[]{0,1.5,a+Math.max(.6,.16*size)},1,Set.of(Material.SUPPORT));','anchor=mountingPoint(surface,0,new double[]{0,1.5,a+Math.min(Math.max(.6,.16*size),.6*size/2)},1,Set.of(Material.SUPPORT),a);')
s=s.replace('var b=mount(surface,anchor,front,.5,.4,.3);','var b=mount(surface,anchor,front,Math.clamp(size*.5,.28,.5),Math.clamp(size*.5,.28,.4),Math.clamp(size*.4,.2,.3));')
old='a[other]+=shift;a=surface.ray(axis,a,sign,MOUNT);var b=target.clone();b[other]+=shift;'
new='''a[other]+=shift;var b=target.clone();b[other]+=shift;try{a=surface.ray(axis,a,sign,MOUNT);}catch(IllegalArgumentException narrow){a=anchor.clone();a[1]+=shift;b=target.clone();b[1]+=shift;a=surface.ray(axis,a,sign,MOUNT);}'''
s=s.replace(old,new)
at=s.index('    private static double[] support(')
s=s[:at]+'''    private static double[] mountingPoint(ServiceSurface surface,int axis,double[] wanted,int sign,Set<Material> materials,double centre){
        try{return surface.ray(axis,wanted,sign,materials);}catch(IllegalArgumentException interrupted){var b=surface.bounds(materials);int across=axis==0?2:0;for(double y:new double[]{wanted[1],.25,(b[1]+b[4])/2})for(double other:new double[]{centre,centre+.1,centre-.1}){var p=wanted.clone();p[1]=y;p[across]=other;try{return surface.ray(axis,p,sign,materials);}catch(IllegalArgumentException missing){}}throw interrupted;}
    }
''' +s[at:];p.write_text(s,encoding='utf-8')
p=Path('src/main/java/com/wormzjl/icheme/science/equipment/SurfaceJoints.java');s=p.read_text(encoding='utf-8').replace('if(!Double.isFinite(cost[end]))throw new IllegalArgumentException("No joined native surface route");','''if(!Double.isFinite(cost[end])){var other=new HashSet<Integer>();var todo=new ArrayDeque<Integer>();other.add(end);todo.add(end);while(!todo.isEmpty())for(int n:links.get(todo.remove()).keySet())if(other.add(n))todo.add(n);double gap=Double.POSITIVE_INFINITY;double[] qa=null,qb=null;for(int i=0;i<cost.length;i++)if(Double.isFinite(cost[i]))for(int j:other){double d=SurfaceSection.distance(points.get(i),points.get(j));if(d<gap){gap=d;qa=points.get(i);qb=points.get(j);}}throw new IllegalArgumentException("No joined native surface route gap="+gap+" at "+Arrays.toString(qa)+" / "+Arrays.toString(qb));}''');p.write_text(s,encoding='utf-8')
