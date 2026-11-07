import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
import java.util.*;

public class Sheets {
    public static void main(String[] args) throws Exception {
        var root=Path.of(args[0]);
        var files=Files.list(root).filter(p->p.toString().endsWith(".png")).sorted().toList();
        for(int start=0,page=1;start<files.size();start+=8,page++) {
            var sheet=new BufferedImage(1440,800,BufferedImage.TYPE_INT_RGB);
            var g=sheet.createGraphics(); g.setColor(new Color(27,32,38));g.fillRect(0,0,1440,800);
            g.setFont(new Font("SansSerif",Font.PLAIN,13));
            for(int at=start;at<Math.min(start+8,files.size());at++) {
                int col=(at-start)%4,row=(at-start)/4,x=col*360,y=row*400;
                var path=files.get(at); var image=ImageIO.read(path.toFile());
                double scale=Math.min(352.0/image.getWidth(),342.0/image.getHeight());
                int w=(int)(image.getWidth()*scale),h=(int)(image.getHeight()*scale);
                g.drawImage(image,x+(360-w)/2,y+48+(344-h)/2,w,h,null);
                String name=path.getFileName().toString().replace(".png","");
                int split=name.indexOf("_0_"); if(split<0) {var match=java.util.regex.Pattern.compile("_\\d+_").matcher(name);if(match.find())split=match.start();}
                g.setColor(Color.WHITE);g.drawString(String.format("%02d ",at+1)+(split<0?name:name.substring(0,split)),x+6,y+19);
                if(split>=0)g.drawString(name.substring(split+1)+"  / "+(args.length>2?args[2]:"pipe OD 0.797 m"),x+6,y+38);
            }
            g.dispose();ImageIO.write(sheet,"png",Path.of(args[1],"sheet-"+page+".png").toFile());
        }
        System.out.println(files.size()+" port views");
    }
}

