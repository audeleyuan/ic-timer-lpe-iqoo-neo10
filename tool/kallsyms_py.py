#!/usr/bin/env python3
"""Pure-Python port of ghostlock-extract's in-image kallsyms recovery.

Source: D:\\glbuild\\fork_src\\Ghostlock-vr-main\\tools\\extract_rs\\src\\kallsyms_finder.rs
Usage: python -I kallsyms_py.py <image> [--btf]
"""
import struct
import sys

ALIGN = 8
TOKEN_COUNT = 256
TOKEN_INDEX_SIZE = TOKEN_COUNT * 2
MAX_TOKEN_LEN = 256
MARKER_SEARCH_WINDOW = 4 * 1024 * 1024
NAME_TAIL_SEARCH = 0x40000
MIN_MARKERS = 8
MAX_MARKERS = 4096
SYMBOL_TYPES = set(b"aAbBcCdDeEfFgGiInNpPrRsStTuUvVwW?-")
REQUIRED_SYMBOLS = ("_text", "_end", "load_module")


def align_up(v, a):
    return (v + a - 1) & ~(a - 1)


def r16(d, o):
    return struct.unpack_from('<H', d, o)[0] if o + 2 <= len(d) else None


def r32(d, o):
    return struct.unpack_from('<I', d, o)[0] if o + 4 <= len(d) else None


def ri32(d, o):
    return struct.unpack_from('<i', d, o)[0] if o + 4 <= len(d) else None


def r64(d, o):
    return struct.unpack_from('<Q', d, o)[0] if o + 8 <= len(d) else None


def arm64_image_size(image):
    if len(image) < 64 or image[0x38:0x3c] != b'ARM\x64':
        return None
    size = r64(image, 0x10)
    return size if size else None


class TokenTable:
    def __init__(self, tokens, table_offset, index_offset):
        self.tokens = tokens
        self.table_offset = table_offset
        self.index_offset = index_offset


def parse_token_table_at(image, start, digit_offset):
    if start % ALIGN != 0:
        return None
    tokens = []
    token_offsets = []
    token_starts = []
    pos = start
    for _ in range(TOKEN_COUNT):
        token_starts.append(pos)
        search_end = min(pos + MAX_TOKEN_LEN + 1, len(image))
        if pos >= search_end:
            return None
        try:
            length = image.index(0, pos, search_end) - pos
        except ValueError:
            return None
        if length == 0:
            return None
        end = pos + length
        token = image[pos:end]
        if any(not (0x20 <= b <= 0x7e) for b in token):
            return None
        token_offsets.append(pos - start)
        tokens.append(token)
        pos = end + 1
    if token_starts[ord('0')] != digit_offset:
        return None
    for value in b"0123456789_abcdefghijklmnopqrstuvwxyzT":
        if tokens[value] != bytes([value]):
            return None
    index_offset = align_up(pos, ALIGN)
    if pos < index_offset and any(b != 0 for b in image[pos:index_offset]):
        return None
    index_end = index_offset + TOKEN_INDEX_SIZE
    if index_end > len(image) or token_offsets[-1] > 0xFFFF:
        return None
    for idx, expected in enumerate(token_offsets):
        if r16(image, index_offset + idx * 2) != expected:
            return None
    return TokenTable(tokens, start, index_offset)


def find_token_tables(image):
    digit_needle = b''.join(bytes([v, 0]) for v in range(ord('0'), ord('9') + 1))
    tables = []
    search_from = 0
    while True:
        digit_offset = image.find(digit_needle, search_from)
        if digit_offset < 0:
            break
        earliest = max(0, digit_offset - ord('0') * (MAX_TOKEN_LEN + 1))
        start = align_up(earliest, ALIGN)
        while start <= digit_offset:
            t = parse_token_table_at(image, start, digit_offset)
            if t is not None:
                tables.append(t)
            start += ALIGN
        search_from = digit_offset + 1
        if search_from >= len(image):
            break
    return tables


def read_markers(image, start, limit):
    if start % ALIGN != 0 or start + 8 > limit:
        return []
    first = r32(image, start)
    second = r32(image, start + 4)
    if first != 0 or second is None or not (0x200 <= second <= 0x40000):
        return []
    markers = [first, second]
    pos = start + 8
    while len(markers) < MAX_MARKERS and pos + 4 <= limit:
        value = r32(image, pos)
        if value is None:
            break
        delta = value - markers[-1]
        if not (0x200 <= delta <= 0x40000):
            break
        markers.append(value)
        pos += 4
    return [] if len(markers) < MIN_MARKERS else markers


def parse_name_spans(image, names_offset, count, markers_offset, markers, uleb):
    if count == 0 or len(markers) != (count + 255) // 256:
        return None
    spans = []
    pos = names_offset
    for index in range(count):
        if index % 256 == 0 and pos - names_offset != markers[index // 256]:
            return None
        if pos >= markers_offset:
            return None
        length = image[pos]
        pos += 1
        if uleb and length & 0x80:
            if pos >= markers_offset:
                return None
            high = image[pos]
            if high & 0x80:
                return None
            length = (length & 0x7f) | (high << 7)
            pos += 1
        if length == 0 or length > 0x3fff or pos + length > markers_offset:
            return None
        spans.append((pos, length))
        pos += length
    if align_up(pos, ALIGN) != markers_offset:
        return None
    if any(b != 0 for b in image[pos:markers_offset]):
        return None
    return spans


def decode_names(image, spans, tokens):
    decoded = []
    names = set()
    for pos, length in spans:
        encoded = image[pos:pos + length]
        try:
            expanded = b''.join(tokens[i] for i in encoded)
        except IndexError:
            return None
        if not expanded or len(expanded) > 4096:
            return None
        kind = expanded[0]
        name_bytes = expanded[1:]
        if not name_bytes or kind not in SYMBOL_TYPES:
            return None
        if any(not (0x21 <= b <= 0x7e) for b in name_bytes):
            return None
        name = name_bytes.decode('ascii')
        decoded.append((kind, name))
        names.add(name)
    if any(req not in names for req in REQUIRED_SYMBOLS):
        return None
    return decoded


def find_name_tables(image, tokens, token_table_offset):
    lower = max(0, token_table_offset - MARKER_SEARCH_WINDOW)
    marker_candidates = []
    pos = align_up(lower, ALIGN)
    while pos + 8 <= token_table_offset:
        m = read_markers(image, pos, token_table_offset)
        if m:
            marker_candidates.append((pos, m))
        pos += ALIGN
    recovered = []
    for markers_offset, markers in marker_candidates:
        min_count = (len(markers) - 1) * 256 + 1
        max_count = len(markers) * 256
        approx_names = markers_offset - markers[-1]
        if approx_names < 0:
            continue
        search_start = max(0, approx_names - NAME_TAIL_SEARCH)
        num_syms_offset = approx_names - approx_names % ALIGN
        while True:
            if num_syms_offset + 8 <= len(image):
                count = r32(image, num_syms_offset) or 0
                padding_zero = image[num_syms_offset + 4:num_syms_offset + 8] == b'\0\0\0\0'
                if min_count <= count <= max_count and padding_zero:
                    names_offset = num_syms_offset + ALIGN
                    seen = []
                    for uleb in (True, False):
                        spans = parse_name_spans(image, names_offset, count,
                                                 markers_offset, markers, uleb)
                        if spans is None or spans in seen:
                            continue
                        seen.append(spans)
                        decoded = decode_names(image, spans, tokens)
                        if decoded is not None:
                            recovered.append((num_syms_offset, names_offset,
                                              markers_offset, decoded))
            if num_syms_offset < search_start + ALIGN:
                break
            num_syms_offset -= ALIGN
    return recovered


def is_arm64_kernel_address(address):
    return (address >> 48) == 0xffff and address % 4096 == 0


def decode_addresses(image, image_size, names, offsets_offset, relative_base_offset):
    count = len(names)
    offsets_end = offsets_offset + count * 4
    if offsets_end > len(image):
        return None
    if relative_base_offset < offsets_end or relative_base_offset + 8 > len(image):
        return None
    if any(b != 0 for b in image[offsets_end:relative_base_offset]):
        return None
    relative_base = r64(image, relative_base_offset)
    if relative_base is None or not is_arm64_kernel_address(relative_base):
        return None
    signed_offsets = [ri32(image, offsets_offset + i * 4) for i in range(count)]
    if any(v is None for v in signed_offsets):
        return None
    negative_count = sum(1 for v in signed_offsets if v < 0)
    if negative_count * 2 >= count:
        addresses = []
        for off in signed_offsets:
            if off < 0:
                addr = relative_base - 1 - off
            else:
                addr = off
            if addr < 0 or addr > 0xFFFFFFFFFFFFFFFF:
                return None
            addresses.append(addr)
    else:
        addresses = [relative_base + (v & 0xFFFFFFFF) for v in signed_offsets]
        if any(a > 0xFFFFFFFFFFFFFFFF for a in addresses):
            return None
    if any(addresses[i] > addresses[i + 1] for i in range(count - 1)):
        return None
    text_addresses = [a for a, (_, n) in zip(addresses, names) if n == '_text']
    end_addresses = [a for a, (_, n) in zip(addresses, names) if n == '_end']
    if len(text_addresses) != 1 or len(end_addresses) != 1:
        return None
    if text_addresses[0] != relative_base:
        return None
    if end_addresses[0] - text_addresses[0] != image_size:
        return None
    ordinary = [a for a, (k, _) in zip(addresses, names) if k not in b'Aa' and a != 0]
    in_image = sum(1 for a in ordinary if text_addresses[0] <= a <= end_addresses[0])
    if not ordinary or in_image * 100 < len(ordinary) * 90:
        return None
    return list(zip(addresses, [k for k, _ in names], [n for _, n in names]))


def recover(image, btf=None, verbose=True):
    image_size = arm64_image_size(image) or len(image)
    token_tables = find_token_tables(image)
    if verbose:
        print(f"token tables: {len(token_tables)}", file=sys.stderr)
    candidates = []
    name_blocks = 0
    for table in token_tables:
        name_tables = find_name_tables(image, table.tokens, table.table_offset)
        for num_syms_offset, _names_offset, _markers_offset, names in name_tables:
            name_blocks += 1
            count = len(names)
            old_relative_base = num_syms_offset - ALIGN
            old_offsets = old_relative_base - align_up(count * 4, ALIGN)
            new_offsets = align_up(table.index_offset + TOKEN_INDEX_SIZE, ALIGN)
            new_relative_base = align_up(new_offsets + count * 4, ALIGN)
            for layout, offsets_offset, relative_base_offset in (
                ("pre-6.4", old_offsets, old_relative_base),
                ("6.4+", new_offsets, new_relative_base),
            ):
                ent = decode_addresses(image, image_size, names,
                                       offsets_offset, relative_base_offset)
                if ent is not None:
                    candidates.append((layout, ent))
    if verbose:
        print(f"name blocks: {name_blocks}, candidates: {len(candidates)}", file=sys.stderr)
    if not candidates:
        raise SystemExit("no kallsyms table found")
    # resolve helper
    def resolve(entries, name):
        found = None
        for addr, _k, n in entries:
            if n == name:
                if found is not None:
                    return None
                found = addr
        return found

    selected = None
    if btf is not None:
        btf_offset, btf_size = btf
        matches = []
        for i, (layout, entries) in enumerate(candidates):
            base = resolve(entries, '_text')
            start = resolve(entries, '__start_BTF')
            stop = resolve(entries, '__stop_BTF')
            if None in (base, start, stop):
                continue
            if start - base == btf_offset and stop - start == btf_size:
                matches.append(i)
        if len(matches) == 1:
            selected = candidates[matches[0]]
        elif not matches and len(candidates) == 1:
            selected = candidates[0]
        else:
            raise SystemExit(f"ambiguous: {len(candidates)} candidates, {len(matches)} BTF matches")
    elif len(candidates) == 1:
        selected = candidates[0]
    else:
        raise SystemExit(f"ambiguous: {len(candidates)} candidates (no BTF)")
    layout, entries = selected
    if verbose:
        print(f"layout {layout}, {len(entries)} symbols", file=sys.stderr)
    syms = {}
    for addr, kind, name in entries:
        syms.setdefault(name, set()).add(addr)
    return entries, syms


def main():
    image = open(sys.argv[1], 'rb').read()
    btf = None
    if '--btf' in sys.argv:
        btf = find_btf(image)
        print("BTF at %#x size %#x" % btf, file=sys.stderr)
    entries, syms = recover(image, btf)
    base = next(iter(syms['_text']))
    print("_text = %#x  symbols=%d" % (base, len(entries)))
    want = ['_text', 'selinux_state', '__tracepoint_sys_exit',
            '__traceiter_sys_exit', '__traceiter_sys_enter', 'init_task',
            'init_cred', 'root_task_group', 'selinux_blob_sizes',
            'security_hook_heads', 'nfulnl_logger', 'sysctl_bootid',
            'loggers', 'remove_waiter', 'load_module']
    for name in want:
        vals = syms.get(name)
        if not vals:
            print(f"  {name}: NOT FOUND")
            continue
        for v in sorted(vals):
            print(f"  {name}: {v:#x}  off={v - base:#x} ({v - base})")


def find_btf(image):
    # .BTF blob starts with magic 0xEB9F and has a valid header.
    start = 0
    while True:
        i = image.find(b'\x9f\xeb\x01\x00', start)
        if i < 0:
            return None
        if i + 24 <= len(image):
            magic, version, flags, hdr_len = struct.unpack_from('<HBBI', image, i)
            if magic == 0xEB9F and hdr_len in (24,):
                type_off, type_len, str_off, str_len = struct.unpack_from('<IIII', image, i + 8)
                total = hdr_len + type_off + type_len
                total2 = hdr_len + str_off + str_len
                end = i + max(total, total2)
                if end <= len(image) and type_len > 0 and str_len > 0:
                    return i, max(total, total2)
        start = i + 1


if __name__ == '__main__':
    main()
