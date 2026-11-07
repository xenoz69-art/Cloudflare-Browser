from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path('.')
for xml_path in Path('app/src/main').rglob('*.xml'):
    ET.parse(xml_path)

html_path = Path('app/src/main/assets/browser/index.html')
js_path = Path('app/src/main/assets/browser/app.js')
html, js = html_path.read_text(), js_path.read_text()
ids = set(re.findall(r'id="([^"]+)"', html))
refs = set(re.findall(r"\$\('#([\w-]+)(?:\s|')", js))
assert refs <= ids, f'Missing HTML ids: {sorted(refs - ids)}'

java_sources = [path for source_set in ('main', 'test', 'androidTest')
                for path in Path(f'app/src/{source_set}/java').rglob('*.java')]
for java_path in java_sources:
    source = java_path.read_text()
    stripped = re.sub(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'', 'L', source)
    stripped = re.sub(r'/\*.*?\*/|//[^\n]*', '', stripped, flags=re.S)
    for opening, closing in [('(', ')'), ('{', '}'), ('[', ']')]:
        depth = 0
        for char in stripped:
            if char == opening:
                depth += 1
            elif char == closing:
                depth -= 1
                assert depth >= 0, f'{java_path}: unmatched {closing}'
        assert depth == 0, f'{java_path}: unmatched {opening}'

print(f'Project structure checks passed: Android XML, {len(ids)} UI IDs, {len(refs)} JS references, Java delimiters.')
