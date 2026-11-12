package com.wormzjl.icheme.science.material;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.wormzjl.icheme.science.fluid.transport.MixtureViscosity;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
class MaterialFluidDataTest {
 private static final String ID="icheme:tjl20_methane",ROOT="data/icheme/materials/transport/";
 @AfterEach void reset(){MaterialRuntime.reset();}
 /** Appends {@code -edited} to a record's {@code revision}: the bump a numerical edit declares (owner decision 2026-10-26). */
 private static void bump(JsonObject record){record.addProperty("revision",record.get("revision").getAsString()+"-edited");}
 @Test void fluidReferenceEditsMoveTheFluidRevisionNotThePhysicsAndCapturedModelsStayStable() throws Exception {
  var original=MaterialCatalog.bundled();var resources=new HashMap<>(original.resources());String path=ROOT+"dissolved_viscosity.json";
  var data=JsonParser.parseString(resources.get(path)).getAsJsonObject();var first=data.getAsJsonArray("curves").get(0).getAsJsonObject();
  var values=first.getAsJsonArray("viscosities_pascal_seconds");values.set(0,new JsonPrimitive(values.get(0).getAsDouble()*2));bump(data);resources.put(path,data.toString());
  var changed=MaterialCatalog.parse(resources);var p=original.requirePackage(ID);var amounts=new double[original.conservedComponents(ID).size()];
  amounts[p.components().indexOf("Methane")]=.05;amounts[p.components().indexOf("crude_pc12")]=.95;
  var captured=new MixtureViscosity(original,ID);double before=captured.liquid(293.15,amounts).pascalSeconds();
  assertEquals(original.physicsContent(ID,p.components()),changed.physicsContent(ID,p.components()));
  assertNotEquals(original.fluidRevision(ID),changed.fluidRevision(ID));
  // A fluid reference record enters only the identity of a package that holds one of its compounds (option B, 2026-11-04).
  assertEquals(original.restrict(ID,List.of("crude_pc12")).fluidRevision(ID),changed.restrict(ID,List.of("crude_pc12")).fluidRevision(ID),"a record for other compounds");
  assertNotEquals(captured.revision(),new MixtureViscosity(changed,ID).revision());
  var admitted=new CountDownLatch(1);var released=new CountDownLatch(1);
  try(var worker=Executors.newSingleThreadExecutor()) {
   var result=worker.submit(()->{admitted.countDown();if(!released.await(5,TimeUnit.SECONDS))throw new AssertionError("release");return captured.liquid(293.15,amounts).pascalSeconds();});
   assertTrue(admitted.await(5,TimeUnit.SECONDS));MaterialRuntime.publish(changed);released.countDown();
   assertEquals(before,result.get(5,TimeUnit.SECONDS));
  } finally {released.countDown();}
  assertEquals(Math.pow(2,.05),new MixtureViscosity(changed,ID).liquid(293.15,amounts).pascalSeconds()/before,1e-12);
  path=ROOT+"liquid_calibration.json";data=JsonParser.parseString(resources.get(path)).getAsJsonObject();
  first=data.getAsJsonArray("points").get(0).getAsJsonObject();first.addProperty("molarVolumeCubicMetres",first.get("molarVolumeCubicMetres").getAsDouble()*1.01);bump(data);resources.put(path,data.toString());
  var recalibrated=MaterialCatalog.parse(resources);
  assertNotEquals(changed.fluidRevision(ID),recalibrated.fluidRevision(ID));
  assertEquals(changed.physicsContent(ID,p.components()),recalibrated.physicsContent(ID,p.components()));
 }
 /** A package without a spine anchors its liquid volumes by the calibration points (HydrocarbonModel reads no
  * volume_translation), whatever its property record translates (t44d's MATERIALS.md reading: the point was dropped from
  * the identity, and a recalibration went unseen): the recalibrated point is the model's liquid volume there. */
 @Test void aCalibrationPointAnchorsAPackageWithoutASpineWhateverTheRecordTranslates() {
  var resources=new HashMap<>(MaterialCatalog.bundled().resources());String property="data/icheme/materials/properties/tjl20_methane.json";
  var methane=JsonParser.parseString(resources.get(property)).getAsJsonObject();
  var pilot=JsonParser.parseString(PilotCryogenicTestCatalog.withPilotResources().get("data/icheme/materials/properties/pilot_methane.json")).getAsJsonObject();
  methane.add("volume_translation",pilot.get("volume_translation"));resources.put(property,methane.toString());
  var translated=MaterialCatalog.parse(resources);assertTrue(translated.volumeTranslation(ID,"Methane").isPresent());
  var point=translated.fluidData().volumeReferences().get("Methane");
  String path=ROOT+"liquid_calibration.json";var data=JsonParser.parseString(resources.get(path)).getAsJsonObject();
  var first=data.getAsJsonArray("points").get(0).getAsJsonObject();assertEquals("Methane",first.get("component").getAsString());
  first.addProperty("molarVolumeCubicMetres",first.get("molarVolumeCubicMetres").getAsDouble()*1.01);bump(data);resources.put(path,data.toString());
  var recalibrated=MaterialCatalog.parse(resources);
  assertNotEquals(translated.fluidRevision(ID),recalibrated.fluidRevision(ID));
  var components=translated.requirePackage(ID).components();double[] pure=new double[components.size()];pure[components.indexOf("Methane")]=1;
  var liquid=com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root.LIQUID;
  var translatedModel=new com.wormzjl.icheme.science.fluid.thermo.HydrocarbonModel(translated,ID);var recalibratedModel=new com.wormzjl.icheme.science.fluid.thermo.HydrocarbonModel(recalibrated,ID);
  double before=translatedModel.phase(point.temperatureKelvin(),point.pressurePascal(),com.wormzjl.icheme.science.fluid.thermo.TestPhases.conserved(translatedModel,pure),liquid).molarVolume();
  double after=recalibratedModel.phase(point.temperatureKelvin(),point.pressurePascal(),com.wormzjl.icheme.science.fluid.thermo.TestPhases.conserved(recalibratedModel,pure),liquid).molarVolume();
  assertEquals(point.molarVolumeCubicMetres(),before,1e-9*before,"the calibration point, not the record's translation");
  assertEquals(1.01,after/before,1e-9,"the recalibrated point");
 }
 /** The network package's water model without its ice fails loading, with the water record's file and the field, so a
  * /reload of such data is refused before publication instead of holding a running network at its model build. */
 @Test void aNetworkWaterModelWithoutIceFailsLoadingWithItsFileAndField() {
  var resources=new HashMap<>(MaterialCatalog.bundled().resources());String path="data/icheme/materials/water/water.json";
  var water=JsonParser.parseString(resources.get(path)).getAsJsonObject();assertNotNull(water.remove("ice"));resources.put(path,water.toString());
  var failure=assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(resources));
  assertTrue(failure.getMessage().startsWith(path+": ice: required"),failure.getMessage());
 }
 /** A solid record missing or mistyping a field fails loading with its file and that field (it was a NullPointerException). */
 @Test void aMalformedSolidRecordFailsWithItsResourceAndField() {
  String path="data/icheme/materials/solids/demo_particle.json";
  for(var field:new String[]{"revision","density_kg_per_m3","heat_capacity_j_per_kg_kelvin"})for(boolean missing:new boolean[]{true,false}) {
   var resources=new HashMap<>(MaterialCatalog.bundled().resources());var record=JsonParser.parseString(resources.get(path)).getAsJsonObject();
   if(missing)record.remove(field);else record.add(field,new JsonArray());
   resources.put(path,record.toString());
   var failure=assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(resources));
   assertTrue(failure.getMessage().startsWith(path+": "+field+": "),failure.getMessage());
  }
 }
 @Test void malformedFluidRecordsFailBeforePublicationWithResourceAndFieldContext() {
  var resources=new HashMap<>(MaterialCatalog.bundled().resources());String path=ROOT+"dissolved_viscosity.json";
  var data=JsonParser.parseString(resources.get(path)).getAsJsonObject();var first=data.getAsJsonArray("curves").get(0).getAsJsonObject();
  var temperatures=first.getAsJsonArray("temperatures_kelvin");temperatures.set(1,temperatures.get(0));resources.put(path,data.toString());
  var failure=assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(resources));assertTrue(failure.getMessage().contains(path));assertTrue(failure.getMessage().contains("temperature"));
 }
}
