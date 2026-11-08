import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;
public class AllFacesRender {
    record Quad(double[][] points,double[][] uv,double[] normal,BufferedImage texture) {}
    static double[] cross(double[] a, double[] b) { return new double[]{a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]}; }
    static double dot(double[] a, double[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
    static double[] unit(double[] a) { double r=Math.sqrt(dot(a,a)); return new double[]{a[0]/r,a[1]/r,a[2]/r}; }
    static double edge(double[] a,double[] b,double x,double y) { return (x-a[0])*(b[1]-a[1])-(y-a[1])*(b[0]-a[0]); }
    public static void main(String[] args)throws Exception {
        for(var medium:com.wormzjl.icheme.science.equipment.CableMedium.values())for(int joints:new int[]{18}){
            var quads=new ArrayList<Quad>();var key=new com.wormzjl.icheme.science.equipment.ConduitGeometry.Key(joints,0,medium);
            texture=medium.texture();for(var p:com.wormzjl.icheme.science.equipment.ConduitGeometry.polygons(key))mesh(quads,p,0x376674);
            render(quads,new double[]{-1,-.8,1.3},args[0]+"/"+medium.name().toLowerCase()+"-"+joints+".png");
        }
    }
    static String texture="data_cable";
    static void mesh(List<Quad> quads,com.wormzjl.icheme.science.equipment.Mesh.Polygon q)throws Exception {mesh(quads,q,0);}
    static void mesh(List<Quad> quads,com.wormzjl.icheme.science.equipment.Mesh.Polygon q,int colour)throws Exception {var v=q.positions();var ns=q.normals();for(int first=1;first+1<q.size();first+=2){var corners=new int[]{0,first,first+1,Math.min(first+2,q.size()-1)};var p=new double[4][3];for(int i=0;i<4;i++)for(int k=0;k<3;k++)p[i][k]=16*v[corners[i]*3+k];var t=new BufferedImage(1,1,BufferedImage.TYPE_INT_RGB);t.setRGB(0,0,colour!=0?colour:switch(q.material()){case OPENING->0x17282d;case GUARD->0xb99d38;case FLANGE->0x747a7d;case STEELWORK->0x434d54;default->0x8b9398;});var uv=new double[4][2];if(colour==0x376674){t=ImageIO.read(Path.of("src/main/resources/assets/icheme/textures/block/"+(q.material()==com.wormzjl.icheme.science.equipment.Mesh.Material.FLANGE?"equipment_flange.png":texture+".png")).toFile());var coordinates=q.uvs();for(int i=0;i<4;i++){uv[i][0]=coordinates[corners[i]*2];uv[i][1]=coordinates[corners[i]*2+1];}}quads.add(new Quad(p,uv,new double[]{ns[0],ns[1],ns[2]},t));}}
    static void render(List<Quad> quads,double[] view,String output) throws Exception {
        var depth=unit(view);var right=unit(cross(depth,new double[]{0,1,0}));var up=cross(right,depth);
        double loX=1e9,hiX=-1e9,loY=1e9,hiY=-1e9;
        for(int i=0;i<8;i++) {
            var p=new double[]{(i&1)*16,(i>>1&1)*16,(i>>2&1)*16};
            double sx=dot(p,right),sy=dot(p,up);loX=Math.min(loX,sx);hiX=Math.max(hiX,sx);loY=Math.min(loY,sy);hiY=Math.max(hiY,sy);
        }
        double scale=560/Math.max(hiX-loX,hiY-loY);int w=(int)((hiX-loX)*scale)+48,h=(int)((hiY-loY)*scale)+48;
        var image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);var zbuffer=new double[w*h];Arrays.fill(zbuffer,Double.POSITIVE_INFINITY);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)image.setRGB(x,y,0xD7DEDE);
        for(var q:quads) {
            
            double[][] screen=new double[4][3];
            for(int i=0;i<4;i++){var p=q.points()[i];screen[i]=new double[]{24+(dot(p,right)-loX)*scale,h-24-(dot(p,up)-loY)*scale,dot(p,depth)};}
            double[] n=q.normal();double light=n[0]*n[0]*.6+n[1]*n[1]*(3+n[1])/4+n[2]*n[2]*.8;
            for(var tri:new int[][]{{0,1,2},{0,2,3}}) {
                var a=screen[tri[0]];var b=screen[tri[1]];var c=screen[tri[2]];double area=edge(a,b,c[0],c[1]);if(Math.abs(area)<1e-9)continue;
                int minX=Math.max(0,(int)Math.floor(Math.min(a[0],Math.min(b[0],c[0])))),maxX=Math.min(w-1,(int)Math.ceil(Math.max(a[0],Math.max(b[0],c[0]))));
                int minY=Math.max(0,(int)Math.floor(Math.min(a[1],Math.min(b[1],c[1])))),maxY=Math.min(h-1,(int)Math.ceil(Math.max(a[1],Math.max(b[1],c[1]))));
                for(int y=minY;y<=maxY;y++)for(int x=minX;x<=maxX;x++) {
                    double aa=edge(b,c,x+.5,y+.5)/area,bb=edge(c,a,x+.5,y+.5)/area,cc=1-aa-bb;
                    if(aa<0||bb<0||cc<0)continue;double z=aa*a[2]+bb*b[2]+cc*c[2];if(z>=zbuffer[y*w+x])continue;
                    double u=aa*q.uv()[tri[0]][0]+bb*q.uv()[tri[1]][0]+cc*q.uv()[tri[2]][0],v=aa*q.uv()[tri[0]][1]+bb*q.uv()[tri[1]][1]+cc*q.uv()[tri[2]][1];
                    var t=q.texture();int rgb=t.getRGB(Math.floorMod((int)Math.floor(u*t.getWidth()),t.getWidth()),Math.floorMod((int)Math.floor(v*t.getHeight()),t.getHeight()));
                    int r=(int)((rgb>>16&255)*light),g=(int)((rgb>>8&255)*light),bcol=(int)((rgb&255)*light);image.setRGB(x,y,r<<16|g<<8|bcol);zbuffer[y*w+x]=z;
                }
            }
        }
        ImageIO.write(image,"png",Path.of(output).toFile());System.out.println("Wrote "+output);
    }
}
