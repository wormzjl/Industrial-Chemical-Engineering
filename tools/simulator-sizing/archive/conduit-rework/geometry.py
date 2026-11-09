from pathlib import Path
p=Path('src/main/java/com/wormzjl/icheme/science/equipment/DataCableGeometry.java')
s=p.read_text(encoding='utf-8');s=s[:s.index('    public record Segment')]+'''    public static List<Polygon> polygons(Key key){return LineGeometry.conduit(key.joints(),key.offsets(),OUTSIDE);}
    /** Rack conduit uses the rack pipe sweep, rather than chords between its corners. */
    public static List<Polygon> lane(RackGeometry.Rack rack,RackGeometry.Lane lane){return RackGeometry.lane(rack,lane);}
    public static List<Polygon> path(List<double[]> points){return LineGeometry.conduit(points,OUTSIDE);}
    /** Orthogonal equipment lead, with face-normal connector and cable-entry legs. */
    public static List<Polygon> lead(double[] outward,double[] end,double[] entering){
        var p=scale(outward,.18);var q=sub(end,scale(entering,.18));var points=new ArrayList<double[]>();
        points.add(new double[3]);points.add(p);
        for(int k=0;k<3;k++)if(Math.abs(p[k]-q[k])>1e-8){p=p.clone();p[k]=q[k];points.add(p);}
        points.add(end);return path(points);
    }
    /** Small boxes around the actual pipe polygons, shared by selection and removal previews. */
    public static List<double[]> boxes(Key key){
        var result=new ArrayList<double[]>();
        for(var p:polygons(key)){
            var bounds=new double[]{Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
            var v=p.positions();for(int i=0;i<v.length;i++){int k=i%3;bounds[k]=Math.min(bounds[k],v[i]);bounds[k+3]=Math.max(bounds[k+3],v[i]);}
            for(int k=0;k<3;k++){bounds[k]-=.012;bounds[k+3]+=.012;}result.add(bounds);
        }
        return List.copyOf(result);
    }
}
''';s=s.replace("/** A signal cable uses the pipe layer's face-point coordinates, without fluid passages or flanges. */",'/** Fixed-diameter metal signal conduit, using the pipe mesher and its face-point coordinates. */');p.write_text(s,encoding='utf-8')
p=Path('src/main/java/com/wormzjl/icheme/client/DataCableLayingClient.java');s=p.read_text(encoding='utf-8')
old='for(var segment:DataCableGeometry.segments(wire.geometry()))box(pose,buffers,camera,new AABB(Math.min(segment.ax(),segment.bx())-.04,Math.min(segment.ay(),segment.by())-.04,Math.min(segment.az(),segment.bz())-.04,Math.max(segment.ax(),segment.bx())+.04,Math.max(segment.ay(),segment.by())+.04,Math.max(segment.az(),segment.bz())+.04).move(hovered.clicked()),true);'
assert old in s;s=s.replace(old,'for(var b:DataCableGeometry.boxes(wire.geometry()))box(pose,buffers,camera,new AABB(b[0],b[1],b[2],b[3],b[4],b[5]).move(hovered.clicked()),true);')
s=s.replace('DataCableGeometry.path(RackGeometry.path(rack.rack(),slot.lane()))','DataCableGeometry.lane(rack.rack(),slot.lane())').replace('DataCableGeometry.path(RackGeometry.path(rack.rack(),rack.cableLane(cable)))','DataCableGeometry.lane(rack.rack(),rack.cableLane(cable))');p.write_text(s,encoding='utf-8')
p=Path('src/main/java/com/wormzjl/icheme/client/DataControlRenderer.java');s=p.read_text(encoding='utf-8');s=s.replace('DataCableGeometry.path(RackGeometry.path(rack.rack(),rack.cableLane(cable)))','DataCableGeometry.lane(rack.rack(),rack.cableLane(cable))')
s=s.replace('DataCableGeometry.path(List.of(new double[]{0,0,0},new double[]{end.x,end.y,end.z}))','DataCableGeometry.lead(new double[]{-port.face().getStepX(),-port.face().getStepY(),-port.face().getStepZ()},new double[]{end.x,end.y,end.z},new double[]{-port.face().getStepX(),-port.face().getStepY(),-port.face().getStepZ()})');p.write_text(s,encoding='utf-8')
