import com.wormzjl.icheme.science.equipment.*;
import java.nio.file.*;
import java.util.*;
public class CapturePorts {
    public static void main(String[] args)throws Exception {
        var lines=new ArrayList<String>();
        lines.add("# Crossflow pipe blocks from 622e35bde, before removing external headers");
        var kind=EquipmentShape.Kind.EXCHANGER;
        for(double d=ProcessUnit.minDiameter(kind);d<=ProcessUnit.maxDiameter(kind);d+=.25)
            for(int length=ProcessUnit.minLength(kind,d);length<=ProcessUnit.maxLength(kind,d);length++)
                for(var heading:EquipmentShape.Heading.values()) {
                    var shape=EquipmentShape.unit(kind,d,length,heading,ProcessUnit.minSupport(kind,d,length)).withExchangerType(ExchangerType.CROSSFLOW);
                    var line=new StringBuilder(d+":"+length+":"+heading);
                    for(var port:ShapeRasterizer.ports(shape))line.append(" ").append(port.cell().x()+port.dx()).append(",").append(port.cell().y()+port.dy()).append(",").append(port.cell().z()+port.dz());
                    lines.add(line.toString());
                }
        Files.write(Path.of(args[0]),lines);
    }
}
