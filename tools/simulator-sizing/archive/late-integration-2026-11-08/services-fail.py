import xml.etree.ElementTree as E
p='build/test-results/equipmentGates/TEST-com.wormzjl.icheme.science.equipment.EquipmentServicesTest.xml'
for t in E.parse(p).getroot().findall('testcase'):
 if t.find('failure') is not None:print(t.get('name'),t.find('failure').get('message'))
