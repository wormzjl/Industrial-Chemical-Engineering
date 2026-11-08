import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
public class PortSheet {
    static BufferedImage crop(BufferedImage im){
        int x0=im.getWidth(),x1=0,y0=im.getHeight(),y1=0;
        for(int y=0;y<im.getHeight();y++)for(int x=0;x<im.getWidth();x++){
            int c=im.getRGB(x,y),r=c>>16&255,g=c>>8&255,b=c&255;
            if(g>r*1.08||b>r*1.15)continue;
            x0=Math.min(x0,x);y0=Math.min(y0,y);x1=Math.max(x1,x);y1=Math.max(y1,y);
        }
        x0=Math.max(0,x0-15);y0=Math.max(0,y0-15);x1=Math.min(im.getWidth()-1,x1+15);y1=Math.min(im.getHeight()-1,y1+15);
        return im.getSubimage(x0,y0,x1-x0+1,y1-y0+1);
    }
    public static void main(String[] args)throws Exception {
        var sheet=new BufferedImage(960,450,BufferedImage.TYPE_INT_RGB);var g=sheet.createGraphics();
        g.setColor(new Color(0xd7dede));g.fillRect(0,0,960,450);g.setFont(new Font("SansSerif",Font.PLAIN,18));g.setColor(new Color(0x26343b));
        for(int i=0;i<2;i++){
            var im=crop(ImageIO.read(Path.of(args[0],i==0?"motor-iso.png":"motor-iso2.png").toFile()));
            double scale=Math.min(440.0/im.getWidth(),380.0/im.getHeight());int w=(int)(im.getWidth()*scale),h=(int)(im.getHeight()*scale);
            g.drawImage(im,480*i+(480-w)/2,45+(380-h)/2,w,h,null);g.drawString(i==0?"Power: existing terminal cabinet":"Data: opposite accessory box",480*i+28,28);
        }
        g.dispose();ImageIO.write(sheet,"png",Path.of(args[0],"motor-ports.png").toFile());
    }
}
