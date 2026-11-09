package com.wormzjl.icheme.science.equipment;
public class Chan { public static void main(String[] a){ for (var m : Mesh.Material.values()) if (m.channel()>=0) System.out.print(m+"="+m.channel()+" "); System.out.println(); } }
