# -*- coding: utf-8 -*-
"""
Flatten BouncyCastle multi-release jars - CORRECTLY, all version tiers.

Discovery that motivated v3: bcprov 1.78/1.79 also carries
    META-INF/versions/15/  (Ed25519/X25519 KeyFactory + KeyPairGenerator SPIs)
    META-INF/versions/9/module-info.class
The previous script only hoisted versions/11, leaving:
  * versions/15 classes unreachable from dex (same bug, different tier), and
  * versions/9/module-info.class, which D8 chokes on ("module-info.class
    is not supported") and which also collided with the packagingOptions glob.

v3 walks EVERY META-INF/versions/<N>/ tier, hoists its .class files to the jar
root and drops the whole META-INF/versions tree (plus module-info) entirely.
Higher N wins when two tiers define the same class.
"""
import os, zipfile

LIBS = '/root/libs'
OUT = '/root/prebuilt_bc'
VR = 'META-INF/versions/'


def flatten(src, dst):
    if not os.path.exists(src):
        print('MISSING ' + src)
        return None
    os.makedirs(OUT, exist_ok=True)
    zin = zipfile.ZipFile(src, 'r')
    names = zin.namelist()

    # ---- 1) gather every versioned class, keyed by its real FQCN ----------
    #    parse the tier number so a higher tier overrides a lower one
    hoist = {}                      # target_path -> (tier, source_entry)
    def tier_of(n):
        rest = n[len(VR):]                       # "11/org/..." or "9/module-info.class"
        head = rest.split('/', 1)[0]
        try:
            return int(head)
        except ValueError:
            return 0

    for n in names:
        if not n.startswith(VR) or not n.endswith('.class'):
            continue
        tier = tier_of(n)
        target = n[len(VR):]
        # strip the leading "<tier>/"
        target = target.split('/', 1)[1] if '/' in target else target
        if 'module-info' in target:
            continue                             # dropped entirely (D8 ban)
        prev = hoist.get(target)
        if prev is None or tier > prev[0]:
            hoist[target] = (tier, n)

    # ---- 2) write the jar: root files first, hoisted classes replace stubs
    zout = zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED)
    seen = set()

    for n in names:
        if n.endswith('/'):
            continue
        if n.startswith(VR):
            continue
        up = n.upper()
        if up.endswith(('.SF', '.DSA', '.RSA')):
            continue
        if n == 'module-info.class' or n.endswith('/module-info.class'):
            continue
        if n in hoist:
            continue                             # superseded, written in pass 2
        if n in seen:
            continue
        seen.add(n)
        zout.writestr(n, zin.read(n))

    for target in sorted(hoist):
        if target in seen:
            continue
        seen.add(target)
        zout.writestr(target, zin.read(hoist[target][1]))

    zout.close()
    zin.close()

    # ---- 3) verification ---------------------------------------------------
    z = zipfile.ZipFile(dst)
    vv = [x for x in z.namelist() if x.startswith(VR)]
    mi = [x for x in z.namelist() if 'module-info' in x]
    nm = z.namelist()
    dup = [x for x in set(nm) if nm.count(x) > 1]
    print('%-28s -> %-26s %8d B  hoisted=%-4d dup=%-3d versions_left=%d module_info=%d'
          % (os.path.basename(src), os.path.basename(dst),
             os.path.getsize(dst), len(hoist), len(dup), len(vv), len(mi)))
    for probe in ['org/bouncycastle/jcajce/provider/asymmetric/edec/KeyAgreementSpi$X25519.class',
                  'org/bouncycastle/jcajce/provider/asymmetric/edec/KeyFactorySpi$X25519.class',
                  'org/bouncycastle/jcajce/provider/asymmetric/edec/KeyPairGeneratorSpi$X25519.class']:
        if probe in z.namelist():
            print('      %-72s %d B' % (probe.rsplit('/', 1)[1], z.getinfo(probe).file_size))
        else:
            print('      MISSING ' + probe)
    z.close()
    return dst


for s, d in [
    ('bcprov-jdk18on-1.78.1.jar', 'bcprov-jdk18on-1.79.jar'),
    ('bcpkix-jdk18on-1.78.1.jar', 'bcpkix-jdk18on-1.79.jar'),
    ('bcutil-jdk18on-1.78.1.jar', 'bcutil-jdk18on-1.79.jar'),
]:
    flatten(os.path.join(LIBS, s), os.path.join(OUT, d))
print('DONE')