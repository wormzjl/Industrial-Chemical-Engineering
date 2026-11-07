import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.Path;
public class ConduitTexture {
    public static void main(String[] args)throws Exception {
        var source=ImageIO.read(Path.of("src/main/resources/assets/icheme/textures/block/equipment_pipe.png").toFile());
        var im=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<32;y++)for(int x=0;x<32;x++){
            int rgb=source.getRGB(x%source.getWidth(),y%source.getHeight());double l=((rgb>>16&255)+(rgb>>8&255)+(rgb&255))/3.0;
            int grain=(int)Math.round((l-140)*.45);int brush=y%4==0?1:0;
            int r=187+grain+brush,g=161+grain+brush,b=61+grain+brush;
            im.setRGB(x,y,0xff000000|Math.clamp(r,0,255)<<16|Math.clamp(g,0,255)<<8|Math.clamp(b,0,255));
        }
        ImageIO.write(im,"png",Path.of("src/main/resources/assets/icheme/textures/block/data_cable.png").toFile());
    }
}
