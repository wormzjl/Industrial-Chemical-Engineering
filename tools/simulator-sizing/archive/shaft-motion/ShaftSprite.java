import java.io.File;
public class ShaftSprite {
    public static void main(String[] args)throws Exception {
        PumpSprites.writeRgb(new File(args[0]), "shaft_steel", PumpSprites.shaft());
    }
}
