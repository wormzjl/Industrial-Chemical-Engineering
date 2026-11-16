package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.runtime.fluid.FluidView;
import com.wormzjl.icheme.runtime.fluid.HeatUnit;
import com.wormzjl.icheme.runtime.instrument.Sensor;
import com.wormzjl.icheme.science.display.DisplayUnit.FlowRateUnit;
import com.wormzjl.icheme.science.display.DisplayUnit.PressureUnit;
import com.wormzjl.icheme.science.display.DisplayUnit.TemperatureUnit;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

import static com.wormzjl.icheme.client.gui.common.kit.Format.number;

/**
 * What a fluid device's door column reads (finding C1, batch 2026-11-13-codebase-review; moved out of
 * {@code FluidDeviceScreen}): the Readings group's meters, the Phases, a tank's Level, a pump's Suction, a casing's Drive and
 * a fan unit's Power, from the view the bucket sent, in the player's units and words. The screen builds one from its menu's
 * data, its Overview's stream, its Phases' basis and its units, and puts the groups into {@link DeviceColumn.Input}. Display
 * arithmetic on what the server sent; no process state is computed. No Minecraft classes.
 *
 * @param port the Overview's stream (a heat unit's port), or null for the node's own contents and on every other page
 * @param mass whether flows and the tables read by mass, else by moles
 * @param texts the screen's lang lines under {@code gui.icheme.fluid.}
 * @param dense the row of the shown contents that holds one dense fluid ({@link #denseRow}; -1 for none): its phase reads
 *              "Dense fluid", a tank's level line "No interface", a pump's gas meter its head share (S5 of batch
 *              2026-11-15-supercritical-fluids)
 */
public record DeviceMeters(Data data,@Nullable FluidView.Port port,PhaseRatioBasis phaseBasis,TemperatureUnit temperatureUnit,PressureUnit pressureUnit,
        FlowRateUnit flowRateUnit,boolean mass,HeatControlColumn.Texts texts,int dense) {
    /** The meters of contents that read no dense fluid ({@link #dense} -1). */
    public DeviceMeters(Data data,@Nullable FluidView.Port port,PhaseRatioBasis phaseBasis,TemperatureUnit temperatureUnit,PressureUnit pressureUnit,
            FlowRateUnit flowRateUnit,boolean mass,HeatControlColumn.Texts texts){this(data,port,phaseBasis,temperatureUnit,pressureUnit,flowRateUnit,mass,texts,-1);}
    /** These meters with the shown contents' dense row {@code row} ({@link DenseLabel#row}; -1 for none). */
    public DeviceMeters withDense(int row){return new DeviceMeters(data,port,phaseBasis,temperatureUnit,pressureUnit,flowRateUnit,mass,texts,row);}
    /**
     * The row of the shown contents that holds one dense fluid above reduced density 0.5 on {@code model} (S5 of batch
     * 2026-11-15-supercritical-fluids, {@link DenseLabel#row(com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics, List, double, double, double[], double[][])}):
     * the contents' temperature and pressure, phase volumes and amounts. One cubic root: the screen evaluates it once per
     * published view and stream, and keeps it.
     */
    public int denseRow(@Nullable com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics model){
        var view=data.view();var port=selectedPort();var info=view.pipeInfo();var state=view.state();
        double t,p;double[] volumes;
        if(port!=null){t=port.temperature()==null?Double.NaN:port.temperature();p=port.pressure()==null?Double.NaN:port.pressure();volumes=port.phaseVolumes();}
        else if(info!=null){var line=info.reading();t=line==null?Double.NaN:line.temperature();p=line==null?Double.NaN:line.pressure();volumes=info.contents().phaseVolumes();}
        else if(state!=null){t=state.temperature();p=state.pressure();volumes=state.phaseVolumes();}
        else return -1;
        return DenseLabel.row(model,data.components(),t,p,volumes,amounts());
    }
    /**
     * What the meters read of the menu's data (the screen copies it from its menu): the device's kind and view, the component axis
     * and its molecular weights (kg/mol), whether its applied power is On, a controllable heat side's applied control (null
     * for any other device), whether it holds a cooling tower's controls, and its instruments' readings.
     */
    public record Data(TopologyCompiler.Kind kind,FluidView view,List<String> components,List<Double> molecularWeights,boolean powerOn,
            @Nullable HeatUnit.Control heatControl,boolean tower,List<Sensor.Reading> sensors) {
    }

    private String tr(String key,Object... args){return texts.get(key,args);}
    private boolean pipe(){return data.kind()==TopologyCompiler.Kind.PIPE;}
    private boolean tank(){return data.kind()==TopologyCompiler.Kind.RESERVOIR;}
    private boolean generator(){return data.kind()==TopologyCompiler.Kind.GENERATOR;}
    private boolean tower(){return data.tower();}
    /** An air cooler's side: its fans switch as a mover does. */
    private boolean fans(){return data.view().heat()!=null&&data.view().heat().fans()!=null;}
    /** A fired heater's coil. */
    private boolean burners(){return data.view().heat()!=null&&data.view().heat().burners()!=null;}
    private FluidView.Converter converter(){return data.view().converter();}
    private FluidView.Port selectedPort(){return port;}
    private String flowUnit(){return flowRateUnit.unit(mass?"kg":"kmol");}
    private String temperatureValue(double kelvin){return value(temperatureUnit.display(kelvin));}
    private String pressureValue(Double pascals){return pascals==null?"—":pressureUnit.number(pascals);}
    public static String value(double v){return Double.isFinite(v)?number(v):"—";}
    public static String quantity(double value,String unit){return (Double.isFinite(value)?number(value):"—")+" "+unit;}
    private static DeviceColumn.Meter meter(String key,String value,String unit){return new DeviceColumn.Meter(key,value,unit,null);}
    /**
     * The Readings group's meters, in the player's units: one value a meter, its unit after it, what it reads on hover. A
     * switch on the column shows its own position, a tank's fill is its Level group, a valve's opening its Valve group.
     */
    public List<DeviceColumn.Meter> meters(){
        var view=data.view();var state=view.state();var rows=new ArrayList<DeviceColumn.Meter>();
        String t=temperatureUnit.symbol(),p=pressureUnit.symbol();
        if(view.tower()!=null){
            var rate=view.tower().rates();
            rows.add(meter("inlet_temperature",rate==null||rate.inletFlow()==0?"—":temperatureValue(rate.inletTemperature()),t));
            rows.add(meter("tower_basin_temperature",state==null?"—":temperatureValue(state.temperature()),t));
            rows.add(new DeviceColumn.Meter("bottom_pressure",pressureValue(view.tank()==null?null:view.tank().bottomPressure()),p,tr("bottom_pressure.hint")));
            rows.add(meter("tower_cooling_duty",rate==null?"—":value(rate.coolingDutyWatt()/1000),"kW"));
            rows.add(meter("tower_return",rate==null?"—":value(flowRateUnit.display(rate.inletFlow())),flowRateUnit.unit("kg")));
            rows.add(meter("tower_evaporation",rate==null?"—":value(flowRateUnit.display(rate.waterLoss())),flowRateUnit.unit("kg")));
            // What else leaves for the air (gas, flash vapour, volatile compounds, less the air dissolved): only when there is any.
            if(rate!=null&&Math.abs(rate.massLoss()-rate.waterLoss())>1e-9)rows.add(meter("tower_other_losses",value(flowRateUnit.display(rate.massLoss()-rate.waterLoss())),flowRateUnit.unit("kg")));
            if(view.tower().specification().draft()==com.wormzjl.icheme.science.fluid.network.CoolingTower.Draft.INDUCED){
                rows.add(meter("fan_power",rate==null?"—":value(rate.fill().fanPowerWatt()/1000),"kW"));
                rows.add(meter("tower_speed",rate==null?"—":value(rate.fill().speed()*100),"%"));
            }
            return List.copyOf(rows);
        }
        // A heat side's burners and fans switch on its Control tab.
        if(fans()||burners())rows.add(meter(fans()?"fans_state":"burners_state",tr(data.powerOn()?"state_on":"state_off"),""));
        // A plain pipe's line reading (OWNER_TEST_REVIEW.md section 6): the stream it carries, as its gauge reads it.
        if(pipe()&&view.pipeInfo().reading()!=null){
            var line=view.pipeInfo().reading();
            rows.add(new DeviceColumn.Meter("temperature",temperatureValue(line.temperature()),t,tr("line_temperature.hint")));
            rows.add(meter("pressure",pressureValue(line.pressure()),p));
        }
        if(tank()){
            var vessel=view.tank();boolean empty=vessel!=null&&state==null;
            rows.add(meter("temperature",state==null?"—":temperatureValue(state.temperature()),t));
            rows.add(new DeviceColumn.Meter("top_pressure",pressureValue(vessel==null?(state==null?null:state.pressure()):vessel.topPressure()),p,tr("top_pressure.hint")));
            rows.add(new DeviceColumn.Meter("bottom_pressure",pressureValue(vessel==null?null:vessel.bottomPressure()),p,tr("bottom_pressure.hint")));
            rows.add(meter("mass",value(state!=null?state.mass():empty?0:Double.NaN),"kg"));
        }else if(!pipe()&&data.kind()!=TopologyCompiler.Kind.FILTER){
            boolean side=data.kind()==TopologyCompiler.Kind.HEAT_SIDE;
            if(data.kind()!=TopologyCompiler.Kind.VOID)rows.add(meter(side?"inlet_temperature":"temperature",state==null?"—":temperatureValue(state.temperature()),t));
            String key=data.kind()==TopologyCompiler.Kind.PUMP||data.kind()==TopologyCompiler.Kind.COMPRESSOR?"suction_pressure":data.kind()==TopologyCompiler.Kind.VALVE||side?"inlet_pressure":"pressure";
            rows.add(meter(key,pressureValue(state==null?null:state.pressure()),p));
        }
        rows.add(new DeviceColumn.Meter(tank()?"net_flow":"throughput",value(displayedFlow()),flowUnit(),tr(tank()?"net_flow.hint":"throughput.hint")));
        if(pipe()){
            var connections=view.pipeInfo().connections();
            range(rows,"velocity",connections,com.wormzjl.icheme.runtime.fluid.FluidView.PipeConnection::velocityMetresPerSecond,DeviceMeters::value,"m/s",tr("velocity.hint"));
            range(rows,"gradient",connections,com.wormzjl.icheme.runtime.fluid.FluidView.PipeConnection::pressureDropPascalPerMetre,pressureUnit::number,p+"/m",tr("gradient.hint"));
        }
        if(data.kind()==TopologyCompiler.Kind.FILTER){
            var filter=view.filter();
            rows.add(meter("filter_load",value(filter==null?Double.NaN:filter.loading()*100),"%"));
            rows.add(meter("captured",value(filter==null?Double.NaN:filter.captured().massKg()),"kg"));
        }
        // A compressor reads its pressure ratio, the quantity its limit is set in, with the pressure change on its hover
        // (owner 2026-10-28, option A: its page fits the device screen's 338 px view at 2560x1440).
        boolean ratio=data.kind()==TopologyCompiler.Kind.COMPRESSOR&&view.devicePressureChange()!=null&&state!=null&&state.pressure()>0;
        if(ratio)rows.add(new DeviceColumn.Meter("pressure_ratio",value((state.pressure()+view.devicePressureChange())/state.pressure()),"",tr("pressure_ratio.hint",pressureValue(view.devicePressureChange())+" "+p)));
        else if(view.devicePressureChange()!=null)rows.add(meter("pressure_change",pressureValue(view.devicePressureChange()),p));
        // A driven casing's readings (W7, build plan section 3): a pump's head, its speed share, the shaft power it takes
        // and its efficiency (the fluid's power over the shaft's), from the view the bucket sent.
        var drive=view.drive();
        if(drive!=null&&!drive.driver().equals("STEAM_TURBINE")&&(data.kind()==TopologyCompiler.Kind.PUMP||data.kind()==TopologyCompiler.Kind.COMPRESSOR)){
            double density=state!=null&&state.volume()>0?state.mass()/state.volume():Double.NaN,rise=view.devicePressureChange()==null?Double.NaN:view.devicePressureChange();
            if(data.kind()==TopologyCompiler.Kind.PUMP)rows.add(new DeviceColumn.Meter("head",value(rise/(density*9.80665)),"m",tr("head.hint")));
            rows.add(meter("speed_share",value(100*drive.share()),"%"));
            rows.add(meter("shaft_power",value(drive.shaftPower()/1000),"kW"));
            double fluid=Math.abs(view.massFlow())/density*rise;
            rows.add(new DeviceColumn.Meter("efficiency",drive.shaftPower()>0&&fluid>0&&data.kind()==TopologyCompiler.Kind.PUMP?value(100*fluid/drive.shaftPower()):"—","%",tr("efficiency.hint")));
        }
        if(view.heat()!=null){var heat=view.heat();
            rows.add(meter("outlet_temperature",temperatureValue(heat.outletTemperature()),t));
            rows.add(new DeviceColumn.Meter("duty",value(Math.abs(heat.heatWatt())/1000),"kW",tr(heat.burners()!=null?"duty.fired":heat.heatWatt()<0?"duty.cooled":"duty.heated")));
            // The control itself (mode, set point, low limit) is on the Control tab; this column keeps what the actuator does.
            var control=data.heatControl();
            if(heat.bypass()!=null)rows.add(new DeviceColumn.Meter("bypass",value(heat.bypass()*100),"%",tr("bypass.hint")));
            if(heat.fans()!=null&&control!=null&&control.mode()!=HeatUnit.Mode.OFF)rows.add(new DeviceColumn.Meter("fans_speed",value(heat.fans()*100),"%",tr("fans_speed.hint")));
            if(heat.fanPowerWatt()!=null)rows.add(new DeviceColumn.Meter("fan_power",value(heat.fanPowerWatt()/1000),"kW",tr("fan_power.hint")));
            if(heat.setPoint()!=null)rows.add(meter("set_temperature",temperatureValue(heat.setPoint()),t));
            if(heat.maximumWatt()!=null)rows.add(new DeviceColumn.Meter("duty_available",value(heat.maximumWatt()/1000),"kW",tr("duty_available.hint")));
            // The burners (batch 2026-11-09-furnace-fuel-power): the fuel into the firebox, the heat it fires against the heat
            // asked for, the combustion-air fans' draw. The alarms stand on the status display.
            var fuel=heat.fuel();
            if(fuel!=null){
                rows.add(new DeviceColumn.Meter("fuel_flow",value(flowRateUnit.display(fuel.massFlow())),flowRateUnit.unit("kg"),tr("fuel_flow.hint",quantity(fuel.heatingValue()/1e6,"MJ/kg"))));
                rows.add(new DeviceColumn.Meter("fuel_fired",value(fuel.firedWatt()/1000),"kW",tr("fuel_fired.hint",quantity(fuel.askedWatt()/1000,"kW"))));
                rows.add(new DeviceColumn.Meter("burner_fans",value(fuel.fanWatt()/1000),"kW",tr("burner_fans.hint")));
            }
        }
        // A flare stack (batch 2026-11-09-flare-stack): the heat it releases against its rating, its gas's heating value and
        // flame temperature, and the most it passes of that gas. Its status stands on the display.
        var still=view.still();if(still!=null)stillMeters(rows,still);
        var flare=view.flare();
        if(flare!=null){
            rows.add(new DeviceColumn.Meter("flare_heat",value(flare.heat()/1e6),"MW",tr("flare_heat.hint",quantity(flare.rated()/1e6,"MW"))));
            rows.add(new DeviceColumn.Meter("flare_heating_value",value(flare.heatingValue()/1e6),"MJ/m³",tr("flare_heating_value.hint")));
            rows.add(new DeviceColumn.Meter("flare_flame",temperatureValue(flare.flameTemperature()),t,tr("flare_flame.hint",temperatureValue(com.wormzjl.icheme.science.fluid.network.Flare.FLAME_LIMIT)+" "+t)));
            rows.add(new DeviceColumn.Meter("flare_limit",value(flowRateUnit.display(flare.limit())),flowRateUnit.unit("kg"),tr("flare_limit.hint",quantity(flare.tip(),"m"))));
        }
        // A chimney (batch 2026-11-10-chimney): the gas and solids it releases, its exit velocity against its window, the smoke's
        // opacity and its stack's draft. Its status stands on the display.
        var chimney=view.chimney();
        if(chimney!=null){
            rows.add(new DeviceColumn.Meter("chimney_flow",value(flowRateUnit.display(chimney.massFlow())),flowRateUnit.unit("kg"),tr("chimney_flow.hint")));
            rows.add(new DeviceColumn.Meter("chimney_solids",value(chimney.solids()*3600),"kg/h",tr("chimney_solids.hint")));
            rows.add(new DeviceColumn.Meter("chimney_velocity",value(chimney.exitVelocity()),"m/s",tr("chimney_velocity.hint",quantity(com.wormzjl.icheme.science.fluid.network.Chimney.LOW_VELOCITY,"m/s"),quantity(com.wormzjl.icheme.science.fluid.network.Chimney.HIGH_VELOCITY,"m/s"),quantity(chimney.diameter(),"m"))));
            rows.add(new DeviceColumn.Meter("chimney_opacity",value(100*chimney.opacity()),"%",tr("chimney_opacity.hint")));
            rows.add(new DeviceColumn.Meter("chimney_draft",value(chimney.draft()/1000),"kPa",tr("chimney_draft.hint")));
        }
        // An instrument's main value on its meter, the other on hover: a flowmeter's mass flow (its volume flow), a level
        // gauge's share of its span (its height).
        for(var sensor:data.sensors()){
            // A turbine's speed pickup (W3) shows on its status line's shaft words; its meter comes with the W7 pages (reading A40).
            if(sensor.kind()==com.wormzjl.icheme.runtime.instrument.Sensor.Kind.SPEED||sensor.kind()==com.wormzjl.icheme.runtime.instrument.Sensor.Kind.ALARM||sensor.kind()==com.wormzjl.icheme.runtime.instrument.Sensor.Kind.ANALYSER)continue;
            String key=switch(sensor.kind()){case PRESSURE->"pressure_probe";case TEMPERATURE->"temperature_probe";case FLOW->"flowmeter";case LEVEL->sensor.mount().equals("level_boot")?"boot_level":"body_level";case SPEED,ALARM,ANALYSER->"";};
            var availability=sensor.availability();
            if(availability==com.wormzjl.icheme.runtime.instrument.Instrument.Availability.UNAVAILABLE){rows.add(new DeviceColumn.Meter(key,"—","",tr("reading.unavailable")));continue;}
            rows.add(switch(sensor.kind()){
                case PRESSURE->meter(key,pressureUnit.number(sensor.value()),p);
                case TEMPERATURE->new DeviceColumn.Meter(key,temperatureValue(sensor.value()),t,availability==com.wormzjl.icheme.runtime.instrument.Instrument.Availability.NO_FLOW?tr("reading.still"):null);
                case FLOW->new DeviceColumn.Meter(key,value(flowRateUnit.display(sensor.value())),flowRateUnit.unit("kg"),tr("reading.volume_flow",quantity(flowRateUnit.display(sensor.secondary()),flowRateUnit.unit("m³"))));
                case LEVEL->new DeviceColumn.Meter(key,value(sensor.secondary()),"%",tr(sensor.value()<0||sensor.value()>sensor.span()?"reading.height_overrange":"reading.height",quantity(sensor.value(),"m")));
                case SPEED,ALARM,ANALYSER->throw new IllegalStateException("A speed pickup, an alarm or an analyser has no meter");
            });
        }
        return List.copyOf(rows);
    }
    /** The still's readings: the pot's and the head's temperatures, the level, the heat, the firing it runs at, the flows, the condenser's share, the reflux. */
    private void stillMeters(List<DeviceColumn.Meter> rows,com.wormzjl.icheme.runtime.fluid.FluidView.Still s){
        String t=temperatureUnit.symbol();
        rows.add(meter("still_pot_temperature",s.potTemperature()==null?"—":temperatureValue(s.potTemperature()),t));
        rows.add(new DeviceColumn.Meter("still_head_temperature",s.headTemperature()==null?"—":temperatureValue(s.headTemperature()),t,tr("still_head_temperature.hint")));
        rows.add(new DeviceColumn.Meter("still_level",value(s.level()),"m",tr("still_level.hint",value(100*s.filled()))));
        double running=s.heldFiring()!=null?s.heldFiring():s.firing();
        rows.add(new DeviceColumn.Meter("still_heat",value(s.heat()/1000),"kW",tr("still_heat.hint",value(s.rating()/1000),value(100*running))));
        if(s.condenser()){
            rows.add(new DeviceColumn.Meter("still_distillate",value(flowRateUnit.display(s.distillate())),flowRateUnit.unit("kg"),tr("still_distillate.hint")));
            rows.add(new DeviceColumn.Meter("still_vent",value(flowRateUnit.display(s.vent())),flowRateUnit.unit("kg"),tr("still_vent.hint")));
            rows.add(new DeviceColumn.Meter("still_condensed",value(100*s.condensed()),"%",tr("still_condensed.hint")));
        }
        if(s.stages()>0&&s.condenser())rows.add(new DeviceColumn.Meter("still_reflux_run",s.effectiveReflux()==null?"—":value(s.effectiveReflux()),"",tr("still_reflux_run.hint")));
    }
    /** A pipe's reading over its connections: one meter where they read alike, else the lowest and the highest. */
    private static void range(List<DeviceColumn.Meter> rows,String key,List<com.wormzjl.icheme.runtime.fluid.FluidView.PipeConnection> connections,
            java.util.function.ToDoubleFunction<com.wormzjl.icheme.runtime.fluid.FluidView.PipeConnection> reading,java.util.function.DoubleFunction<String> shown,String unit,String hint){
        if(connections.isEmpty()){rows.add(new DeviceColumn.Meter(key,"—",unit,hint));return;}
        String low=shown.apply(connections.stream().mapToDouble(reading).min().orElse(0)),high=shown.apply(connections.stream().mapToDouble(reading).max().orElse(0));
        if(low.equals(high))rows.add(new DeviceColumn.Meter(key,low,unit,hint));
        else{rows.add(new DeviceColumn.Meter(key+"_lowest",low,unit,hint));rows.add(new DeviceColumn.Meter(key+"_highest",high,unit,hint));}
    }
    private double displayedFlow(){
        var view=data.view();
        if(view.pipeInfo()!=null){
            if(view.intervalSeconds()<=0)return Double.NaN;
            var stream=view.pipeInfo().contents();
            if(mass)return flowRateUnit.display(stream.massKg()/view.intervalSeconds());
            double moles=0;for(var phase:stream.phaseMoles())for(double amount:phase)moles+=amount;
            return flowRateUnit.display(moles/1000/view.intervalSeconds());
        }
        if(tank())return flowRateUnit.display(mass?view.outlets().stream().mapToDouble(com.wormzjl.icheme.runtime.fluid.FluidView.Outlet::massFlow).sum():view.outlets().stream().mapToDouble(com.wormzjl.icheme.runtime.fluid.FluidView.Outlet::moleFlow).sum()/1000);
        double flow=view.massFlow()*(data.kind()==TopologyCompiler.Kind.VOID?-1:1);
        if(mass||flow==0)return flowRateUnit.display(flow);
        if(view.state()==null)return Double.NaN;
        double moles=0;for(var phase:view.state().phaseMoles())for(double amount:phase)moles+=amount;
        // The bulk mass includes suspended solids, which do not contribute fluid moles.
        return view.state().mass()>0?flowRateUnit.display(flow*moles/view.state().mass()/1000):Double.NaN;
    }
    /** The Phases group: accepted passing material, stored vessel contents, configured generator feed, or the Overview's stream. */
    public DeviceColumn.Phases phases(){
        if(tower())return null;
        var view=data.view();var state=view.state();var info=view.pipeInfo();var port=selectedPort();
        // A heat unit's selected stream (the Overview's switch) sets the phase split shown.
        double[] volumes=port!=null?port.phaseVolumes():info!=null?info.contents().phaseVolumes():state!=null?state.phaseVolumes():new double[3];
        var solids=port!=null?SolidInventory.EMPTY:info!=null?info.contents().solids():state!=null?state.solids():SolidInventory.EMPTY;
        String hint=tr(generator()?"phase_ratio.generator_hint":tank()?"phase_ratio.tank_hint":"phase_ratio.hint");
        if(phaseBasis==PhaseRatioBasis.MOLAR)hint+="\n"+tr("phase_ratio.mole_hint");
        return new DeviceColumn.Phases(phaseBasis.key(),phaseBasis.amounts(volumes,amounts(),solids,data.components(),data.molecularWeights()),phaseBasis.excludesCustomSolids(solids),hint,aqueousWater(),dense);
    }
    public double[][] amounts(){
        var view=data.view();
        var port=selectedPort();if(port!=null)return port.phaseMoles();
        if(view.pipeInfo()!=null)return view.pipeInfo().contents().phaseMoles();
        return view.state()==null?new double[3][data.components().size()]:view.state().phaseMoles();
    }
    /** Whether the shown aqueous liquid is named water (plan N7, {@link DeviceColumn#aqueousIsWater}). */
    public boolean aqueousWater(){return DeviceColumn.aqueousIsWater(amounts()[1],data.components().indexOf("Water"));}
    /** A tank's Level group: its fill (NaN while unknown), and its contents of its capacity in litres. */
    public DeviceColumn.Level level(){
        if(!tank()||converter()!=null)return null;
        var view=data.view();var vessel=view.tank();var state=view.state();boolean empty=vessel!=null&&state==null;
        double capacity=vessel!=null?vessel.capacity():state==null?Double.NaN:state.capacity();
        return new DeviceColumn.Level(state!=null?state.fill():empty?0:Double.NaN,state!=null?number(state.volume()*1000):empty?number(0):"—",value(capacity*1000),state!=null&&dense>=0);
    }
    /**
     * A pump's Suction and Health groups (W10d, owner decision D37): NPSH available and required, the suction's gas and the
     * health left, from the view the bucket sent; null for a device that is no pump. CAVITATION, GAS and FAILED stay on the display.
     */
    public DeviceColumn.Suction suction(){
        var p=data.view().pump();if(p==null)return null;
        // One dense fluid (S5): its share is a density's, not gas. The meter reads the head share the pump keeps on it,
        // M(α, p) on the server's α at the suction pressure the page reads (the pump node's state).
        var state=data.view().state();
        boolean head=dense>=0&&p.gasShare()!=null&&state!=null&&state.pressure()>0;
        String gas=p.gasShare()==null?"—":value(100*(head?com.wormzjl.icheme.science.machine.GasEntrainment.headShare(p.gasShare(),state.pressure()):p.gasShare()));
        return new DeviceColumn.Suction(boxed(p.npshAvailable()),boxed(p.npshRequired()),gas,value(100*p.health()),p.lifePlaySeconds()==null?"—":value(p.lifePlaySeconds()/60),head);
    }
    private String boxed(Double v){return v==null?"—":value(v);}
    /**
     * A casing's or a turbine's Drive group (W7, owner decision D31, option A): the driver in one line, its state, Open
     * driver; {@code NO DRIVER} where the casing has none and stands closed. Null for a device with no shaft.
     */
    public DeviceColumn.Drive drive(){
        var d=data.view().drive();if(d==null)return null;
        if(d.driver().isEmpty())return new DeviceColumn.Drive(tr("no_driver"),null,false,false);
        String kind=tr("driver."+d.driver().toLowerCase(Locale.ROOT)),rating=number(d.ratedPower()/1000);
        String line=d.ratedPower()<=0?kind:tr("drive_line",kind,rating);
        return new DeviceColumn.Drive(line,tr("drive_state",d.status(),String.format(Locale.ROOT,"%.0f",d.speed())),true,d.fixed());
    }
    /** A fan unit's Power group (W7, reading A86): its draw, FE per tick and store; NO POWER or UNMETERED stay on the display. */
    public DeviceColumn.Power power(){
        var p=data.view().power();if(p==null)return null;
        return new DeviceColumn.Power(value(p.draw()/1000),String.format(Locale.ROOT,"%.0f",p.perTick()),p.store()==null?null:String.format(Locale.ROOT,"%.0f",100*p.store()));
    }
    /** Phase fractions from cached amounts only; custom solids have no molecular identity in the material model. */
    public enum PhaseRatioBasis {
        VOLUME,MOLAR,MASS;
        public PhaseRatioBasis next(){return switch(this){case VOLUME->MOLAR;case MOLAR->MASS;case MASS->VOLUME;};}
        public String key(){return switch(this){case VOLUME->"volume";case MOLAR->"mole";case MASS->"mass";};}
        public double[] amounts(double[] volumes,double[][] moles,SolidInventory solids,List<String> components,List<Double> weights) {
            if(volumes.length!=3||moles.length!=3||weights.size()!=components.size())throw new IllegalArgumentException("Invalid phase ratio axis");
            for(double weight:weights)if(!Double.isFinite(weight)||weight<=0)throw new IllegalArgumentException("Invalid molecular weight");
            var values=new double[4];
            for(int p=0;p<3;p++) {
                if(moles[p].length!=weights.size())throw new IllegalArgumentException("Invalid phase ratio axis");
                if(!Double.isFinite(volumes[p])||volumes[p]<0)throw new IllegalArgumentException("Invalid phase volume");
                for(double amount:moles[p])if(!Double.isFinite(amount)||amount<0)throw new IllegalArgumentException("Invalid phase amount");
                if(this==VOLUME)values[p]=volumes[p];
                else for(int c=0;c<weights.size();c++)values[p]+=moles[p][c]*(this==MASS?weights.get(c):1);
            }
            if(this==VOLUME)values[3]=solids.volume();
            else if(this==MASS)values[3]=solids.massKg();
            else for(var population:solids.populations()) {
                var crystal=population.material().crystal();if(crystal==null)continue;
                int component=components.indexOf(crystal.component());
                if(component<0)throw new IllegalArgumentException("Unknown crystal component");
                values[3]+=population.massKg()/weights.get(component);
            }
            for(double value:values)if(!Double.isFinite(value)||value<0)throw new IllegalArgumentException("Invalid phase total");
            return values;
        }
        public boolean excludesCustomSolids(SolidInventory solids){return this==MOLAR&&solids.populations().stream().anyMatch(p->p.material().crystal()==null);}
    }
}
