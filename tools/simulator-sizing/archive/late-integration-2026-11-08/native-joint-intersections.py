from pathlib import Path
p=Path('src/main/java/com/wormzjl/icheme/science/equipment/InstrumentWiring.java');s=p.read_text(encoding='utf-8');old='''for(var polygon:LineGeometry.conduit(List.of(start,foot),.020))hardware.include(material(polygon,Material.INSTRUMENT_STEEL));
            }catch(IllegalArgumentException ex){sources.add(p.tap());failures.add(p.id()+": "+ex.getMessage());}'''
new='''for(var polygon:LineGeometry.conduit(List.of(start,foot),.020))hardware.include(material(polygon,Material.INSTRUMENT_STEEL));
            }catch(IllegalArgumentException ex){
                // A very small head can overhang its shell. Bring the protected lead in along horizontal axes,
                // then descend once onto the actual tap surface; never invent a vertical foot outside the vessel.
                if(p.top())try{var footWanted=p.tap();foot=surface.ray(1,footWanted,1,PROCESS);var a=start.clone();a[0]=foot[0];var b=a.clone();b[2]=foot[2];for(var polygon:LineGeometry.conduit(List.of(start,a,b,foot),.020))hardware.include(material(polygon,Material.INSTRUMENT_STEEL));sources.add(foot);}catch(IllegalArgumentException missing){sources.add(p.tap());failures.add(p.id()+": "+missing.getMessage());}
                else {sources.add(p.tap());failures.add(p.id()+": "+ex.getMessage());}
            }'''
assert old in s;s=s.replace(old,new);p.write_text(s,encoding='utf-8')
p=Path('src/main/java/com/wormzjl/icheme/science/equipment/SurfaceJoints.java');s=p.read_text(encoding='utf-8');at=s.index('\n    }\n    private static boolean nearBox',s.index('SurfaceJoints(List'))
s=s[:at]+'''
        // Native struts can intersect the middle of a curved facet without sharing a vertex.
        // Split those exact edge/face crossings too, rather than loosening the joint-gap tolerance.
        for(var owner:faces)for(int e=0;e<3;e++){
            int a=owner.nodes[e],b=owner.nodes[(e+1)%3];var start=points.get(a);var finish=points.get(b);var delta=sub(finish,start);
            for(var face:faces){if(face==owner)continue;boolean near=true;for(int k=0;k<3;k++)if(Math.max(start[k],finish[k])<Math.min(face.a[k],Math.min(face.b[k],face.c[k]))-1e-8||Math.min(start[k],finish[k])>Math.max(face.a[k],Math.max(face.b[k],face.c[k]))+1e-8){near=false;break;}if(!near)continue;
                var n=cross(sub(face.b,face.a),sub(face.c,face.a));double den=dot(n,delta);if(Math.abs(den)<1e-12)continue;double amount=dot(n,sub(face.a,start))/den;if(amount<1e-7||amount>1-1e-7)continue;var q=plus(start,scale(delta,amount));if(SurfaceSection.distance(q,nearest(q,face))>1e-7)continue;
                int at=node(q);link(a,at,owner.material);link(at,b,owner.material);for(int target:face.nodes)link(at,target,face.material);
            }
        }
''' +s[at:];p.write_text(s,encoding='utf-8')
