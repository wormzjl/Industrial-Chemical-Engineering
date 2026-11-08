from pathlib import Path
p=Path('tmp/process-control/ControlAssetRender.java');s=p.read_text(encoding='utf-8')
s=s.replace('static void mesh(List<Quad> quads,com.wormzjl.icheme.science.equipment.Mesh.Polygon q,int colour){var v=', 'static void mesh(List<Quad> quads,com.wormzjl.icheme.science.equipment.Mesh.Polygon q,int colour)throws Exception {var v=')
s=s.replace('static void mesh(List<Quad> quads,com.wormzjl.icheme.science.equipment.Mesh.Polygon q){','static void mesh(List<Quad> quads,com.wormzjl.icheme.science.equipment.Mesh.Polygon q)throws Exception {')
s=s.replace('quads.add(new Quad(p,new double[4][2],new double[]{ns[0],ns[1],ns[2]},t));','var uv=new double[4][2];if(colour==0x376674){t=ImageIO.read(Path.of("src/main/resources/assets/icheme/textures/block/data_cable.png").toFile());var coordinates=q.uvs();for(int i=0;i<4;i++){uv[i][0]=coordinates[corners[i]*2];uv[i][1]=coordinates[corners[i]*2+1];}}quads.add(new Quad(p,uv,new double[]{ns[0],ns[1],ns[2]},t));')
p.write_text(s,encoding='utf-8')
