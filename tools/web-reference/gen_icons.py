import re, glob, sys, os
import xml.etree.ElementTree as ET
SRC=os.environ.get('WEB_SRC','/home/user/naqi-ice-tamimi/src')
names=set()
for f in glob.glob(SRC+'/**/*.tsx', recursive=True):
    if '/components/ui/' in f: continue
    s=open(f,encoding='utf-8').read()
    for m in re.finditer(r'import\s*\{([^}]+)\}\s*from\s*"lucide-react"', s, re.S):
        for n in m.group(1).split(','):
            n=n.strip().split(' as ')[0].strip()
            if n: names.add(n)
names |= {'X','Check','ChevronDown','Calendar','Trash2','Pencil','Download','FileSpreadsheet','MapPinned','Store','Package','KeyRound','Building2','Navigation','Image','Info','QrCode','Flashlight','Factory','Boxes'}
def kebab(n):
    n=re.sub(r'Icon$','',n)
    s=re.sub(r'([a-z])([A-Z0-9])', r'\1-\2', n)
    s=re.sub(r'([0-9])([A-Z])', r'\1-\2', s)
    return s.lower()
ALIAS={'alert-triangle':'triangle-alert','bar-chart3':'chart-column','bar-chart-3':'chart-column','file-x2':'file-x-2','file-x-2':'file-x-corner','share2':'share-2','trash2':'trash-2','building2':'building-2','home':'house','more-horizontal':'ellipsis','edit':'square-pen','check-circle2':'circle-check','check-circle-2':'circle-check','alert-circle':'circle-alert','x-circle':'circle-x','loader2':'loader-circle','loader-2':'loader-circle'}
ICONS='node_modules/lucide-static/icons/'
def f(v): 
    v=float(v); return ('%g'%v)
def shape(el):
    t=el.tag.split('}')[1]; a=el.attrib
    if t=='path': return a['d']
    if t=='circle':
        cx,cy,r=float(a['cx']),float(a['cy']),float(a['r'])
        return f"M{f(cx-r)} {f(cy)}a{f(r)} {f(r)} 0 1 0 {f(2*r)} 0a{f(r)} {f(r)} 0 1 0 {f(-2*r)} 0"
    if t=='ellipse':
        cx,cy,rx,ry=float(a['cx']),float(a['cy']),float(a['rx']),float(a['ry'])
        return f"M{f(cx-rx)} {f(cy)}a{f(rx)} {f(ry)} 0 1 0 {f(2*rx)} 0a{f(rx)} {f(ry)} 0 1 0 {f(-2*rx)} 0"
    if t=='line': return f"M{a['x1']} {a['y1']}L{a['x2']} {a['y2']}"
    if t in('polyline','polygon'):
        pts=re.split(r'[\s,]+', a['points'].strip()); d='M'+' L'.join(f"{pts[i]} {pts[i+1]}" for i in range(0,len(pts),2))
        return d+('Z' if t=='polygon' else '')
    if t=='rect':
        x,y,w,h=float(a.get('x',0)),float(a.get('y',0)),float(a['width']),float(a['height'])
        rx=float(a.get('rx',a.get('ry',0))); ry=float(a.get('ry',rx))
        if rx==0: return f"M{f(x)} {f(y)}h{f(w)}v{f(h)}h{f(-w)}Z"
        return (f"M{f(x+rx)} {f(y)}h{f(w-2*rx)}a{f(rx)} {f(ry)} 0 0 1 {f(rx)} {f(ry)}v{f(h-2*ry)}a{f(rx)} {f(ry)} 0 0 1 {f(-rx)} {f(ry)}"
                f"h{f(-(w-2*rx))}a{f(rx)} {f(ry)} 0 0 1 {f(-rx)} {f(-ry)}v{f(-(h-2*ry))}a{f(rx)} {f(ry)} 0 0 1 {f(rx)} {f(-ry)}Z")
    raise Exception(t)
out=['package com.tamimi.naqiice.ui','','import androidx.compose.ui.graphics.SolidColor','import androidx.compose.ui.graphics.Color','import androidx.compose.ui.graphics.StrokeCap','import androidx.compose.ui.graphics.StrokeJoin','import androidx.compose.ui.graphics.vector.ImageVector','import androidx.compose.ui.graphics.vector.PathParser','import androidx.compose.ui.unit.dp','',
'// Generated from lucide-static 0.575.0 (ISC licence) — the icon set the web app uses.','// Regenerate rather than edit by hand.','',
'private fun lucide(name: String, vararg paths: String): ImageVector {','    val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)',
'    for (d in paths) builder.addPath(PathParser().parsePathString(d).toNodes(), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)',
'    return builder.build()','}','','object Lucide {']
missing=[]
for n in sorted(names):
    k=kebab(n); p=ICONS+k+'.svg'
    if not os.path.exists(p): p=ICONS+ALIAS.get(k,k)+'.svg'
    if not os.path.exists(p): missing.append(n); continue
    root=ET.parse(p).getroot()
    paths=[shape(e) for e in root if e.tag.split('}')[1]!='title']
    name=re.sub(r'Icon$','',n)
    out.append(f'    val {name}: ImageVector by lazy {{ lucide("{name}", '+', '.join('"'+d.replace('\n',' ')+'"' for d in paths)+') }')
out.append('}'); out.append('')
open(os.path.join(os.path.dirname(os.path.abspath(__file__)),'..','..','app/src/main/java/com/tamimi/naqiice/ui/Lucide.kt'),'w').write('\n'.join(out))
print(len(names)-len(missing),'icons; missing:',missing)
