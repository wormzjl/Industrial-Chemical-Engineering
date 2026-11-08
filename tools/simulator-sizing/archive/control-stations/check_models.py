import itertools,json,pathlib,re
root=pathlib.Path.cwd()
for model,source in [('equipment_constructor','EquipmentConstructorBlock'),('process_simulator','ProcessSimulatorBlock')]:
    data=json.loads((root/f'src/main/resources/assets/icheme/models/block/{model}.json').read_text())
    java=(root/f'src/main/java/com/wormzjl/icheme/world/level/block/{source}.java').read_text()
    raw=java.split('double[][] parts = {',1)[1].split('};',1)[0]
    parts=[[float(x) for x in r.split(',')] for r in re.findall(r'\{([^{}]+)\}',raw)]
    textures=data['textures']
    for name,texture in textures.items():
        assert (root/'src/main/resources/assets/icheme/textures'/ (texture.split(':',1)[1]+'.png')).is_file(),(name,texture)
    samples=0
    for element in data['elements']:
        lo,hi=element['from'],element['to']
        assert all(0<=a<b<=16 for a,b in zip(lo,hi)),element['name']
        for xyz in itertools.product(*[(a,(a+b)/2,b) for a,b in zip(lo,hi)]):
            assert any(all(p[k]-1e-8<=xyz[k]<=p[k+3]+1e-8 for k in range(3)) for p in parts),(model,element['name'],xyz)
            samples+=1
        for face in element['faces'].values():
            assert face['texture'][1:] in textures
            assert all(0<=v<=16 for v in face['uv'])
    variants=json.loads((root/f'src/main/resources/assets/icheme/blockstates/{model}.json').read_text())['variants']
    assert [variants[f'facing={d}'].get('y',0) for d in ['north','east','south','west']]==[0,90,180,270]
    print(f'{model}: {len(data["elements"])} elements, {samples} collision samples covered, all textures/UVs and four headings valid')
