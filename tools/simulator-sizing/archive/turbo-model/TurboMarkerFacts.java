package com.wormzjl.icheme.science.equipment;
public class TurboMarkerFacts {
    public static void main(String[] args) {
        for (double d : new double[]{.3, .7, 1.5}) {
            var shape = EquipmentShape.unit(EquipmentShape.Kind.CENTRIFUGAL_COMPRESSOR, d, 2, EquipmentShape.Heading.NORTH, 0);
            for (int k = 0; k < shape.nozzles().size(); k++) {
                var mesh = new Mesh();
                var nozzle = shape.nozzles().get(k);
                PortMarkings.band(mesh, shape, k, 0, PortMarkings.flow(shape.kind(), nozzle.role()));
                System.out.println(d + " " + nozzle.role() + " arrow facets=" + mesh.facets().size());
            }
        }
    }
}
