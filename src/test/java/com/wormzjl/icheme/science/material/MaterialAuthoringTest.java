package com.wormzjl.icheme.science.material;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class MaterialAuthoringTest {
 private static final String ROOT="data/icheme/materials/",ID="icheme:tjl20_methane";
 @Test void physicsIdentityIgnoresAssaysButTracksActualSelectedProperties() {
  var c=MaterialCatalog.bundled();var axis=c.requirePackage(ID).components();
  assertEquals(c.physicsContent(ID,axis),c.physicsContent("icheme:tjl20_methane_nitrogen",axis));
  var resources=new HashMap<>(c.resources());var a=JsonParser.parseString(resources.get(ROOT+"assays/medium_crude.json")).getAsJsonObject();
  a.getAsJsonObject("amounts_by_component").addProperty("crude_pc01",.8);resources.put(ROOT+"assays/medium_crude.json",a.toString());
  var changed=MaterialCatalog.parse(resources);
  assertNotEquals(c.requirePackage(ID).assays(),changed.requirePackage(ID).assays());
  assertEquals(c.physicsContent(ID,axis),changed.physicsContent(ID,axis));
  var p=JsonParser.parseString(resources.get(ROOT+"properties/crude_pc08.json")).getAsJsonObject();
  p.addProperty("standard_liquid_density_kg_per_m3",913);resources.put(ROOT+"properties/crude_pc08.json",p.toString());
  changed=MaterialCatalog.parse(resources);
  assertNotEquals(c.physicsContent(ID,axis),changed.physicsContent(ID,axis));
  assertEquals(c.physicsContent(ID,List.of("crude_pc07")),changed.physicsContent(ID,List.of("crude_pc07")));
  assertNotEquals(c.physicsContent(ID,List.of("crude_pc08")),changed.physicsContent(ID,List.of("crude_pc08")));
 }
 @Test void packageCyclesAndBadReferencesFailWithResourceContext() {
  var records=new HashMap<>(MaterialCatalog.bundled().resources());String path=ROOT+"packages/bad.json";
  String head="{\"schema_version\":1,\"revision\":\"test-r1\",\"method\":\"icheme:crude_pr78\",";
  records.put(path,head+"\"id\":\"test:cycle\",\"extends\":\"test:cycle\",\"components\":[\"Ethane\"]}");
  var error=assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(records));assertTrue(error.getMessage().contains(path));assertTrue(error.getMessage().contains("cycle"));
  // A compound with no default dataset must name one; a named dataset must exist and be the compound's.
  records.put(path,head+"\"id\":\"test:bad\",\"components\":[\"Water\"]}");
  error=assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(records));assertTrue(error.getMessage().contains(path)&&error.getMessage().contains("no default dataset"),error.getMessage());
  records.put(path,head+"\"id\":\"test:bad\",\"components\":[{\"id\":\"Ethane\",\"properties\":\"test:missing\"}]}");
  error=assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(records));assertTrue(error.getMessage().contains(path)&&error.getMessage().contains("test:missing"),error.getMessage());
  records.put(path,head+"\"id\":\"test:bad\",\"components\":[{\"id\":\"Ethane\",\"properties\":\"icheme:tjl19_propane\"}]}");
  error=assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(records));assertTrue(error.getMessage().contains(path)&&error.getMessage().contains("Ethane"),error.getMessage());
 }
 @Test void sparseAssaysRejectUnknownIdentitiesAndConflictingPositionalForms() {
  var c=MaterialCatalog.bundled();var records=new HashMap<>(c.resources());String path=ROOT+"assays/medium_crude.json";
  var a=JsonParser.parseString(records.get(path)).getAsJsonObject();a.getAsJsonObject("amounts_by_component").addProperty("retired_pc13",0);records.put(path,a.toString());
  assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(records));
  a.getAsJsonObject("amounts_by_component").remove("retired_pc13");a.add("amounts",new JsonArray());records.put(path,a.toString());
  assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(records));
 }
 @Test void identityProjectionPreservesAmountsAndRejectsNonzeroRetiredSpecies() {
  var source=new MaterialAxis(List.of("Water","Ethane","Nitrogen"));var target=new MaterialAxis(List.of("Nitrogen","Water","Ethane","Propane"));
  assertArrayEquals(new double[]{3,1,2,0},target.project(source,new double[]{1,2,3}));
  assertThrows(IllegalArgumentException.class,()->new MaterialAxis(List.of("Ethane")).project(source,new double[]{1,2,0}));
  assertArrayEquals(new double[]{2},new MaterialAxis(List.of("Ethane")).project(source,new double[]{0,2,0}));
  assertThrows(IllegalArgumentException.class,()->new MaterialAxis(List.of("Ethane","Ethane")));
 }
}
