import java.nio.file.Files;
import java.nio.file.Path;
public final class StationModels {
    public static void main(String[] args)throws Exception {
        var root=Path.of(args[0]);
        Files.writeString(root.resolve("equipment_constructor.json"),ConstructorAssets.model(ConstructorAssets.texturePaths(),ConstructorAssets.boxes()));
        Files.writeString(root.resolve("process_simulator.json"),ConstructorAssets.model(SimulatorAssets.texturePaths(),SimulatorAssets.boxes()));
    }
}
