import json
from pathlib import Path
root=Path('src/main/resources/assets/icheme/lang')
en=json.loads((root/'en_us.json').read_text(encoding='utf-8'))
keys=['gui.icheme.equipment_constructor.motor.rating.hint','gui.icheme.equipment_constructor.generator.rating.hint','gui.icheme.data.removed','gui.icheme.power.removed','message.icheme.conduit.route','gui.icheme.control.sensor_location','gui.icheme.control.sensor_host_location','gui.icheme.control.sensor_equipment','gui.icheme.control.location_unknown','gui.icheme.equipment_constructor.rated_speed','gui.icheme.equipment_constructor.rated_speed.hint']
values={
'zh_cn': ['额定轴功率、设计转速（rpm）和机座直径','额定功率、设计转速（rpm）和机座直径','数据导管已拆除','电力导管已拆除','%s · %s · %s m · %s 个弯头 · %s m 套管 · %s：%s','传感器方块：%s，%s，%s','设备方块：%s，%s，%s','设备：%s','位置不可用','额定转速','额定设计工况下的轴转速，单位为 rpm。运行转速由相连的驱动机决定。'],
'fr_fr': ["Puissance nominale à l'arbre, vitesse de conception en tr/min et diamètre de la carcasse","Puissance nominale, vitesse de conception en tr/min et diamètre de la carcasse",'Conduit de données retiré','Conduit électrique retiré','%s · %s · %s m · %s coudes · %s m de gaine · %s : %s','Bloc du capteur : %s, %s, %s',"Bloc de l'équipement : %s, %s, %s",'Équipement : %s','Position indisponible','Vitesse nominale',"Vitesse de l'arbre au point de conception nominal, en tr/min. L'entraînement raccordé détermine la vitesse de fonctionnement."],
'es_es': ['Potencia nominal en el eje, velocidad de diseño en rpm y diámetro de la carcasa','Potencia nominal, velocidad de diseño en rpm y diámetro de la carcasa','Conducto de datos retirado','Conducto eléctrico retirado','%s · %s · %s m · %s codos · %s m de envolvente · %s: %s','Bloque del sensor: %s, %s, %s','Bloque del equipo: %s, %s, %s','Equipo: %s','Ubicación no disponible','Velocidad nominal','Velocidad del eje en el punto nominal de diseño, en rpm. El accionamiento conectado determina la velocidad de funcionamiento.'],
'ja_jp': ['定格軸出力、設計回転速度（rpm）、フレーム直径','定格出力、設計回転速度（rpm）、フレーム直径','データ導管を撤去しました','電力導管を撤去しました','%s · %s · %s m · エルボー %s 個 · 保護管 %s m · %s: %s','センサーブロック：%s、%s、%s','設備ブロック：%s、%s、%s','設備：%s','位置情報なし','定格回転速度','定格設計点での軸回転速度（rpm）。運転時の回転速度は接続された駆動機が決定します。']}
for locale,lines in values.items():
 p=root/(locale+'.json');d=json.loads(p.read_text(encoding='utf-8'));assert len(keys)==len(lines)
 d.update(zip(keys,lines));assert set(d)==set(en)
 p.write_text(json.dumps({k:d[k] for k in en},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
 print(locale,len(d),'ordered keys; nine additions and two rewritten hints')
