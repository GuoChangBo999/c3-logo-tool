# -*- coding: utf-8 -*-
"""
Flatten BouncyCastle's multi-release jar CORRECTLY.

Previous attempt appended the versions/11 classes after already writing the
same paths from the root -> duplicate zip entries. D8/zip readers take the
FIRST entry, so the JDK-8 stub would still win. This version builds an ordered
name->bytes map (versions/11 wins) and writes each path exactly once.
"""
import os, zipfile

LIBS = '/root/libs'
OUT = '/root/prebuilt_bc'
VR = 'META-INF/versions/11/'


def flatten(src, dst):
    if not os.path.exists(src):
        print('MISSING ' + src)
        return
    os.makedirs(OUT, exist_ok=True)
    zin = zipfile.ZipFile(src, 'r')

    entries = {}   # name -> (is_from_versions11, bytes)
    for n in zin.namelist():
        if n.endswith('/'):
            continue
        up = n.upper()
        if up.startswith('META-INF/SCRIPTS') or up.endswith(('.SF', '.DSA', '.RSA')):
            continue                      # signing material - drop, we resigned
        if n.startswith(VR):
            continue                      # handled in pass 2
        entries[n] = (False, None)

    # pass 2: hoist versions/11 classes; they must REPLACE the root stub
    hoisted = overridden = added = 0
    for n in zin.namelist():
        if not n.startswith(VR) or not n.endswith('.class'):
            continue
        target = n[len(VR):]
        if target in entries:
            overridden += 1
        else:
            added += 1
        entries[target] = (True, None)
        hoisted += 1

    zout = zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED)
    dup_guard = set()
    # root entries we must NOT emit where a versions/11 override exists
    override_targets = set()
    for n in zin.namelist():
        if n.startswith(VR) and n.endswith('.class'):
            override_targets.add(n[len(VR):])

    # 1) emit everything that is NOT overridden, and NOT the versions tree
    for n in zin.namelist():
        if n.endswith('/') or n.startswith(VR):
            continue
        up = n.upper()
        if up.startswith('META-INF/SCRIPTS') or up.endswith(('.SF', '.DSA', '.RSA')):
            continue
        if n in override_targets:
            continue                      # superseded by the hoisted class
        if n in dup_guard:
            continue
        dup_guard.add(n)
        zout.writestr(n, zin.read(n))

    # 2) emit the hoisted (real) classes exactly once
    for n in zin.namelist():
        if not n.startswith(VR) or not n.endswith('.class'):
            continue
        target = n[len(VR):]
        if target in dup_guard:
            continue
        dup_guard.add(target)
        zout.writestr(target, zin.read(n))

    zout.close()
    zin.close()
    print('%s -> %s (%d bytes) hoisted=%d overridden=%d added=%d total_entries=%d'
          % (os.path.basename(src), os.path.basename(dst),
             os.path.getsize(dst), hoisted, overridden, added, len(dup_guard)))
    return dst


for src, dst in [
    ('bcprov-jdk18on-1.78.1.jar', 'bcprov-jdk18on-1.79.jar'),
    ('bcpkix-jdk18on-1.78.1.jar', 'bcpkix-jdk18on-1.79.jar'),
    ('bcutil-jdk18on-1.78.1.jar', 'bcutil-jdk18on-1.79.jar'),
]:
    flatten(os.path.join(LIBS, src), os.path.join(OUT, dst))
print('DONE')