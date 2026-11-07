import com.wormzjl.icheme.client.gui.common.ConstructorColumn;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.PanelSkin;
import com.wormzjl.icheme.science.equipment.*;
import java.nio.file.*;
import java.util.*;

public class ConstructorOptionsRender {
    public static void main(String[] args) throws Exception {
        HeatDiagramRender.lang = HeatDiagramRender.lang(Path.of(args[0]));
        HeatDiagramRender.font = HeatDiagramRender.GameFont.load(Path.of("build/moddev/artifacts/neoforge-21.1.219-client-extra-aka-minecraft-resources.jar"));
        var out = Path.of(args[1]);
        Files.createDirectories(out);
        for (var form : List.of(VesselPlacement.Form.VERTICAL, VesselPlacement.Form.EXCHANGER, VesselPlacement.Form.MOTOR)) {
            var sizes = ConstructorDefaults.sizes(form);
            var unit = form.designed() ? UnitDesign.of(form.kind()) : null;
            int support = unit == null ? EquipmentShape.defaultSupport(sizes.diameter(), form.orientation()) : unit.defaultSupport(sizes.diameter(), sizes.length());
            var duty = unit != null && unit.hasDuty() ? unit.duty(sizes.diameter(), sizes.length()) : MachineDuty.NONE;
            var input = new ConstructorColumn.Design(form, sizes.diameter(), sizes.length(), support, 3, 0, 0,
                    ProcessUnit.Bank.ONE, null, new ConstructorColumn.Outlet(1, 0, GuiPrototypes.STANDING), 16, TowerBank.ONE, duty);
            var page = ConstructorColumn.design(input, GuiPrototypes.DIALOG);
            var prototype = new GuiPrototypes.Prototype(form.name(), 464, 254, (d, skin) ->
                    GuiPrototypes.constructorDoor(d, page, "Equipment", "change", "", Palette.ACCENT, Map.of()));
            HeatDiagramRender.grid(out.resolve(form.name().toLowerCase(Locale.ROOT) + ".png"),
                    List.of("Light", "Dark"), List.of(GuiPrototypes.render(prototype, PanelSkin.LIGHT, false), GuiPrototypes.render(prototype, PanelSkin.DARK, false)));
        }
    }
}
