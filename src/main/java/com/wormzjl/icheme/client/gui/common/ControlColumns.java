package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.*;
import java.util.*;
import java.util.function.BiFunction;

/** The controller and DCS share one operator form, also used by the offline renderer. */
public final class ControlColumns {
    private ControlColumns(){}
    public record Choice(String key,String label,boolean selected){}
    public enum Variable { UNKNOWN, LEVEL, PRESSURE, FLOW, TEMPERATURE, SPEED }
    public record View(boolean enabled,String unit,String secondUnit,boolean secondary,String input,String value,String output,String status,boolean outlet,Variable variable){}
    public static List<Column.Row> navigation(int page,int pages,String key,BiFunction<String,Object[],String> text){
        if(pages<=1)return List.of();
        return List.of(new Column.Line((page+1)+" / "+pages,Palette.TEXT),new Column.Buttons(List.of(
                new Column.Button(key+":prev",text.apply("control.previous",new Object[0]),page>0,null,false,false),
                new Column.Button(key+":next",text.apply("control.next",new Object[0]),page+1<pages,null,false,false))));
    }
    private static Column.Button button(String key,String label,boolean selected){return new Column.Button(key,label,true,null,selected,false);}
    public static List<Column.Row> controllers(List<Choice> choices,BiFunction<String,Object[],String> text){
        var rows=new ArrayList<Column.Row>();for(var choice:choices)rows.add(new Column.Choice(choice.label(),null,choice.selected(),choice.key()));
        if(rows.isEmpty())rows.add(new Column.Paragraph(text.apply("control.no_controllers",new Object[0]),Palette.TEXT));
        return List.of(new Column.Group(text.apply("control.controllers",new Object[0]),null,rows));
    }
    public static List<Column.Row> choices(String title,List<Choice> choices,BiFunction<String,Object[],String> text){
        var rows=new ArrayList<Column.Row>();for(var choice:choices)rows.add(new Column.Choice(choice.label(),null,choice.selected(),choice.key()));
        if(rows.isEmpty())rows.add(new Column.Paragraph(text.apply("control.no_devices",new Object[0]),Palette.TEXT));
        rows.add(new Column.Buttons(List.of(button("back",text.apply("control.back",new Object[0]),false))));return List.of(new Column.Group(title,null,rows));
    }
    public static List<Column.Row> loop(View v,BiFunction<String,Object[],String> text){
        java.util.function.Function<String,String> t=k->text.apply("control."+k,new Object[0]);
        var input=new ArrayList<Column.Row>();input.add(new Column.Buttons(List.of(button("input",t.apply("choose_input"),false))));input.add(new Column.Paragraph(v.input(),Palette.TEXT));input.add(new Column.Reading("pv",t.apply("reading"),v.value(),v.secondary()?v.secondUnit():v.unit(),null));
        if(!v.secondUnit().isEmpty())input.add(new Column.Buttons(List.of(new Column.Button("primary",v.unit(),true,null,!v.secondary(),true),new Column.Button("secondary",v.secondUnit(),true,null,v.secondary(),true))));
        var settings=new Column.Group(t.apply("settings"),null,List.of(
                new Column.Line(t.apply("name"),Palette.TEXT),new Column.Wide("name",t.apply("name")),
                new Column.Buttons(List.of(button("off",t.apply("off"),!v.enabled()),button("on",t.apply("on"),v.enabled()))),
                new Column.Field("target",t.apply("target"),v.secondary()?v.secondUnit():v.unit(),v.variable()==Variable.LEVEL?t.apply("level_target_hint"):null)));
        var output=new Column.Group(t.apply("output"),null,List.of(
                new Column.Buttons(List.of(button("output",t.apply("choose_output"),false))),new Column.Paragraph(v.output(),Palette.TEXT)));
        var rows=new ArrayList<Column.Row>(List.of(settings,new Column.Group(t.apply("input"),null,input),output));
        if(v.variable()==Variable.LEVEL||v.variable()==Variable.PRESSURE)rows.add(new Column.Group(t.apply("action"),t.apply("valve_location_hint"),List.of(
                new Column.Buttons(List.of(button("inlet",t.apply("inlet"),!v.outlet()),button("outlet",t.apply("outlet"),v.outlet()))),
                new Column.Paragraph(t.apply(v.variable()==Variable.LEVEL?(v.outlet()?"level_outlet":"level_inlet"):"pressure_outlet"),Palette.TEXT))));
        rows.add(new Column.Group(t.apply("status"),null,List.of(new Column.Paragraph(v.status(),Palette.TEXT))));
        return List.copyOf(rows);
    }
}
