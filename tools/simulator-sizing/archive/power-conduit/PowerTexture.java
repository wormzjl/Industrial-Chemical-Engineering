import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.Path;
public class PowerTexture {
    public static void main(String[] args)throws Exception {
        var source=ImageIO.read(Path.of("src/main/resources/assets/icheme/textures/block/equipment_pipe.png").toFile());
        var im=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<32;y++)for(int x=0;x<32;x++){
            int rgb=source.getRGB(x%source.getWidth(),y%source.getHeight());int l=((rgb>>16&255)+(rgb>>8&255)+(rgb&255))/3;
            int grain=(int)Math.round((l-140)*.5);int brush=y%4==0?1:0;
            // Brushed metallic casing with narrow orange circuit-identification bands, rather than bare cable insulation.
            boolean band=y==7||y==8||y==23||y==24;
            int r=(band?188:134)+grain+brush,g=(band?126:138)+grain+brush,b=(band?45:140)+grain+brush;
            im.setRGB(x,y,0xff000000|Math.clamp(r,0,255)<<16|Math.clamp(g,0,255)<<8|Math.clamp(b,0,255));
        }
        ImageIO.write(im,"png",Path.of("src/main/resources/assets/icheme/textures/block/power_cable.png").toFile());
        var icon=ImageIO.read(Path.of("src/main/resources/assets/icheme/textures/item/data_cable.png").toFile());
        for(int y=0;y<icon.getHeight();y++)for(int x=0;x<icon.getWidth();x++){
            int rgb=icon.getRGB(x,y),a=rgb>>>24,r=rgb>>16&255,g=rgb>>8&255,b=rgb&255;
            if(a>0){if(r>b*1.2&&g>b*1.2){g=(int)(g*.76);b=Math.min(255,b+8);}else{r=Math.min(255,r+15);g=Math.min(255,g+15);b=Math.min(255,b+15);}icon.setRGB(x,y,a<<24|r<<16|g<<8|b);}
        }
        ImageIO.write(icon,"png",Path.of("src/main/resources/assets/icheme/textures/item/power_cable.png").toFile());
    }
}
