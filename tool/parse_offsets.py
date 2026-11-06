#!/usr/bin/env python3
"""parse_offsets.py -- build the IC Timer LPE symbol table for any vivo/iQOO
6.1.145 kernel build.

The exploit needs 66 link-time symbol addresses (62 sched_blocked_reason
anchors + 4 targets).  They are build-specific, so a kernel that is not
6.1.145-android14-11-gf506252fa70d needs its own table.  This tool accepts
whatever the vendor published and produces /data/local/tmp/ic-syms.txt:

  * a full OTA zip (payload.bin, AB/full OTA -- boot partition is extracted,
    zstd/gzip/lzma/bzip2 operations are handled)
  * payload.bin on its own
  * boot.img (Android boot v0-v4)
  * a raw/compressed ARM64 kernel Image (lz4-legacy, gzip, zlib)
  * a vmlinux ELF (uses .symtab)
  * a System.map / ksyms.txt text file

Usage:
  python -I parse_offsets.py <input> [out_syms] [--boot-out FILE] [--adb]
  python -I parse_offsets.py --push <input> [--adb-serial SERIAL]

Examples:
  python -I parse_offsets.py PD2307_A_16.2.20.3.zip ic-syms.txt
  python -I parse_offsets.py boot.img ic-syms.txt --adb
  adb push ic-syms.txt /data/local/tmp/ic-syms.txt

Requires only the Python standard library; zstd operations additionally need
`7z` or `zstd` on PATH.
"""
import bz2
import gzip
import importlib.util
import lzma
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
REQUIRED = [
    "__filemap_fdatawait_range", "__vm_munmap", "__wait_one_discard_bio",
    "do_checkpoint", "do_exit", "f2fs_issue_checkpoint",
    "f2fs_wait_on_node_pages_writeback", "filemap_read", "msleep",
    "rcu_gp_fqs_loop", "rcu_gp_kthread", "runqueues",
    "synchronize_rcu_expedited", "ufshcd_delay_us", "worker_thread",
    "futex_wait_queue", "futex_wait", "do_futex", "pipe_read", "pipe_write",
    "do_epoll_wait", "schedule_timeout", "io_schedule",
    "msleep_interruptible", "wait_for_completion",
    "wait_for_completion_interruptible", "mutex_lock",
    "__mutex_lock_slowpath", "down", "down_read", "kthread", "kthreadd",
    "kthread_worker_fn", "rescuer_thread", "flush_work", "cancel_work_sync",
    "jbd2_log_wait_commit", "jbd2_journal_commit_transaction",
    "f2fs_balance_fs", "f2fs_write_checkpoint", "submit_bio_wait", "vfs_read",
    "vfs_write", "ksys_read", "ksys_write", "sock_recvmsg",
    "unix_stream_recvmsg", "tcp_recvmsg", "n_tty_read", "binder_thread_read",
    "binder_ioctl", "do_sys_poll", "do_select", "core_sys_select",
    "do_group_exit", "vm_munmap", "mmap_region", "filemap_fdatawait_range",
    "synchronize_rcu", "io_wq_submit_work", "__flush_workqueue",
    "drain_workqueue",
    "__tracepoint_sys_exit", "selinux_state", "init_user_ns", "init_cred",
]

# Optional symbols: emitted when the image has them.  panic_on_warn
# lets the launcher disable the WARN-to-panic net before any kernel write.
OPTIONAL = ["sig_enforce", "module_notify_list", "panic_on_warn"]
TARGETS = REQUIRED[-4:]


def log(*a):
    print(*a, file=sys.stderr)


def load_kallsyms():
    p = os.path.join(HERE, "kallsyms_py.py")
    spec = importlib.util.spec_from_file_location("kallsyms_py", p)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


# --------------------------------------------------------------- protobuf
def _varint(b, i):
    v = 0
    s = 0
    while True:
        c = b[i]
        i += 1
        v |= (c & 0x7F) << s
        s += 7
        if not c & 0x80:
            return v, i


def pb_fields(b):
    i = 0
    out = []
    while i < len(b):
        key, i = _varint(b, i)
        fn, wt = key >> 3, key & 7
        if wt == 0:
            v, i = _varint(b, i)
        elif wt == 1:
            v = b[i:i + 8]
            i += 8
        elif wt == 2:
            l, i = _varint(b, i)
            v = b[i:i + l]
            i += l
        elif wt == 5:
            v = b[i:i + 4]
            i += 4
        else:
            raise ValueError("bad wire type %d" % wt)
        out.append((fn, wt, v))
    return out


def try_extents(b):
    """Decode a repeated Extent message; return [(start_block, num_blocks)]."""
    try:
        f = pb_fields(b)
    except Exception:
        return None
    if not f:
        return None
    exts = []
    cur = {}
    for fn, wt, v in f:
        if fn == 1 and wt == 0:
            if cur:
                exts.append((cur.get(1, 0), cur.get(2, 0)))
                cur = {}
            cur[1] = v
        elif fn == 2 and wt == 0:
            cur[2] = v
        else:
            return None
    if cur:
        exts.append((cur.get(1, 0), cur.get(2, 0)))
    if not exts or any(n <= 0 or n > 0x1000000 for _, n in exts):
        return None
    return exts


# -------------------------------------------------------------- payload
def _zip_data_start(zf, name):
    zi = zf.getinfo(name)
    with open(zf.filename, "rb") as f:
        f.seek(zi.header_offset)
        h = f.read(30)
        if h[:4] != b"PK\x03\x04":
            raise SystemExit("bad zip local header for %s" % name)
        nlen = struct.unpack_from("<H", h, 26)[0]
        elen = struct.unpack_from("<H", h, 28)[0]
        return zi.header_offset + 30 + nlen + elen, zi.compress_type


def extract_boot_from_payload(payload_path, out_boot):
    """Extract the `boot` partition from an Android update payload."""
    with open(payload_path, "rb") as f:
        hdr = f.read(24)
        magic, ver, msize, sig = struct.unpack(">4sQQI", hdr)
        if magic != b"CrAU":
            raise SystemExit("not an update payload (magic=%r)" % magic)
        if ver != 2:
            log("[ota] payload version %d (expected 2)" % ver)
        man = f.read(msize)
        f.read(sig)
        data_start = 24 + msize + sig
        log("[ota] payload v%d manifest=%d sig=%d data@%#x" %
            (ver, msize, sig, data_start))
        return _payload_boot(f, data_start, man, out_boot)


def _payload_boot(f, data_start, man, out_boot, base=0):
    top = pb_fields(man)
    block_size = 4096
    for fn, wt, v in top:
        if fn == 3 and wt == 0:
            block_size = v
    partitions = []
    for fn, wt, v in top:
        if fn == 13 and wt == 2:
            partitions.append(pb_fields(v))
    want = None
    names = []
    for p in partitions:
        nm = ""
        for fn, wt, v in p:
            if fn == 1 and wt == 2:
                nm = v.decode("utf-8", "replace")
                break
        names.append(nm)
        if nm == "boot":
            want = p
    if want is None:
        raise SystemExit("no 'boot' partition (partitions: %s)" %
                         ", ".join(names[:50]))
    size = None
    ops = []
    for fn, wt, v in want:
        if fn == 7 and wt == 2:
            for a, b, c in pb_fields(v):
                if a == 1 and b == 0:
                    size = c
        elif fn == 8 and wt == 2:
            ops.append(pb_fields(v))
    if not size:
        size = 0
    log("[ota] boot partition: size=%d block=%d ops=%d" %
        (size, block_size, len(ops)))
    out = bytearray(size)
    cursor = 0
    for idx, op in enumerate(ops):
        typ = off = dlen = 0
        exts = None
        for fn, wt, v in op:
            if fn == 1 and wt == 0:
                typ = v
            elif fn == 2 and wt == 0:
                off = v
            elif fn == 3 and wt == 0:
                dlen = v
            elif wt == 2 and fn in (6, 8):
                e = try_extents(v)
                if e:
                    exts = e
        if typ in (6, 7):  # ZERO / DISCARD
            log("[ota] op %d type=%d zero/discard" % (idx, typ))
            continue
        if not dlen:
            log("[ota] op %d type=%d empty, skipped" % (idx, typ))
            continue
        f.seek(base + data_start + off)
        blob = f.read(dlen)
        if len(blob) != dlen:
            raise SystemExit("short read for op %d" % idx)
        raw = decompress_op(blob, typ)
        if exts is None:
            exts = [(cursor // block_size, (len(raw) + block_size - 1) // block_size)]
        pos = 0
        for start, nblk in exts:
            n = nblk * block_size
            chunk = raw[pos:pos + n]
            dst = start * block_size
            if dst + len(chunk) > len(out):
                raise SystemExit("op %d writes past partition" % idx)
            out[dst:dst + len(chunk)] = chunk
            pos += len(chunk)
            cursor = max(cursor, dst + len(chunk))
        log("[ota] op %d type=%d %d bytes -> %s (+%d)" %
            (idx, typ, len(raw), exts[0], len(exts)))
    with open(out_boot, "wb") as f:
        f.write(out)
    log("[ota] wrote %s (%d bytes)" % (out_boot, len(out)))
    return out_boot


def decompress_op(blob, typ):
    if typ == 0:
        return blob
    if typ == 1:
        return bz2.decompress(blob)
    if typ == 8:
        return lzma.decompress(blob)
    if typ == 14:
        if blob[:4] == b"\x28\xb5\x2f\xfd":
            return decompress_zstd(blob)
        return blob
    raise SystemExit("unsupported payload operation type %d" % typ)


def decompress_zstd(blob):
    z7 = shutil.which("7z") or shutil.which("7za") or shutil.which("7zr")
    zst = shutil.which("zstd")
    with tempfile.NamedTemporaryFile(suffix=".zst", delete=False) as t:
        t.write(blob)
        name = t.name
    try:
        if z7:
            r = subprocess.run([z7, "x", "-y", "-so", name],
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        elif zst:
            r = subprocess.run([zst, "-d", "-c", name],
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        else:
            raise SystemExit("zstd payload needs 7z or zstd on PATH")
        if r.returncode != 0:
            raise SystemExit("zstd decompress failed: %s" %
                             r.stderr.decode("utf-8", "replace")[:300])
        return r.stdout
    finally:
        try:
            os.unlink(name)
        except OSError:
            pass


# ----------------------------------------------------------- boot image
def boot_kernel(img):
    if len(img) < 64 or img[:8] != b"ANDROID!":
        return img
    ksize = struct.unpack_from("<I", img, 8)[0]
    hver = struct.unpack_from("<I", img, 40)[0]
    if hver >= 3:
        page = 4096
    else:
        page = struct.unpack_from("<I", img, 36)[0]
        if page < 512 or page > 65536 or page & (page - 1):
            raise SystemExit("bad boot page size %d" % page)
    if not ksize or page + ksize > len(img):
        raise SystemExit("bad boot kernel size %d" % ksize)
    log("[boot] v%d kernel@%#x size=%d" % (hver, page, ksize))
    return img[page:page + ksize]


def lz4_legacy_decode(src):
    if src[:4] != b"\x02\x21\x4c\x18":
        return None
    out = bytearray()
    i = 4
    n = len(src)
    while i + 4 <= n:
        bsz = struct.unpack_from("<I", src, i)[0]
        i += 4
        if bsz == 0:
            break
        blk = src[i:i + bsz]
        if len(blk) != bsz:
            raise SystemExit("short lz4 block")
        i += bsz
        p = 0
        while p < bsz:
            token = blk[p]
            p += 1
            ll = token >> 4
            if ll == 15:
                while True:
                    s = blk[p]
                    p += 1
                    ll += s
                    if s != 255:
                        break
            out += blk[p:p + ll]
            p += ll
            if p >= bsz:
                break
            off = blk[p] | (blk[p + 1] << 8)
            p += 2
            if off == 0 or off > len(out):
                raise SystemExit("bad lz4 offset")
            ml = (token & 15) + 4
            if (token & 15) == 15:
                while True:
                    s = blk[p]
                    p += 1
                    ml += s
                    if s != 255:
                        break
            start = len(out) - off
            for k in range(ml):
                out.append(out[start + k])
    return bytes(out)


def decompress_kernel(kern):
    if kern[:4] == b"\x02\x21\x4c\x18":
        out = lz4_legacy_decode(kern)
        log("[kernel] lz4-legacy %d -> %d" % (len(kern), len(out)))
        return out
    if kern[:2] == b"\x1f\x8b":
        out = gzip.decompress(kern)
        log("[kernel] gzip %d -> %d" % (len(kern), len(out)))
        return out
    if kern[:4] == b"\x28\xb5\x2f\xfd":
        out = decompress_zstd(kern)
        log("[kernel] zstd %d -> %d" % (len(kern), len(out)))
        return out
    return kern


def banner_release(img):
    i = img.find(b"Linux version ")
    if i < 0:
        return ""
    j = i + len(b"Linux version ")
    k = j
    while k < len(img) and img[k:k + 1] not in b" \n\r\0":
        k += 1
    return img[j:k].decode("ascii", "replace")


# ------------------------------------------------------------- elf/text
def parse_elf(img):
    if img[:4] != b"\x7fELF" or img[4] != 2 or img[5] != 1:
        return None
    shoff = struct.unpack_from("<Q", img, 0x28)[0]
    shentsize, shnum = struct.unpack_from("<HH", img, 0x3A)
    if shentsize < 64 or shoff + shnum * shentsize > len(img):
        return None
    syms = {}
    for i in range(shnum):
        sh = img[shoff + i * shentsize: shoff + (i + 1) * shentsize]
        typ, = struct.unpack_from("<I", sh, 4)
        link, = struct.unpack_from("<I", sh, 0x28)
        off, size = struct.unpack_from("<QQ", sh, 0x18)
        entsize, = struct.unpack_from("<Q", sh, 0x38)
        if typ != 2 or not entsize or entsize < 24 or link >= shnum:
            continue
        st = img[shoff + link * shentsize: shoff + (link + 1) * shentsize]
        stroff, strsize = struct.unpack_from("<QQ", st, 0x18)
        if stroff + strsize > len(img) or off + size > len(img):
            continue
        for j in range(0, size - 23, entsize):
            nameoff, = struct.unpack_from("<I", img, off + j)
            value, = struct.unpack_from("<Q", img, off + j + 8)
            if not value or nameoff >= strsize:
                continue
            s = img[stroff + nameoff: stroff + strsize].split(b"\0", 1)[0]
            name = s.decode("ascii", "replace")
            if name in REQUIRED:
                syms.setdefault(name, value)
    return syms or None


def parse_text(img):
    syms = {}
    build = ""
    for line in img.decode("utf-8", "replace").splitlines():
        line = line.strip()
        if not line:
            continue
        if line.startswith("# build="):
            build = line[8:].strip()
            continue
        if "=" in line:
            a, b = line.split("=", 1)
            a, b = a.strip(), b.strip()
            if a in REQUIRED:
                try:
                    syms.setdefault(a, int(b, 16))
                except ValueError:
                    pass
            continue
        tok = line.split()
        if len(tok) < 2:
            continue
        name = tok[-1]
        if name not in REQUIRED:
            continue
        for t in tok[:-1]:
            try:
                syms.setdefault(name, int(t, 16))
                break
            except ValueError:
                continue
    return syms or None, build


# ---------------------------------------------------------------- output
def write_table(out_path, syms, build, version=""):
    missing = [n for n in TARGETS if n not in syms]
    if missing:
        raise SystemExit("missing targets: %s" % ", ".join(missing))
    got = sum(1 for n in REQUIRED if n in syms)
    if got < 8:
        raise SystemExit("only %d required symbols found" % got)
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("# ic-timer symbol table\n")
        if build:
            f.write("# build=%s\n" % build)
        if version:
            f.write("# version=%s\n" % version)
        for n in REQUIRED:
            if n in syms:
                f.write("%s=0x%x\n" % (n, syms[n]))
        for n in OPTIONAL:
            if n in syms:
                f.write("%s=0x%x\n" % (n, syms[n]))
    log("[out] %s: %d/%d symbols, targets=%d/4, build=%s" %
        (out_path, got, len(REQUIRED), len(TARGETS), build or "?"))


def recover_from_image(img):
    k = load_kallsyms()
    boot = boot_kernel(img)
    kern = decompress_kernel(boot)
    build = banner_release(kern)
    log("[kernel] build=%s image=%d" % (build or "?", len(kern)))
    entries, syms = k.recover(kern, None)
    out = {}
    for name in REQUIRED + OPTIONAL:
        vals = syms.get(name)
        if vals:
            out[name] = sorted(vals)[0]
    return out, build


# ------------------------------------------------------------------ main
def usage():
    print(__doc__)
    sys.exit(2)


def main():
    args = sys.argv[1:]
    if not args:
        usage()
    push = False
    adb_serial = None
    boot_out = None
    inputs = []
    i = 0
    while i < len(args):
        a = args[i]
        if a == "--push":
            push = True
        elif a == "--adb":
            push = True
        elif a == "--adb-serial":
            i += 1
            adb_serial = args[i]
            push = True
        elif a == "--boot-out":
            i += 1
            boot_out = args[i]
        elif a in ("-h", "--help"):
            usage()
        else:
            inputs.append(a)
        i += 1
    if not inputs:
        usage()
    src = inputs[0]
    out = inputs[1] if len(inputs) > 1 else "ic-syms.txt"

    with open(src, "rb") as f:
        head = f.read(16)
    syms = None
    build = ""
    version = ""

    if head[:4] == b"PK\x03\x04":
        with zipfile.ZipFile(src) as zf:
            names = zf.namelist()
            if "payload.bin" not in names:
                raise SystemExit("zip has no payload.bin (not a full OTA)")
            log("[ota] %s (%d bytes)" % (src, os.path.getsize(src)))
            start, ctype = _zip_data_start(zf, "payload.bin")
            if ctype != 0:
                raise SystemExit("payload.bin is compressed in the zip; "
                                 "unzip it first")
            with open(src, "rb") as f:
                f.seek(start)
                hdr = f.read(24)
                magic, ver, msize, sig = struct.unpack(">4sQQI", hdr)
                if magic != b"CrAU":
                    raise SystemExit("bad payload header")
                man = f.read(msize)
                f.read(sig)
                data_start = 24 + msize + sig
                tmp = tempfile.NamedTemporaryFile(suffix=".img",
                                                  delete=False)
                tmp.close()
                try:
                    _payload_boot(f, data_start, man, tmp.name, start)
                    img = open(tmp.name, "rb").read()
                    syms, build = recover_from_image(img)
                    if boot_out:
                        shutil.copyfile(tmp.name, boot_out)
                        log("[boot] saved %s" % boot_out)
                finally:
                    try:
                        os.unlink(tmp.name)
                    except OSError:
                        pass
    elif head[:4] == b"CrAU":
        # Read the manifest from the file; never read the whole payload into
        # memory (a full OTA is ~10 GB).
        with open(src, "rb") as f:
            hdr = f.read(24)
            magic, ver, msize, sig = struct.unpack(">4sQQI", hdr)
            man = f.read(msize)
            tmp = tempfile.NamedTemporaryFile(suffix=".img", delete=False)
            tmp.close()
            try:
                _payload_boot(f, 24 + msize + sig, man, tmp.name)
                img = open(tmp.name, "rb").read()
                syms, build = recover_from_image(img)
                if boot_out:
                    shutil.copyfile(tmp.name, boot_out)
            finally:
                try:
                    os.unlink(tmp.name)
                except OSError:
                    pass
    else:
        data = open(src, "rb").read()
        if data[:4] == b"\x7fELF":
            s = parse_elf(data)
            if not s:
                raise SystemExit("ELF without a usable .symtab (stripped?)")
            syms = s
            build = banner_release(data)
        elif data[:8] == b"ANDROID!" or data[:4] in (
                b"\x02\x21\x4c\x18", b"\x1f\x8b", b"\x28\xb5\x2f\xfd") or \
                (len(data) > 0x40 and data[0x38:0x3c] == b"ARM\x64"):
            syms, build = recover_from_image(data)
        else:
            # System.map / ksyms.txt / an existing ic-syms.txt
            s, b = parse_text(data)
            if not s:
                raise SystemExit("unrecognised input (not OTA/boot.img/"
                                 "Image/ELF/System.map)")
            syms = s
            build = b

    write_table(out, syms, build, version)

    if push:
        adb = shutil.which("adb") or "adb"
        cmd = [adb]
        if adb_serial:
            cmd += ["-s", adb_serial]
        cmd += ["push", out, "/data/local/tmp/ic-syms.txt"]
        log("[adb] %s" % " ".join(cmd))
        subprocess.run(cmd, check=False)
        print("/data/local/tmp/ic-syms.txt")


if __name__ == "__main__":
    main()
