package com.wormzjl.icheme.science.material;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.*;

/**
 * The {@code freezing}, {@code thermochemistry} and {@code flammability} sub-records a catalog's datasets carry, read from the
 * catalog's own resources with the loader's readers. No model reads them yet, so the catalog keeps none: the loader validates
 * each as it reads it, and only the data gates look them up (the tooling cleanup of batch 2026-11-04-open-thermo-data moved
 * these lookups out of {@link MaterialCatalog}).
 */
final class SubRecords {
    /** The elements a formula may name ({@link MaterialCatalog.Thermochemistry#ELEMENTS}), with their standard atomic weights, g/mol. */
    static final Map<String,Double> ATOMIC_WEIGHTS=Map.of("C",12.011,"H",1.008,"N",14.007,"O",15.999,"S",32.06);
    /** Each catalog's records by kind and id ({@code properties/icheme:tjl20_methane}), parsed once. */
    private static final Map<MaterialCatalog,Map<String,JsonObject>> RECORDS=Collections.synchronizedMap(new WeakHashMap<>());

    private SubRecords() {
    }

    /**
     * A compound's freezing point in a package: the {@code freezing} sub-record of the dataset the package takes for it; absent
     * for a dataset without one and for the water, whose water model holds its triple point and its ice.
     */
    static Optional<MaterialCatalog.Freezing> freezingPoint(MaterialCatalog catalog,String packageId,String componentId) {
        return dataset(catalog,packageId,componentId).filter(o->o.has("freezing")).map(o->MaterialCatalog.readFreezing(o.getAsJsonObject("freezing")));
    }

    /**
     * A compound's thermochemistry in a package: the {@code thermochemistry} sub-record of the dataset the package takes for it,
     * or of the package's water model for the water; absent where the record has none.
     */
    static Optional<MaterialCatalog.Thermochemistry> thermochemistry(MaterialCatalog catalog,String packageId,String componentId) {
        // W3b: Water in a package's cubic takes its thermochemistry from the water model where its cubic record carries none.
        var record=dataset(catalog,packageId,componentId).filter(o->o.has("thermochemistry")||!componentId.equals("Water")).orElseGet(()->water(catalog,packageId));
        return Optional.of(record).filter(o->o.has("thermochemistry")).map(o->MaterialCatalog.readThermochemistry(o.getAsJsonObject("thermochemistry")));
    }

    /**
     * A compound's flammability limits in a package: the {@code flammability} sub-record of the dataset the package takes for
     * it; absent for a dataset without one (a compound that does not burn) and for the water.
     */
    static Optional<MaterialCatalog.Flammability> flammability(MaterialCatalog catalog,String packageId,String componentId) {
        return dataset(catalog,packageId,componentId).filter(o->o.has("flammability")).map(o->MaterialCatalog.readFlammability(o.getAsJsonObject("flammability")));
    }

    /** The formula's molar mass, kg/mol. */
    static double molarMass(MaterialCatalog.Thermochemistry thermochemistry) {
        double grams=0;for(var e:thermochemistry.elements().entrySet())grams+=e.getValue()*ATOMIC_WEIGHTS.get(e.getKey());return grams/1000;
    }

    /** The dataset record the package takes for a compound; empty for the water; a compound outside the package is refused. */
    private static Optional<JsonObject> dataset(MaterialCatalog catalog,String packageId,String componentId) {
        var p=catalog.requirePackage(packageId);String id=p.canonicalId(Objects.requireNonNull(componentId,"componentId"));
        int index=p.components().indexOf(id);
        if(index>=0)return Optional.of(record(catalog,"properties",p.properties().get(index).id()));
        if(id.equals("Water"))return Optional.empty();
        throw new IllegalArgumentException("Component is outside package: "+componentId);
    }

    /** The package's water model record: the one its method names. */
    private static JsonObject water(MaterialCatalog catalog,String packageId) {
        var method=record(catalog,"methods",record(catalog,"packages",packageId).get("method").getAsString());
        return record(catalog,"water",method.get("water_model").getAsString());
    }

    private static JsonObject record(MaterialCatalog catalog,String kind,String id) {
        var record=RECORDS.computeIfAbsent(catalog,SubRecords::index).get(kind+"/"+id);
        if(record==null)throw new IllegalStateException("No "+kind+" record "+id+" in the catalog's resources");
        return record;
    }

    private static Map<String,JsonObject> index(MaterialCatalog catalog) {
        var records=new HashMap<String,JsonObject>();
        for(var e:catalog.resources().entrySet()) {
            String path=e.getKey();String kind=path.substring(path.indexOf("materials/")+"materials/".length()).split("/")[0];
            var o=JsonParser.parseString(e.getValue()).getAsJsonObject();records.put(kind+"/"+o.get("id").getAsString(),o);
        }
        return Map.copyOf(records);
    }
}
