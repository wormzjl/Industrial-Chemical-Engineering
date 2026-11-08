from pathlib import Path
p=Path('src/main/java/com/wormzjl/icheme/runtime/fluid/HeatPresentation.java');s=p.read_text(encoding='utf-8').replace('        for(int p=0;p<volumes.length;p++)volumes[p]*=scale;\n','');p.write_text(s,encoding='utf-8')
p=Path('src/main/java/com/wormzjl/icheme/science/equipment/ServiceSurface.java');s=p.read_text(encoding='utf-8').replace('double[] ray(int axis,double[] wanted,int sign,Set<Material> materials) {','''double[] ray(int axis,double[] wanted,int sign,Set<Material> materials) {return ray(axis,wanted,sign,materials,null);}
    double[] rayNear(int axis,double[] wanted,int sign,Set<Material> materials,double datum){return ray(axis,wanted,sign,materials,datum);}
    private double[] ray(int axis,double[] wanted,int sign,Set<Material> materials,Double datum) {''').replace('double best=sign>0?Double.NEGATIVE_INFINITY:Double.POSITIVE_INFINITY;','double best=sign>0?Double.NEGATIVE_INFINITY:Double.POSITIVE_INFINITY,bestDistance=Double.POSITIVE_INFINITY;')
s=s.replace('best=sign>0?Math.max(best,at):Math.min(best,at);','if(datum==null)best=sign>0?Math.max(best,at):Math.min(best,at);else if(Math.abs(at-datum)<bestDistance){bestDistance=Math.abs(at-datum);best=at;}')
p.write_text(s,encoding='utf-8')
p=Path('src/main/java/com/wormzjl/icheme/science/equipment/EquipmentServices.java');s=p.read_text(encoding='utf-8')
s=s.replace('a=surface.ray(axis,a,sign,MOUNT);','a=surface.rayNear(axis,a,sign,MOUNT,anchor[axis]);')
s=s.replace('return surface.ray(axis,chosen,sign,materials);','return surface.rayNear(axis,chosen,sign,materials,chosen[axis]);')
start=s.index('        throw new IllegalArgumentException("No native service pad inside')
s=s[:start]+'''        // A narrow frame may have no room outside its outer leg. Its inward-facing native leg face is still a supported pad.
        double best=Double.POSITIVE_INFINITY;double[][] chosen=null;
        for(var polygon:surface.polygons){if(!MOUNT.contains(polygon.material()))continue;var v=polygon.positions();var n=cross(sub(Arrays.copyOfRange(v,3,6),Arrays.copyOfRange(v,0,3)),sub(Arrays.copyOfRange(v,6,9),Arrays.copyOfRange(v,0,3)));if(n[axis]*sign<=1e-9)continue;var point=new double[3];for(int i=0;i<v.length;i++)point[i%3]+=v[i]/polygon.size();
            var box=mount(surface,point,face,Math.min(width,.3),Math.min(height,.3),Math.min(depth,.18));double cost=dot(sub(point,anchor),sub(point,anchor));if(cost<best&&fits(shape,box)&&ServiceSurface.clear(point,zones)){best=cost;chosen=new double[][]{point,box[0],box[1]};}
        }if(chosen!=null)return chosen;
''' +s[start:]
p.write_text(s,encoding='utf-8')
