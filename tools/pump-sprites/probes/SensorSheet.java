import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Inventory sensor identification at enlarged and small sizes. Args: render-folder output.png. */
public final class SensorSheet {
    public static void main(String[] args) throws Exception {
        var names=new String[]{"pressure_probe","temperature_probe","flowmeter","digital_level_meter","speed_sensor"};
        var labels=new String[]{"P  Pressure","T  Temperature","F  Flow","L  Level","N  Speed"};
        var sheet=new BufferedImage(900,260,BufferedImage.TYPE_INT_RGB);var g=sheet.createGraphics();
        try {
            g.setColor(new Color(0xd7dede));g.fillRect(0,0,900,260);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setFont(new Font(Font.MONOSPACED,Font.BOLD,14));
            for(int i=0;i<names.length;i++) {
                var image=ImageIO.read(Path.of(args[0],names[i]+"-front.png").toFile());
                g.drawImage(image,i*180+10,0,160,160,null);g.setColor(new Color(0x26343b));g.drawString(labels[i],i*180+15,175);
                var small=new BufferedImage(32,32,BufferedImage.TYPE_INT_RGB);var s=small.createGraphics();
                try {s.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);s.drawImage(image,0,0,32,32,null);} finally {s.dispose();}
                g.drawImage(small,i*180+54,185,64,64,null);
            }
        } finally {g.dispose();}
        ImageIO.write(sheet,"png",Path.of(args[1]).toFile());
    }
}
