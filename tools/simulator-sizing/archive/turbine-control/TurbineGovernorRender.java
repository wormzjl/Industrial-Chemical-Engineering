import com.wormzjl.icheme.client.gui.common.*;
import com.wormzjl.icheme.client.gui.common.kit.*;
import com.wormzjl.icheme.runtime.fluid.*;
import com.wormzjl.icheme.science.machine.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

public class TurbineGovernorRender {
    public static void main(String[] args) throws Exception {
        HeatDiagramRender.lang = HeatDiagramRender.lang(Path.of("src/main/resources/assets/icheme/lang/en_us.json"));
        HeatDiagramRender.font = HeatDiagramRender.GameFont.load(Path.of("build/moddev/artifacts/neoforge-21.1.219-client-extra-aka-minecraft-resources.jar"));
        var v = new MachineView(1, ShaftNetwork.Kind.STEAM_TURBINE, "RUNNING", 6000, 500000, 0, 0,
                ShaftUnit.Settings.DEFAULT, ShaftTrain.Trip.NONE, 1e6, 6000, 0, false, Double.NaN, Double.NaN, "", 0,
                1.5, 40e5, 4e5, null);
        var t = GuiPrototypes.DIALOG;
        var in = MachineColumn.input(v, true, true, MachineColumn.Mode.SPEED, t);
        var p = GuiPrototypes.machinePage("internal-turbine", in, "KT-101", v.status(), Map.of("speed", "6000"), MachineColumn.nameplate(v,t), true);
        for(var e : Map.of("light", PanelSkin.LIGHT, "dark", PanelSkin.DARK).entrySet())
            ImageIO.write(GuiPrototypes.render(p,e.getValue(),false),"png",Path.of("tmp/turbine-control/governor-"+e.getKey()+".png").toFile());
    }
}
