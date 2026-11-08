import java.util.*;
import com.wormzjl.icheme.science.equipment.*;
public final class ControlPortRender {
 public static void main(String[] args)throws Exception {
  int i=0;for(var key:List.of(new ConduitGeometry.Key(0,0).connect(0,0,0).connect(3,-12,12),new ConduitGeometry.Key(0,0).connect(0,0,0).connect(3,5,8),new ConduitGeometry.Key(0,0).connect(0,0,0).connect(1,-12,12))){
   var q=new ArrayList<PowerLookRender.Quad>();PowerLookRender.texture="data_cable";PowerLookRender.markerColour=CableMedium.DATA.colour();
   for(var p:ConduitGeometry.polygons(key,false))PowerLookRender.mesh(q,p,0x376674);
   PowerLookRender.render(q,new double[]{-1,-.8,1.3},args[0]+"/offset-"+(i++)+".png");
  }
 }
}
