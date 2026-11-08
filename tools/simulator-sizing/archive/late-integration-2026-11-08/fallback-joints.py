from pathlib import Path
p=Path('src/main/java/com/wormzjl/icheme/science/equipment/InstrumentWiring.java');s=p.read_text(encoding='utf-8').replace('Material.TOWER_CONCRETE,Material.GRATING','Material.TOWER_CONCRETE,Material.TOWER_FLOOR,Material.GRATING')
s=s.replace('section(carriers,src,join,branchAxis)','section(carriers,src,join,branchAxis,runs,probes.get(i).id())').replace('section(carriers,roofExit,nearest,sectionAxis)','section(carriers,roofExit,nearest,sectionAxis,runs,"controller")')
s=s.replace('    private static List<double[]> section(List<Polygon> carriers,double[] a,double[] b,int axis){','''    private static List<double[]> section(List<Polygon> carriers,double[] a,double[] b,int axis,List<Run> runs,String source){
        try{return section(carriers,a,b,axis);}catch(IllegalArgumentException disconnected){
            var path=new SurfaceJoints(carriers).route(a,b);for(var edge:path){
                boolean concealed=Set.of(Material.SUPPORT,Material.STEELWORK,Material.CONCRETE).contains(edge.material());
                if(concealed){add(runs,"concealed:"+source,List.of(edge.a(),edge.b()),.025);continue;}
                // Flat casing runs are H/V in their native face. A curve changes coordinates only as its facet projects them.
                var delta=sub(edge.b(),edge.a());int fixed=0;for(int k=1;k<3;k++)if(Math.abs(delta[k])<Math.abs(delta[fixed]))fixed=k;var corner=edge.a().clone();int first=(fixed+1)%3;corner[first]=edge.b()[first];
                if(Math.abs(delta[fixed])<1e-7){add(runs,source,List.of(edge.a(),corner,edge.b()),.020);}else add(runs,source,List.of(edge.a(),edge.b()),.020);
            }return List.of(a);
        }
    }
    private static List<double[]> section(List<Polygon> carriers,double[] a,double[] b,int axis){''')
s=s.replace('for(var run:runs)for(int i=1;', 'for(var run:runs)if(!run.source.startsWith("concealed:"))for(int i=1;')
p.write_text(s,encoding='utf-8')
