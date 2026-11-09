import java.io.File;
import javax.imageio.ImageIO;
public class LineSprites {
    public static void main(String[] args)throws Exception {
        var folder=new File(args[0]);
        ImageIO.write(VesselTextures.line(VesselTextures.PIPE),"png",new File(folder,"equipment_pipe.png"));
        ImageIO.write(VesselTextures.line(VesselTextures.FLANGE),"png",new File(folder,"equipment_flange.png"));
        PaintedSprites.main(new String[]{new File(folder,"equipment_pipe.png").toString(),new File(folder,"painted_pipe.png").toString(),"236"});
        PaintedSprites.main(new String[]{new File(folder,"equipment_flange.png").toString(),new File(folder,"painted_flange.png").toString(),"200"});
    }
}
