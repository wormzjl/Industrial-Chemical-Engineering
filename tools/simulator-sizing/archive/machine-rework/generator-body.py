from pathlib import Path
p=Path('src/main/java/com/wormzjl/icheme/science/equipment/ProcessUnit.java');s=p.read_text(encoding='utf-8');start=s.index('    void generator(Frame f,');end=s.index('    private void generatorPanels',start)
s=s[:start]+'''    void generator(Frame f,double r,double z0,double z1,double floor) {
        var o=f.origin();var ax=f.axis();var across=cross(new double[]{0,1,0},ax);double y=o[1],length=z1-z0;
        // A broad alternator stator, enclosed end ventilation and bearing caps; no motor fins or external fan cowl.
        motorBox(f,across,z0+.12*length,z1-.12*length,y-.80*r,y+.80*r,-1.05*r,1.05*r,Material.GENERATOR_PANEL,true);
        for(double end:new double[]{z0,z1-.12*length}) {
            mesh.cylinder(f,.80*r,end,end+.12*length,12,Material.MOTOR_PLAIN);
            mesh.disc(f,.80*r,end,12,-1,Material.GENERATOR_PANEL);
            mesh.disc(f,.80*r,end+.12*length,12,1,Material.GENERATOR_PANEL);
        }
        mesh.cylinder(f,.30*r,z0-.04,z0,12,Material.MOTOR_PLAIN);
        double terminal=z0+.3*length,tc=terminal+.275*r;
        motorBox(f,across,terminal,terminal+.55*r,y+.76*r,y+1.45*r,-.35*r,.35*r,Material.MOTOR_PLAIN,false);
        terminalBox=new double[]{o[0]+tc*ax[0],y+1.215*r,o[2]+tc*ax[2]};
        for(int sign:new int[]{-1,1})for(double z:new double[]{z0+.08,z1-.25})
            motorBox(f,across,z,z+.17,y-r-.02,y-.55*r,Math.min(sign*.5*r,sign*.9*r),Math.max(sign*.5*r,sign*.9*r),Material.MOTOR_PLAIN,false);
        if(y-r-.02>floor+.01)motorBox(f,across,z0+.02,z1-.02,floor,y-r-.02,-.95*r,.95*r,Material.STEELWORK,true);
    }
'''+s[end:];p.write_text(s,encoding='utf-8')
