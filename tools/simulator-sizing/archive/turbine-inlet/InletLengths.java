package com.wormzjl.icheme.science.equipment;
public class InletLengths {
    public static void main(String[] args) {
        for (int tenth = 3; tenth <= 30; tenth++) {
            var s = EquipmentShape.unit(EquipmentShape.Kind.STEAM_TURBINE, tenth / 10.0, 1, EquipmentShape.Heading.NORTH, 0);
            var n = s.nozzles().stream().filter(v -> v.role() == EquipmentShape.Role.STEAM).findFirst().orElseThrow();
            System.out.printf("%.1f: nozzle %.4f, taper room %.4f%n", tenth / 10.0, n.length(), n.length() - PipeSizes.flangeThickness(.8) - PipeSizes.hub(.8) - .01);
        }
    }
}
