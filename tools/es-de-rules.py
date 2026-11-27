# Turns ES-DE's Android launch rules (MIT licensed) into WalkThor's emulator table.
# Usage: python3 tools/es-de-rules.py <path to an ES-DE checkout>
import xml.etree.ElementTree as ET, re, sys
import os
ES=sys.argv[1]
WT=os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
A=ES+'/resources/systems/android/'
norm=lambda s: re.sub(r'[\s_\-]','',s.lower())
# Emulators: name -> entries
emus={}
for e in ET.parse(A+'es_find_rules.xml').getroot().findall('emulator'):
    ents=[x.text.strip() for r in e.findall('rule') if r.get('type')=='androidpackage' for x in r.findall('entry')]
    out=[]
    for en in ents:
        pkg,act=en.split('/',1)
        if act.startswith('.'): act=pkg+act
        out.append(pkg+'/'+act)
    emus[e.get('name')]=out
# ScreenScraper ids by platform name
pid=open(ES+'/es-app/src/PlatformId.cpp').read()
names=re.findall(r'^\s*"([^"]+)",',pid.split('platformNames')[1],re.M)
h=open(ES+'/es-app/src/PlatformId.h').read()
enum=re.findall(r'^\s*([A-Z][A-Z0-9_]*)\s*(?:=\s*0)?,',h.split('enum PlatformId')[1].split('};')[0],re.M)
assert enum[0]=='PLATFORM_UNKNOWN', enum[:3]
assert len(names)==len(enum),(len(names),len(enum)); plat=dict(zip(names,enum))
ss=dict(re.findall(r'\{\s*([A-Z][A-Z0-9_]*)\s*,\s*(\d+)\s*\}',open(ES+'/es-app/src/scrapers/ScreenScraper.cpp').read()))
# WalkThor's own systems: folder -> key
roms=open(WT+'/app/src/main/java/com/nerfherder/walkthor/data/Roms.kt').read()
wt={}
for m in re.finditer(r'GameSystem\(\s*"([^"]+)",\s*"[^"]*",\s*-?\d+,\s*setOf\(([^)]*)\)',roms):
    key=m.group(1); wt[norm(key)]=key
    for f in re.findall(r'"([^"]+)"',m.group(2)): wt.setdefault(norm(f),key)
cmds=[]; newsys=[]; used=set()
skip=('PLACEHOLDER','%ANDROIDAPP%','%FILEINJECT%')
for s in ET.parse(A+'es_systems_saf.xml').getroot().findall('system'):
    name=s.findtext('name'); key=wt.get(norm(name))
    good=[]
    for c in s.findall('command'):
        t=(c.text or '').strip()
        if not t or any(k in t for k in skip): continue
        m=re.match(r'%EMULATOR_([^%]+)%',t)
        if not m or m.group(1) not in emus: continue
        good.append((c.get('label') or m.group(1).title(), t))
    if not good: continue
    if key is None:
        key=norm(name)
        p=(s.findtext('platform') or '').split(',')[0].strip()
        sid=int(ss.get(plat.get(p,''),0))
        newsys.append((key,s.findtext('fullname'),sid,norm(name)))
        wt[norm(name)]=key
    for lab,t in good:
        if (key,t) in used: continue
        used.add((key,t)); cmds.append((key,lab,t))
with open(WT+'/app/src/main/assets/launch/es-de.tsv','w') as f:
    f.write('# Emulator launch rules for Android, from ES-DE Frontend (es-de.org), MIT License, Copyright (c) Leon Styhre and contributors. See es-de-LICENSE.txt.\n')
    for k,v in sorted(emus.items()):
        if v: f.write('E\t'+k+'\t'+'\t'.join(v)+'\n')
    for key,lab,t in cmds: f.write('C\t'+key+'\t'+lab+'\t'+t+'\n')
def kstr(s): return '"'+s.replace('\\','\\\\').replace('"','\\"').replace('$','\\$')+'"'
with open(WT+'/app/src/main/java/com/nerfherder/walkthor/data/MoreSystems.kt','w') as f:
    f.write('package com.nerfherder.walkthor.data\n\n// Made by a script from ES-DE Frontend\'s Android systems (MIT License): every system it can\n// start games for that WalkThor didn\'t already list. The folder name is ES-DE\'s.\n')
    f.write('internal val MORE_SYSTEMS = listOf(\n')
    for key,full,sid,folder in newsys: f.write(f'    GameSystem({kstr(key)}, {kstr(full)}, {sid}, setOf({kstr(folder)})),\n')
    f.write(')\n')
print(len(emus),'emulators;',len(cmds),'commands;',len(newsys),'new systems')
print(sorted(set(k for k,_,_ in cmds)-set(n[0] for n in newsys)))
