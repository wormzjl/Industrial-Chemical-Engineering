import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;
public class ConduitSectionsRender {
    record Quad(double[][] points,double[][] uv,double[] normal,double[] shades,BufferedImage texture) {}
    static double[] cross(double[] a, double[] b) { return new double[]{a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]}; }
    static double dot(double[] a, double[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
    static double[] unit(double[] a) { double r=Math.sqrt(dot(a,a)); return new double[]{a[0]/r,a[1]/r,a[2]/r}; }
    static double edge(double[] a,double[] b,double x,double y) { return (x-a[0])*(b[1]-a[1])-(y-a[1])*(b[0]-a[0]); }
    public static void main(String[] args)throws Exception {
        java.nio.file.Files.createDirectories(Path.of(args[0]));blocks=5;
        for(var medium:com.wormzjl.icheme.science.equipment.CableMedium.values()){
            var quads=new ArrayList<Quad>();texture=medium.texture();markerColour=medium.colour();
            var rack=new com.wormzjl.icheme.science.equipment.RackGeometry.Rack(5,0);
            var lane=new com.wormzjl.icheme.science.equipment.RackGeometry.Lane(0,1,48,48,8);
            long points=com.wormzjl.icheme.science.equipment.RackGeometry.points(rack,lane);
            var lead=com.wormzjl.icheme.science.equipment.ConduitGeometry.lead(new double[]{1,0,0},new double[]{1,.25,0},new double[]{1,0,0},medium);
            add(quads,lead,0,.5,.5);
            add(quads,com.wormzjl.icheme.science.equipment.ConduitGeometry.polygons(new com.wormzjl.icheme.science.equipment.ConduitGeometry.Key(0,0,medium).connect(4,8,0).connect(5,0,0),false),1,0,0);
            int left=(int)(points>>40)&1023,right=(int)(points>>50)&1023;
            add(quads,com.wormzjl.icheme.science.equipment.ConduitGeometry.polygons(new com.wormzjl.icheme.science.equipment.ConduitGeometry.Key(0,0,medium).connect(4,0,0).connect(5,signed(left&31),signed(left>>5&31)),false),2,0,0);
            add(quads,com.wormzjl.icheme.science.equipment.ConduitGeometry.lane(rack,lane,new com.wormzjl.icheme.science.equipment.ConduitGeometry.Key(48,points,medium),true),3,0,0);
            add(quads,com.wormzjl.icheme.science.equipment.ConduitGeometry.polygons(new com.wormzjl.icheme.science.equipment.ConduitGeometry.Key(0,0,medium).connect(4,signed(right&31),signed(right>>5&31)).connect(5,0,0),false),4,0,0);
            render(quads,new double[]{-.25,-.65,1.3},args[0]+"/"+medium.name().toLowerCase()+"-sections.png");
        }
    }
    static int signed(int n){return n>=16?n-32:n;}
    static void add(List<Quad> quads,List<com.wormzjl.icheme.science.equipment.Mesh.Polygon> polygons,double x,double y,double z)throws Exception{
        for(var q:polygons){var v=q.positions().clone();for(int i=0;i<v.length;i+=3){v[i]+=x;v[i+1]+=y;v[i+2]+=z;}mesh(quads,new com.wormzjl.icheme.science.equipment.Mesh.Polygon(v,q.uvs(),q.normals(),q.material()),0x376674);}
    }
    static String texture="power_cable"; static int markerColour=0xffffff; static int blocks=1;
    static void mesh(List<Quad> quads,com.wormzjl.icheme.science.equipment.Mesh.Polygon q)throws Exception {mesh(quads,q,0);}
    static void mesh(List<Quad> quads,com.wormzjl.icheme.science.equipment.Mesh.Polygon q,int colour)throws Exception {var v=q.positions();var ns=q.normals();for(int first=1;first+1<q.size();first+=2){var corners=new int[]{0,first,first+1,Math.min(first+2,q.size()-1)};var p=new double[4][3];for(int i=0;i<4;i++)for(int k=0;k<3;k++)p[i][k]=16*v[corners[i]*3+k];var t=new BufferedImage(1,1,BufferedImage.TYPE_INT_RGB);t.setRGB(0,0,colour!=0?colour:switch(q.material()){case OPENING->0x17282d;case GUARD->0xb99d38;case FLANGE->0x747a7d;case STEELWORK->0x434d54;default->0x8b9398;});var uv=new double[4][2];if(colour==0x376674){t=ImageIO.read(Path.of("src/main/resources/assets/icheme/textures/block/"+(q.material()==com.wormzjl.icheme.science.equipment.Mesh.Material.FLANGE?"equipment_flange.png":q.material()==com.wormzjl.icheme.science.equipment.Mesh.Material.OPENING?"equipment_opening.png":q.material()==com.wormzjl.icheme.science.equipment.Mesh.Material.GUARD?"painted_pipe.png":texture+".png")).toFile());if(q.material()==com.wormzjl.icheme.science.equipment.Mesh.Material.GUARD){var painted=new BufferedImage(t.getWidth(),t.getHeight(),BufferedImage.TYPE_INT_RGB);for(int py=0;py<t.getHeight();py++)for(int px=0;px<t.getWidth();px++){int c=t.getRGB(px,py);painted.setRGB(px,py,((c>>16&255)*(markerColour>>16&255)/255)<<16|((c>>8&255)*(markerColour>>8&255)/255)<<8|(c&255)*(markerColour&255)/255);}t=painted;}var coordinates=q.uvs();for(int i=0;i<4;i++){uv[i][0]=coordinates[corners[i]*2];uv[i][1]=coordinates[corners[i]*2+1];}}var shades=new double[4];for(int i=0;i<4;i++)shades[i]=Math.round(255*net.neoforged.neoforge.client.model.lighting.QuadLighter.calculateShade((float)ns[corners[i]*3],(float)ns[corners[i]*3+1],(float)ns[corners[i]*3+2],false))/255.0;quads.add(new Quad(p,uv,new double[]{ns[0],ns[1],ns[2]},shades,t));}}
    static void render(List<Quad> quads,double[] view,String output) throws Exception {
        var depth=unit(view);var right=unit(cross(depth,new double[]{0,1,0}));var up=cross(right,depth);
        double loX=1e9,hiX=-1e9,loY=1e9,hiY=-1e9;
        for(int i=0;i<8;i++) {
            var p=new double[]{(i&1)*16*blocks,(i>>1&1)*16,(i>>2&1)*16};
            double sx=dot(p,right),sy=dot(p,up);loX=Math.min(loX,sx);hiX=Math.max(hiX,sx);loY=Math.min(loY,sy);hiY=Math.max(hiY,sy);
        }
        double scale=(blocks==1?560:1100)/Math.max(hiX-loX,hiY-loY);int w=(int)((hiX-loX)*scale)+48,h=(int)((hiY-loY)*scale)+48;
        var image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);var zbuffer=new double[w*h];Arrays.fill(zbuffer,Double.POSITIVE_INFINITY);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)image.setRGB(x,y,0xD7DEDE);
        for(var q:quads) {
            double[] pa=q.points()[0],pb=q.points()[1],pc=q.points()[2]; var ab=new double[]{pb[0]-pa[0],pb[1]-pa[1],pb[2]-pa[2]}; var ac=new double[]{pc[0]-pa[0],pc[1]-pa[1],pc[2]-pa[2]}; if(dot(cross(ab,ac),depth)>=0)continue;
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
                    light=aa*q.shades()[tri[0]]+bb*q.shades()[tri[1]]+cc*q.shades()[tri[2]];int r=(int)((rgb>>16&255)*light),g=(int)((rgb>>8&255)*light),bcol=(int)((rgb&255)*light);image.setRGB(x,y,r<<16|g<<8|bcol);zbuffer[y*w+x]=z;
                }
            }
        }
        ImageIO.write(image,"png",Path.of(output).toFile());System.out.println("Wrote "+output);
    }
}
