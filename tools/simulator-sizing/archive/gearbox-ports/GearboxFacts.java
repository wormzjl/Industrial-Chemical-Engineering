package com.wormzjl.icheme.science.equipment;
import com.wormzjl.icheme.runtime.fluid.ShaftUnit;
import com.wormzjl.icheme.science.machine.ShaftNetwork;
public class GearboxFacts {
    public static void main(String[] args) {
        System.out.println("New gearbox ratio="+ShaftUnit.Settings.placed(ShaftNetwork.Kind.GEARBOX).ratio());
        System.out.println("Legacy/explicit Auto ratio="+ShaftUnit.Settings.DEFAULT.ratio());
        System.out.println("Other new parts keep generic settings="+ShaftUnit.Settings.placed(ShaftNetwork.Kind.MOTOR).equals(ShaftUnit.Settings.DEFAULT));
        for(double power:new double[]{.75,75,20000}) {
            var shape=EquipmentShape.unit(EquipmentShape.Kind.GEARBOX,power,1,EquipmentShape.Heading.NORTH,0);
            var facets=ProcessUnit.of(shape).drawing().mesh().facets();
            long in=facets.stream().filter(f->f.material()==Mesh.Material.SHAFT_INPUT).count();
            long out=facets.stream().filter(f->f.material()==Mesh.Material.SHAFT_OUTPUT).count();
            System.out.println(power+" kW: input plaques="+in+", output plaques="+out+", ends="+shape.shaftEnds().size());
        }
    }
}
