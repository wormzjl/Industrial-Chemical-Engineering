package com.wormzjl.icheme.client.fx.silhouette;

/**
 * One silhouette box in metres relative to its structure's origin (batch 2026-11-10-custom-shader-system, W10, plan
 * section 4.7): lower and upper corners, its colour as 0xRRGGBB and whether DH shades it as stone (concrete, brick) or as
 * metal (steel, paint, copper).
 */
public record SilhouetteBox(double x0,double y0,double z0,double x1,double y1,double z1,int rgb,boolean stone) {
    public SilhouetteBox{
        if(!(x1>x0&&y1>y0&&z1>z0))throw new IllegalArgumentException("A silhouette box has a positive size");
        if((rgb&~0xFFFFFF)!=0)throw new IllegalArgumentException("A silhouette colour is 0xRRGGBB");
    }
    /** Whether the point (x, y, z) lies inside, faces included. */
    public boolean contains(double x,double y,double z){return x>=x0&&x<=x1&&y>=y0&&y<=y1&&z>=z0&&z<=z1;}
    public double volume(){return (x1-x0)*(y1-y0)*(z1-z0);}
}
