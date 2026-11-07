import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;

/** Software view of the same boxes and UVs written to the vanilla constructor model. */
public class SimulatorRender {
    record Quad(double[][] points, double[][] uv, double[] normal, BufferedImage texture) {}
    static double[] cross(double[] a, double[] b) { return new double[]{a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]}; }
    static double dot(double[] a, double[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
    static double[] unit(double[] a) { double r=Math.sqrt(dot(a,a)); return new double[]{a[0]/r,a[1]/r,a[2]/r}; }
    static double edge(double[] a,double[] b,double x,double y) { return (x-a[0])*(b[1]-a[1])-(y-a[1])*(b[0]-a[0]); }
    public static void main(String[] args) throws Exception {
        var textures=new HashMap<String,BufferedImage>();
        for (var entry:SimulatorAssets.texturePaths().entrySet()) textures.put(entry.getKey(),ImageIO.read(Path.of(args[0],"textures/block/"+entry.getValue().substring("icheme:block/".length())+".png").toFile()));
        var quads=new ArrayList<Quad>();
        for(var box:SimulatorAssets.boxes()) {
            var a=box.from();var b=box.to();double x=a[0],y=a[1],z=a[2],X=b[0],Y=b[1],Z=b[2];
            for(var f:box.faces().entrySet()) {
                double[][] p; double[] n;
                switch(f.getKey()) {
                    case "north" -> { p=new double[][]{{X,Y,z},{X,y,z},{x,y,z},{x,Y,z}};n=new double[]{0,0,-1}; }
                    case "south" -> { p=new double[][]{{x,Y,Z},{x,y,Z},{X,y,Z},{X,Y,Z}};n=new double[]{0,0,1}; }
                    case "east" -> { p=new double[][]{{X,Y,Z},{X,y,Z},{X,y,z},{X,Y,z}};n=new double[]{1,0,0}; }
                    case "west" -> { p=new double[][]{{x,Y,z},{x,y,z},{x,y,Z},{x,Y,Z}};n=new double[]{-1,0,0}; }
                    case "up" -> { p=new double[][]{{x,Y,z},{x,Y,Z},{X,Y,Z},{X,Y,z}};n=new double[]{0,1,0}; }
                    default -> { p=new double[][]{{x,y,Z},{x,y,z},{X,y,z},{X,y,Z}};n=new double[]{0,-1,0}; }
                }
                var u=f.getValue().uv();
                quads.add(new Quad(p,new double[][]{{u[0]/16,u[1]/16},{u[0]/16,u[3]/16},{u[2]/16,u[3]/16},{u[2]/16,u[1]/16}},n,textures.get(f.getValue().texture())));
            }
        }
        render(quads,new double[]{-1,-.8,1.3},args[1]+"-front.png");
        render(quads,new double[]{1,-.8,-1.3},args[1]+"-rear.png");
        render(quads,new double[]{-.15,-1,.35},args[1]+"-top.png");
    }
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
            if(dot(q.normal(),depth)>=0)continue;
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
                    var t=q.texture();int rgb=t.getRGB(Math.min(t.getWidth()-1,Math.max(0,(int)(u*t.getWidth()))),Math.min(t.getHeight()-1,Math.max(0,(int)(v*t.getHeight()))));
                    int r=(int)((rgb>>16&255)*light),g=(int)((rgb>>8&255)*light),bcol=(int)((rgb&255)*light);image.setRGB(x,y,r<<16|g<<8|bcol);zbuffer[y*w+x]=z;
                }
            }
        }
        ImageIO.write(image,"png",Path.of(output).toFile());System.out.println("Wrote "+output);
    }
}
