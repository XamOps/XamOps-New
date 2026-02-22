import xml.etree.ElementTree as ET
import glob

def find_dups(file):
    try:
        tree = ET.parse(file)
        root = tree.getroot()
        ns = {'mvn': 'http://maven.apache.org/POM/4.0.0'}
        
        deps = set()
        for dep in root.findall('.//mvn:dependencies/mvn:dependency', ns):
            group_elem = dep.find('mvn:groupId', ns)
            art_elem = dep.find('mvn:artifactId', ns)
            
            if group_elem is not None and art_elem is not None:
                group = group_elem.text
                art = art_elem.text
                key = f"{group}:{art}"
                if key in deps:
                    print(f"Duplicate dependency {key} in {file}")
                deps.add(key)
        
        # Check dependencyManagement
        deps_mgt = set()
        for dep in root.findall('.//mvn:dependencyManagement/mvn:dependencies/mvn:dependency', ns):
            group_elem = dep.find('mvn:groupId', ns)
            art_elem = dep.find('mvn:artifactId', ns)
            
            if group_elem is not None and art_elem is not None:
                group = group_elem.text
                art = art_elem.text
                key = f"{group}:{art}"
                if key in deps_mgt:
                    print(f"Duplicate managed dependency {key} in {file}")
                deps_mgt.add(key)
    
    except Exception as e:
        print(f"Error parsing {file}: {e}")

for pom in glob.glob('**/pom.xml', recursive=True):
    if 'node_modules' not in pom:
        find_dups(pom)
