package com.wormzjl.icheme.science.equipment;

/** Saved by name. Both metallic cable enclosures use the same fixed outside diameter. */
public enum CableMedium {
    DATA(1/8.0,"data_cable",0xe5ca70), POWER(1/8.0,"power_cable",0xd89455);
    private final double outside;
    private final String texture;
    private final int colour;
    CableMedium(double outside,String texture,int colour){this.outside=outside;this.texture=texture;this.colour=colour;}
    public double outside(){return outside;}
    public String texture(){return texture;}
    public int colour(){return colour;}
}
