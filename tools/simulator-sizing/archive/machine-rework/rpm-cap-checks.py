from pathlib import Path
p=Path('src/test/java/com/wormzjl/icheme/science/machine/RotatingDriveReworkTest.java');s=p.read_text(encoding='utf-8').replace('Motor.availablePower(22000,3000,4500)','Motor.availablePower(22000,1500,3000)');at=s.index('    @Test void inlineRatings')
s=s[:at]+'''    @Test void manualAndAutomaticMotorsNeverExceed3000Rpm() {
        for(double base:new double[]{1500,3000,12000})for(var mode:List.of(ShaftTrain.Mode.SPEED,ShaftTrain.Mode.REGULATE_FLOW)){
            var driver=new ShaftTrain.Driver(ShaftTrain.Drive.VARIABLE_MOTOR,22000,base,4,true,mode,10,0,ShaftTrain.Trip.NONE,false);
            var measure=new ShaftTrain.Measure(0,0,false,1,100,Double.NaN,Double.NaN);
            assertEquals(3000,ShaftTrain.step(driver,2950,measure).speed(),1e-9);
            assertTrue(driver.setSpeed()<=Motor.MAXIMUM_RPM);
        }
    }
'''+s[at:];p.write_text(s,encoding='utf-8')
