import java.nio.file.*;
import java.util.*;
public class SharedAssetsCheck {
    public static void main(String[] args) throws Exception {
        var textures = new LinkedHashMap<String,String>();
        textures.put("particle", "icheme:block/constructor_cabinet");
        for (var name : ConstructorAssets.sprites().keySet()) textures.put(name, "icheme:block/constructor_" + name);
        var generated = ConstructorAssets.model(textures, ConstructorAssets.boxes());
        var shipped = Files.readString(Path.of("src/main/resources/assets/icheme/models/block/equipment_constructor.json")).replace("\r\n", "\n");
        if (!shipped.equals(generated)) throw new IllegalStateException("Shared writer changed the constructor model");
        System.out.println("Shared model writer preserves the constructor model exactly.");
    }
}
