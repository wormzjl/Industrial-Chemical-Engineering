import java.io.File;
public class PortSprites {
    public static void main(String[] args)throws Exception {
        PumpSprites.writeRgb(new File(args[0]),"shaft_input",PumpSprites.shaftPort(true));
        PumpSprites.writeRgb(new File(args[0]),"shaft_output",PumpSprites.shaftPort(false));
    }
}
