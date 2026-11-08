import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.Path;
public class ConduitSheet {
    public static void main(String[] args)throws Exception {
        var sheet=new BufferedImage(960,230,BufferedImage.TYPE_INT_RGB);var g=sheet.createGraphics();
        g.setColor(new Color(0xd7dede));g.fillRect(0,0,960,230);g.setFont(new Font("SansSerif",Font.PLAIN,16));
        var names=new String[]{"straight","elbow","tee","off-centre"};var labels=new String[]{"Straight","Elbow","Junction","Off-centre entry"};
        for(int k=0;k<names.length;k++){
            var im=ImageIO.read(Path.of("tmp/conduit-rework/renders/"+names[k]+".png").toFile());int x0=im.getWidth(),y0=im.getHeight(),x1=0,y1=0;
            for(int y=0;y<im.getHeight();y++)for(int x=0;x<im.getWidth();x++)if((im.getRGB(x,y)&0xffffff)!=0xd7dede){x0=Math.min(x0,x);y0=Math.min(y0,y);x1=Math.max(x1,x);y1=Math.max(y1,y);}
            var crop=im.getSubimage(x0,y0,x1-x0+1,y1-y0+1);double scale=Math.min(200.0/crop.getWidth(),140.0/crop.getHeight());int w=(int)(crop.getWidth()*scale),h=(int)(crop.getHeight()*scale);
            g.drawImage(crop,k*240+(240-w)/2,30+(140-h)/2,w,h,null);g.setColor(new Color(0x26343b));g.drawString(labels[k],k*240+25,207);
        }
        g.dispose();ImageIO.write(sheet,"png",Path.of("tmp/conduit-rework/conduit-preview.png").toFile());
    }
}
