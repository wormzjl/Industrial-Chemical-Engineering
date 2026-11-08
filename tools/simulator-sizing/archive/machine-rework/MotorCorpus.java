import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
public class MotorCorpus {
    public static void main(String[] args)throws Exception {
        var lines=new ArrayList<String>();
        for(var duty:List.of(MachineDuty.motor(.75,300.0),MachineDuty.motor(22,3000.0),MachineDuty.motor(20000,12000.0),MachineDuty.motor(22,4,MachineDuty.Drive.FIXED))){
            var shape=EquipmentShape.unit(EquipmentShape.Kind.MOTOR,duty.power(),1,EquipmentShape.Heading.NORTH,0).withDuty(duty);
            var saved=new CompoundTag();EquipmentShellBlockEntity.writeShape(saved,shape);
            var row=new CompoundTag();row.put("Saved",saved);row.putString("Expected",shape.toString());lines.add(row.toString());
        }
        Files.write(Path.of("src/test/resources/compat/equipment/shapes-10.snbt"),lines);
        var table=Path.of("src/test/resources/compat/layouts/layouts.txt");var previous=Files.readAllLines(table);int at=0;
        for(var kind:List.of(EquipmentShape.Kind.MOTOR,EquipmentShape.Kind.GENERATOR)) {
            int sizeIndex=0;double low=ProcessUnit.minDiameter(kind),high=ProcessUnit.maxDiameter(kind);
            var sizes=new double[]{low,ProcessUnit.snap(kind,(low+high)/2),high};
            for(int i=0;i<previous.size();i++)if(previous.get(i).startsWith("EquipmentShape[kind="+kind+",")) {
                double size=sizes[sizeIndex++];at++;int length=ProcessUnit.defaultLength(kind,size);
                var shape=EquipmentShape.designed(kind,size,length,EquipmentShape.Heading.NORTH,ProcessUnit.defaultSupport(kind,size,length),ProcessUnit.Bank.ONE,0);
                var layout=ShapeRasterizer.layout(shape);var text=new StringBuilder(shape.toString().replace(", baseSpeed=0.0]","]").replace(", exchangerType=STANDARD]","]"));
                text.append(" | box ").append(shape.sizeX()).append(' ').append(shape.sizeY()).append(' ').append(shape.sizeZ()).append(" | shell ").append(layout.shell().size()).append(" interior ").append(layout.interior().size()).append(" | stamp ").append(Long.toHexString(ShapeRasterizer.stamp(shape))).append(" | pipes");
                for(var nozzle:ShapeRasterizer.ports(shape))text.append(' ').append(nozzle.cell().x()+nozzle.dx()).append(',').append(nozzle.cell().y()+nozzle.dy()).append(',').append(nozzle.cell().z()+nozzle.dz());
                var terminal=ShapeRasterizer.terminal(shape);if(terminal!=null)text.append(" | terminal ").append(terminal.x()).append(',').append(terminal.y()).append(',').append(terminal.z());
                previous.set(i,text.toString());
            }
        }
        Files.write(table,previous);System.out.println("Wrote shape fields 10 and "+at+" motor layout records");
    }
}
