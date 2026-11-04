#!/usr/bin/env python3
"""Rebuild the bundled subset from Material Icon Theme f368264282ef14ee7feb012fd54e825181eff9c3.

Usage: python3 scripts/icons/import-material-icons.py /path/to/vscode-material-icon-theme
Source: https://github.com/material-extensions/vscode-material-icon-theme (MIT).
Only the SVG subset used by FileTypeIcon.kt is converted; no build-time dependency.
"""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ANDROID = 'http://schemas.android.com/apk/res/android'
AAPT = 'http://schemas.android.com/aapt'
ET.register_namespace('android', ANDROID)
ET.register_namespace('aapt', AAPT)
ROOT = Path(__file__).resolve().parents[2]
SOURCE = Path(sys.argv[1])
OUT = ROOT / 'android/app/src/main/res/drawable'
A = lambda key: f'{{{ANDROID}}}{key}'
NUM = re.compile(r'[-+]?(?:\d*\.\d+|\d+\.?\d*)(?:[eE][-+]?\d+)?')
ARITY = dict(zip('MLHVCSQTA', (2, 2, 1, 1, 6, 4, 4, 2, 7)))


def path_data(data):
    # SVG allows adjacent arc flags ("01"). Android PathParser treats them as
    # one number. Parse flags individually and emit every argument with spaces.
    pos, command, result = 0, None, []
    while pos < len(data):
        if data[pos] in ' ,\t\r\n':
            pos += 1
            continue
        if data[pos].isalpha():
            command = data[pos]
            pos += 1
            if command.upper() == 'Z':
                result.append(command)
                command = None
                continue
        assert command and command.upper() in ARITY, data[pos:]
        values = []
        for index in range(ARITY[command.upper()]):
            while pos < len(data) and data[pos] in ' ,\t\r\n':
                pos += 1
            if command.upper() == 'A' and index in (3, 4):
                assert data[pos] in '01', data[pos:]
                values.append(data[pos])
                pos += 1
            else:
                match = NUM.match(data, pos)
                assert match, data[pos:]
                values.append(match[0])
                pos = match.end()
        result.append(command + ' ' + ' '.join(values))
        if command.upper() == 'M':
            command = 'l' if command.islower() else 'L'
    return ' '.join(result)


def color(value):
    if value == 'none':
        return '#00000000'
    if value.startswith('#') and len(value) == 4:
        return '#' + ''.join(c * 2 for c in value[1:])
    return value


def convert(svg, target):
    _, _, width, height = map(float, svg.attrib['viewBox'].split())
    vector = ET.Element('vector', {A('width'): '24dp', A('height'): '24dp',
                                  A('viewportWidth'): str(width), A('viewportHeight'): str(height)})
    gradients = {node.attrib['id']: node for node in svg.iter() if node.tag.endswith('}linearGradient')}

    def visit(node, inherited):
        tag = node.tag.split('}')[-1]
        if tag == 'defs':
            return
        attributes = dict(inherited, **node.attrib)
        assert 'transform' not in attributes, 'Unsupported SVG transform'
        if tag in ('svg', 'g'):
            for child in node:
                visit(child, attributes)
            return
        if tag == 'circle':
            x, y, r = (float(attributes[k]) for k in ('cx', 'cy', 'r'))
            data = f'M {x-r} {y} a {r} {r} 0 1 0 {r*2} 0 a {r} {r} 0 1 0 {-r*2} 0'
        elif tag == 'rect':
            x, y = float(attributes.get('x', 0)), float(attributes.get('y', 0))
            w, h = float(attributes['width']), float(attributes['height'])
            rx = min(float(attributes.get('rx', attributes.get('ry', 0))), w / 2)
            ry = min(float(attributes.get('ry', attributes.get('rx', 0))), h / 2)
            if rx and ry:
                data = (f'M {x+rx} {y} H {x+w-rx} A {rx} {ry} 0 0 1 {x+w} {y+ry} '
                        f'V {y+h-ry} A {rx} {ry} 0 0 1 {x+w-rx} {y+h} '
                        f'H {x+rx} A {rx} {ry} 0 0 1 {x} {y+h-ry} '
                        f'V {y+ry} A {rx} {ry} 0 0 1 {x+rx} {y} Z')
            else:
                data = f'M {x} {y} h {w} v {h} h {-w} Z'
        else:
            assert tag == 'path', tag
            data = attributes['d']
        path = ET.SubElement(vector, 'path', {A('pathData'): path_data(data)})
        for source, dest in [('fill', 'fillColor'), ('stroke', 'strokeColor'),
                             ('stroke-width', 'strokeWidth'), ('fill-rule', 'fillType'),
                             ('stroke-linecap', 'strokeLineCap'), ('stroke-linejoin', 'strokeLineJoin'),
                             ('fill-opacity', 'fillAlpha'), ('stroke-opacity', 'strokeAlpha')]:
            value = attributes.get(source, '#000000' if source == 'fill' else None)
            if value is None:
                continue
            if value.startswith('url('):
                gradient = gradients[value[5:-1]]
                assert gradient.attrib.get('gradientUnits') == 'userSpaceOnUse'
                transform = gradient.attrib.get('gradientTransform', '')
                params = re.fullmatch(r'translate\(([-.\d]+) ([-.\d]+)\)scale\(([-.\d]+)\)', transform)
                assert not transform or params, transform
                tx, ty, scale = map(float, params.groups()) if params else (0, 0, 1)
                attr = ET.SubElement(path, f'{{{AAPT}}}attr', {'name': 'android:fillColor'})
                coords = {A('type'): 'linear'}
                for axis, android_axis, translation in [('x', 'X', tx), ('y', 'Y', ty)]:
                    for index, edge in [('1', 'start'), ('2', 'end')]:
                        coords[A(edge + android_axis)] = str(float(gradient.attrib[axis + index]) * scale + translation)
                output = ET.SubElement(attr, 'gradient', coords)
                for stop in gradient:
                    ET.SubElement(output, 'item', {A('offset'): stop.attrib['offset'], A('color'): color(stop.attrib['stop-color'])})
            else:
                path.set(A(dest), 'evenOdd' if value == 'evenodd' else color(value))
    visit(svg, {})
    ET.indent(vector)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text('<?xml version="1.0" encoding="utf-8"?>\n<!-- Material Icon Theme, MIT; see assets/licenses/material-icon-theme.txt. -->\n' + ET.tostring(vector, encoding='unicode') + '\n')


# The default file/folder artwork is generated by upstream, rather than checked
# into icons/. Read those exact paths and its default color from the pinned tree.
folder_source = (SOURCE / 'src/core/generator/folderGenerator.ts').read_text()
file_source = (SOURCE / 'src/core/generator/fileGenerator.ts').read_text()
folder_closed = re.search(r"const folderIcon =\s*'([^']+)'", folder_source)[1]
folder_open = re.search(r"const folderIconOpen =\s*'([^']+)'", folder_source)[1]
file_path = re.search(r"const fileIcon =\s*'([^']+)'", file_source)[1]
icons = set(re.findall(r'R.drawable.file_type_(\w+)', (ROOT / 'android/app/src/main/kotlin/io/github/shiroel/aries/ui/FileTypeIcon.kt').read_text()))
icons = {icon for icon in icons if not icon.endswith('_light')}
for resource in sorted(icons):
    name = resource.replace('_', '-')
    # Upstream uses react_ts.svg for TSX.
    if name == 'react-ts':
        name = 'react_ts'
    if name in ('file', 'folder', 'folder-open'):
        data = {'file': file_path, 'folder': folder_closed, 'folder-open': folder_open}[name]
        svg = ET.fromstring(f'<svg viewBox="0 0 16 16"><path fill="#90a4ae" d="{data}"/></svg>')
    else:
        base = name.removesuffix('-open')
        svg = ET.parse(SOURCE / f'icons/{base}.svg').getroot()
        if name.endswith('-open'):
            folder = next(node for node in svg.iter() if node.attrib.get('id') == 'folder')
            folder.set('d', folder_open)
    convert(svg, OUT / f'file_type_{resource}.xml')
    # Retain upstream's light palette where it offers one.
    light = SOURCE / f'icons/{name}_light.svg'
    if light.exists():
        convert(ET.parse(light).getroot(), OUT / f'file_type_{resource}_light.xml')
license_path = ROOT / 'android/app/src/main/assets/licenses/material-icon-theme.txt'
license_path.write_text('Material Icon Theme\nhttps://github.com/material-extensions/vscode-material-icon-theme\nSource commit: f368264282ef14ee7feb012fd54e825181eff9c3\nSVGs converted to Android vector drawables; folder-open variants use upstream generator paths.\n\n' + (SOURCE / 'LICENSE').read_text())
print(f'Converted {len(icons)} icons (plus available light variants).')
