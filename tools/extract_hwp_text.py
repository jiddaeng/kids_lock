"""Extract paragraph text from an unencrypted HWP 5 compound file (stdlib only)."""
from pathlib import Path
import struct
import sys
import zlib


def extract(path):
    data = Path(path).read_bytes()
    if data[:8] != bytes.fromhex('d0cf11e0a1b11ae1'):
        raise ValueError('Not a compound HWP file')
    sector_size = 1 << struct.unpack_from('<H', data, 30)[0]
    mini_size = 1 << struct.unpack_from('<H', data, 32)[0]
    def sector(n):
        return data[(n + 1) * sector_size:(n + 2) * sector_size]
    def ints(b):
        return list(struct.unpack('<' + 'I' * (len(b) // 4), b))
    difat = ints(data[76:512])
    nxt, count = struct.unpack_from('<II', data, 68)
    for _ in range(count):
        values = ints(sector(nxt))
        difat.extend(values[:-1])
        nxt = values[-1]
    fat = []
    for n in difat:
        if n < 0xfffffffa:
            fat.extend(ints(sector(n)))
    def chain(start, table, reader):
        result, seen = [], set()
        while start < 0xfffffffa:
            if start in seen:
                raise ValueError('Cyclic sector chain')
            seen.add(start)
            result.append(reader(start))
            start = table[start]
        return b''.join(result)
    directory = chain(struct.unpack_from('<I', data, 48)[0], fat, sector)
    entries = []
    for off in range(0, len(directory), 128):
        e = directory[off:off + 128]
        length = struct.unpack_from('<H', e, 64)[0]
        name = e[:max(0, length - 2)].decode('utf-16le', errors='replace')
        entries.append((name, e[66], struct.unpack_from('<I', e, 116)[0], struct.unpack_from('<Q', e, 120)[0]))
    root = next(e for e in entries if e[1] == 5)
    mini_stream = chain(root[2], fat, sector)[:root[3]]
    mini_fat = ints(chain(struct.unpack_from('<I', data, 60)[0], fat, sector))
    def stream(entry):
        _, _, start, size = entry
        if size < struct.unpack_from('<I', data, 56)[0]:
            return chain(start, mini_fat, lambda n: mini_stream[n * mini_size:(n + 1) * mini_size])[:size]
        return chain(start, fat, sector)[:size]
    header = stream(next(e for e in entries if e[0] == 'FileHeader'))
    flags = struct.unpack_from('<I', header, 36)[0]
    if flags & 6:
        raise ValueError('Encrypted or distribution document is unsupported')
    result = []
    sections = sorted((e for e in entries if e[0].startswith('Section') and e[1] == 2), key=lambda e: int(e[0][7:]))
    for entry in sections:
        body = stream(entry)
        if flags & 1:
            body = zlib.decompress(body, -15)
        result.append('\n## ' + entry[0] + '\n')
        pos = 0
        while pos + 4 <= len(body):
            rec = struct.unpack_from('<I', body, pos)[0]
            pos += 4
            tag, size = rec & 0x3ff, rec >> 20
            if size == 0xfff:
                size = struct.unpack_from('<I', body, pos)[0]
                pos += 4
            payload = body[pos:pos + size]
            pos += size
            if tag != 67:
                continue
            units = list(struct.unpack('<' + 'H' * (len(payload) // 2), payload))
            chars, i = [], 0
            extended = {1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 12, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23}
            while i < len(units):
                c = units[i]
                if c in extended:
                    if c == 9:
                        chars.append('\t')
                    i += 8
                else:
                    if c >= 32:
                        chars.append(chr(c))
                    elif c in (10, 13):
                        chars.append('\n')
                    i += 1
            text = ''.join(chars).strip()
            if text:
                result.append(text)
    return '\n'.join(result)


if __name__ == '__main__':
    output = Path(sys.argv[2])
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(extract(sys.argv[1]), encoding='utf-8')
    print(str(output))
