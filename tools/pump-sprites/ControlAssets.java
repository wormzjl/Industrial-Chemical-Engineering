import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** Controller/DCS finishes using the workstation palette and writer, with their existing model envelopes. */
public final class ControlAssets {
    private static final int GASKET=0x354249, EDGE=0xA5AEAF, HIGHLIGHT=0xEFF1EE;
    private static final int GLASS=0x102B36, GRID=0x173A46, CYAN=0xA6D4D9, AMBER=0xC3AD69;
    private ControlAssets() {}

    static Map<String,String> texturePaths(boolean dcs) {
        var paths=new LinkedHashMap<String,String>();
        paths.put("particle","icheme:block/pump_casting");
        paths.put("case","icheme:block/pump_casting");
        paths.put("metal","icheme:block/equipment_steelwork");
        paths.put("side","icheme:block/control_panel_side");
        paths.put("rear","icheme:block/control_panel_rear");
        paths.put("door","icheme:block/control_panel_service");
        paths.put("front","icheme:block/"+(dcs?"dcs_front":"controller_front"));
        return paths;
    }

    static List<ConstructorAssets.Box> boxes(boolean dcs) {
        var boxes=new ArrayList<ConstructorAssets.Box>();
        boxes.add(dcs?ConstructorAssets.box("steel plinth",1,0,2,15,2,14,"metal"):
                ConstructorAssets.box("steel mounting foot",3,0,5,13,2,12,"metal"));
        if(dcs) boxes.add(cabinet("lower service cabinet",2,2,7,14,10,14,"door"));
        boxes.add(dcs?cabinet("DCS operator terminal",1,9,3,15,16,13,"front"):
                cabinet("local loop controller",2,2,3,14,15,13,"front"));
        return List.copyOf(boxes);
    }

    private static ConstructorAssets.Box cabinet(String name,double x,double y,double z,double X,double Y,double Z,String front) {
        var box=ConstructorAssets.box(name,x,y,z,X,Y,Z,"case");
        box.faces().put("north",new ConstructorAssets.Face(front));
        box.faces().put("south",new ConstructorAssets.Face("rear"));
        box.faces().put("east",new ConstructorAssets.Face("side"));
        box.faces().put("west",new ConstructorAssets.Face("side"));
        return box;
    }

    private static BufferedImage painted(BufferedImage steel,int width,int height) {
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)image.setRGB(x,y,steel.getRGB(x%steel.getWidth(),y%steel.getHeight()));
        return image;
    }

    private static void rect(Graphics2D g,int x,int y,int w,int h,int colour) { ConstructorAssets.rect(g,x,y,w,h,colour); }
    private static void frame(Graphics2D g,int x,int y,int w,int h,int light,int dark) {
        rect(g,x,y,w,1,light);rect(g,x,y,1,h,light);rect(g,x,y+h-1,w,1,dark);rect(g,x+w-1,y,1,h,dark);
    }
    private static void screw(Graphics2D g,int x,int y) {
        rect(g,x,y,3,3,EDGE);rect(g,x,y,3,1,HIGHLIGHT);rect(g,x+1,y+1,2,1,GASKET);
    }
    private static void key(Graphics2D g,int x,int y,int w,int h,int colour) {
        rect(g,x-1,y-1,w+2,h+2,GASKET);rect(g,x,y,w,h,colour);frame(g,x,y,w,h,HIGHLIGHT,0x536169);
    }
    private static void glass(Graphics2D g,int x,int y,int w,int h) {
        rect(g,x-3,y-3,w+6,h+6,GASKET);frame(g,x-3,y-3,w+6,h+6,0xAAB4B5,0x19272E);
        rect(g,x,y,w,h,GLASS);rect(g,x,y,w,6,0x284B58);
        for(int xx=x+5;xx<x+w;xx+=8)rect(g,xx,y+8,1,h-9,GRID);
        for(int yy=y+10;yy<y+h;yy+=8)rect(g,x+1,yy,w-2,1,GRID);
    }
    private static void label(Graphics2D g,String text,int x,int y,int colour) {
        g.setColor(new Color(colour));
        for(char c:text.toCharArray()) {
            String glyph=switch(c) {
                case 'L'->"100100100100111";case 'C'->"111100100100111";case 'D'->"110101101101110";
                case 'S'->"111100111001111";case 'A'->"010101111101101";case 'M'->"101111111101101";
                default->"000000000000000";
            };
            for(int row=0;row<5;row++)for(int col=0;col<3;col++)if(glyph.charAt(3*row+col)=='1')g.fillRect(x+col,y+row,1,1);
            x+=4;
        }
    }

    private static BufferedImage local(BufferedImage steel) {
        var image=painted(steel,96,104);var g=image.createGraphics();
        try {
            frame(g,1,1,94,102,HIGHLIGHT,0x7E898D);frame(g,6,5,83,94,EDGE,GASKET);
            for(int y:new int[]{19,80}){rect(g,3,y,5,11,EDGE);rect(g,4,y,2,10,HIGHLIGHT);}
            rect(g,14,8,22,8,GASKET);label(g,"LC",17,10,0xCBD3D1);
            glass(g,15,24,62,40);label(g,"LC",18,25,CYAN);
            g.setColor(new Color(CYAN));g.drawRect(22,39,12,14);g.drawLine(22,44,34,44);
            g.drawLine(28,39,28,35);g.drawLine(28,35,55,35);g.drawLine(55,35,55,44);
            g.drawLine(34,49,50,49);g.drawLine(55,54,55,58);g.drawLine(55,58,28,58);g.drawLine(28,58,28,53);
            g.drawPolygon(new int[]{50,60,50},new int[]{44,49,54},3);g.drawPolygon(new int[]{60,50,60},new int[]{44,49,54},3);
            rect(g,64,38,7,2,AMBER);rect(g,64,44,7,2,0x54828B);rect(g,64,50,7,2,0x8EC7AB);
            for(int x:new int[]{11,79})for(int y:new int[]{20,66})screw(g,x,y);
            key(g,16,77,13,7,0x789093);key(g,35,77,13,7,0xB5A367);
            label(g,"M",20,88,0x536169);label(g,"A",40,88,0x536169);
            rect(g,60,77,13,13,EDGE);rect(g,61,78,11,11,GASKET);rect(g,66,79,2,8,0xD5DDDA);
            rect(g,84,79,3,12,GASKET);rect(g,85,82,1,6,EDGE);
        } finally { g.dispose(); }
        return image;
    }

    private static BufferedImage dcs(BufferedImage steel) {
        var image=painted(steel,112,56);var g=image.createGraphics();
        try {
            frame(g,1,1,110,54,HIGHLIGHT,0x7E898D);glass(g,8,8,96,32);label(g,"DCS",11,9,CYAN);
            g.setColor(new Color(0x46717A));g.drawLine(15,23,91,23);
            for(int x:new int[]{18,43,68}) {
                rect(g,x,19,19,16,0x264652);frame(g,x,19,19,16,0x54828B,0x0C2530);
                g.setColor(new Color(CYAN));g.drawOval(x+3,22,5,5);g.drawLine(x+8,24,x+14,24);
                g.setColor(new Color(0x8EC7AB));g.drawPolyline(new int[]{x+3,x+6,x+9,x+12,x+15},new int[]{32,29,31,27,28},5);
            }
            rect(g,93,18,7,2,AMBER);rect(g,93,25,7,2,0x8EC7AB);rect(g,93,32,7,2,0x54828B);
            for(int x=13;x<83;x+=14)key(g,x,46,9,4,0x8B9C9D);
            key(g,92,46,8,4,0xB5A367);
            for(int x:new int[]{3,105})for(int y:new int[]{3,48})screw(g,x,y);
        } finally { g.dispose(); }
        return image;
    }

    private static BufferedImage panel(BufferedImage steel,boolean vents) {
        var image=painted(steel,64,64);var g=image.createGraphics();
        try {
            frame(g,1,1,62,62,HIGHLIGHT,0x7E898D);frame(g,6,6,52,52,EDGE,GASKET);
            for(int x:new int[]{8,53})for(int y:new int[]{8,53})screw(g,x,y);
            if(vents)for(int y=22;y<47;y+=5) {
                rect(g,14,y,36,2,0x526169);rect(g,15,y+2,35,1,0xB4BEBD);
            }
            else {rect(g,14,13,20,7,GASKET);rect(g,16,15,15,1,0x9AA9AB);rect(g,16,17,10,1,0x9AA9AB);}
        } finally { g.dispose(); }
        return image;
    }

    private static BufferedImage service(BufferedImage steel) {
        var image=painted(steel,96,64);var g=image.createGraphics();
        try {
            frame(g,2,2,92,60,HIGHLIGHT,0x7E898D);frame(g,7,6,82,52,EDGE,GASKET);
            for(int y:new int[]{13,43}){rect(g,4,y,5,9,EDGE);rect(g,5,y,2,8,HIGHLIGHT);}
            rect(g,78,28,4,12,GASKET);rect(g,79,31,1,6,0xD1D8D5);
            rect(g,15,13,28,8,GASKET);label(g,"DCS",18,15,0xCBD3D1);
            for(int y=34;y<50;y+=4){rect(g,18,y,34,1,0x526169);rect(g,19,y+1,33,1,0xB4BEBD);}
        } finally { g.dispose(); }
        return image;
    }

    private static BufferedImage sensorCase(BufferedImage steel) {
        var image=painted(steel,32,32);var g=image.createGraphics();
        try {
            frame(g,1,1,30,30,HIGHLIGHT,0x7E898D);frame(g,4,4,24,24,EDGE,0x7E898D);
            for(int x:new int[]{2,27})for(int y:new int[]{2,27})screw(g,x,y);
        } finally { g.dispose(); }
        return image;
    }

    private static BufferedImage sensor(BufferedImage steel,char type) {
        var image=painted(steel,32,32);var g=image.createGraphics();
        try {
            frame(g,0,0,32,32,HIGHLIGHT,0x7E898D);
            rect(g,3,3,26,22,GASKET);frame(g,3,3,26,22,EDGE,0x19272E);
            rect(g,5,5,22,18,GLASS);rect(g,5,5,22,1,0x284B58);
            String[] glyph=switch(type) {
                case 'p'->new String[]{"11110","10001","10001","11110","10000","10000","10000"};
                case 't'->new String[]{"11111","00100","00100","00100","00100","00100","00100"};
                case 'f'->new String[]{"11111","10000","10000","11110","10000","10000","10000"};
                case 'l'->new String[]{"10000","10000","10000","10000","10000","10000","11111"};
                case 'n'->new String[]{"10001","11001","11001","10101","10011","10011","10001"};
                default->throw new IllegalArgumentException("Unknown sensor type");
            };
            for(int y=0;y<7;y++)for(int x=0;x<5;x++)if(glyph[y].charAt(x)=='1')rect(g,11+2*x,7+2*y,2,2,CYAN);
            rect(g,8,27,16,3,AMBER);rect(g,8,30,16,1,0x7E898D);
            for(int x:new int[]{0,29})for(int y:new int[]{0,29})screw(g,x,y);
        } finally { g.dispose(); }
        return image;
    }

    public static void main(String[] args) throws Exception {
        var assets=Path.of(args[0]);var texture=assets.resolve("textures/block");
        var steel=ImageIO.read(texture.resolve("pump_casting.png").toFile());
        var images=new LinkedHashMap<String,BufferedImage>();
        images.put("controller_front",local(steel));images.put("dcs_front",dcs(steel));
        images.put("control_panel_side",panel(steel,true));images.put("control_panel_rear",panel(steel,false));images.put("control_panel_service",service(steel));
        images.put("sensor_case",sensorCase(steel));
        for(char kind:new char[]{'p','t','f','l','n'})images.put("sensor_"+kind,sensor(steel,kind));
        for(var entry:images.entrySet())ImageIO.write(entry.getValue(),"png",texture.resolve(entry.getKey()+".png").toFile());
        for(boolean remote:new boolean[]{false,true})Files.writeString(assets.resolve("models/block/"+(remote?"dcs":"local_controller")+".json"),ConstructorAssets.model(texturePaths(remote),boxes(remote)));
        System.out.println("Wrote controller/DCS and five sensor finishes; existing element bounds retained.");
    }
}
