import com.wormzjl.icheme.science.equipment.*;
import java.nio.file.Path;
import java.util.*;

/** Source-mesh views of an offset junction and real standing/lying drum terminal mounts. Args: indexed output folder. */
public final class ControlPortProbe {
    public static void main(String[] args)throws Exception {
        var output=Path.of(args[0]);
        PowerLookRender.texture=CableMedium.DATA.texture();PowerLookRender.markerColour=CableMedium.DATA.colour();
        var key=new ConduitGeometry.Key(0,0).connect(0,0,0).connect(1,-10,10).connect(5,0,0);
        var junction=ConduitGeometry.polygons(key,false);var point=key.point(1);int missing=0;
        for(int i=0;i<8;i++){
            double theta=2*Math.PI*i/8,x=point[0]+.0625*Math.cos(theta),z=point[2]+.0625*Math.sin(theta);boolean found=false;
            for(var p:junction)if(p.material()==Mesh.Material.PIPE)for(int v=0;v<p.positions().length;v+=3)
                if(Math.abs(p.positions()[v]-x)+Math.abs(p.positions()[v+1]-point[1])+Math.abs(p.positions()[v+2]-z)<1e-8)found=true;
            if(!found)missing++;
        }
        System.out.println("OFFSET_JUNCTION missing_body_rim_vertices="+missing+" / 8");
        var quads=new ArrayList<PowerLookRender.Quad>();
        for(var p:junction)PowerLookRender.mesh(quads,p,0x376674);
        PowerLookRender.render(quads,new double[]{-1,-.8,1.3},output.resolve("offset-junction.png").toString());
        for(var orientation:new EquipmentShape.Orientation[]{EquipmentShape.Orientation.VERTICAL,EquipmentShape.Orientation.HORIZONTAL}){
            var shape=EquipmentShape.drum(2.5,6,orientation,EquipmentShape.Heading.NORTH,EquipmentShape.defaultSupport(2.5,orientation));
            var socket=DataSocketGeometry.equipment(shape);double[] p={socket.x(),socket.y(),socket.z()};
            int axis=socket.face()<2?1:socket.face()<4?2:0;var normal=new double[3];normal[axis]=(socket.face()&1)==0?-1:1;
            quads.clear();for(var polygon:ShapeRasterizer.renderPreview(shape,RenderDetail.NORMAL))PowerLookRender.mesh(quads,polygon);
            for(var polygon:DataPortGeometry.polygons(p,normal,true))PowerLookRender.mesh(quads,polygon,polygon.material()==Mesh.Material.GUARD?CableMedium.DATA.colour():0);
            PowerLookRender.crop=new double[]{p[0]-.65,p[1]-.65,p[2]-.65,p[0]+.65,p[1]+.65,p[2]+.65};
            double[] view={-normal[0]+.35*normal[2],-.45,-normal[2]-.35*normal[0]};
            PowerLookRender.render(quads,view,output.resolve("drum-"+orientation.name().toLowerCase()+".png").toString());
            System.out.println("DRUM_PAD "+orientation+" "+socket);
        }
        PowerLookRender.crop=null;
    }
}
