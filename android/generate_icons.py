"""Generate Android resource files needed to build the APK."""

import struct, zlib, os, io

def create_png(width, height, r, g, b):
    """Create a minimal valid PNG file with a solid color."""
    def write_chunk(chunk_type, data):
        chunk = chunk_type + data
        return struct.pack('>I', len(data)) + chunk + struct.pack('>I', zlib.crc32(chunk) & 0xffffffff)

    buf = io.BytesIO()
    buf.write(b'\x89PNG\r\n\x1a\n')
    buf.write(write_chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0)))

    raw = b''
    for y in range(height):
        raw += b'\x00'  # filter byte
        for x in range(width):
            raw += bytes([r, g, b])

    buf.write(write_chunk(b'IDAT', zlib.compress(raw)))
    buf.write(write_chunk(b'IEND', b''))
    return buf.getvalue()

base = os.path.dirname(os.path.abspath(__file__))

# Generate launcher icons for various densities
icons = {
    'mdpi':   48,
    'hdpi':   72,
    'xhdpi':  96,
    'xxhdpi': 144,
    'xxxhdpi': 192,
}

for density, size in icons.items():
    res_dir = os.path.join(base, 'app', 'src', 'main', 'res', f'mipmap-{density}')
    os.makedirs(res_dir, exist_ok=True)
    png = create_png(size, size, 0x12, 0x12, 0x12)  # Dark background
    with open(os.path.join(res_dir, 'ic_launcher.png'), 'wb') as f:
        f.write(png)

# Also generate ic_launcher_round.png (same thing)
for density, size in icons.items():
    res_dir = os.path.join(base, 'app', 'src', 'main', 'res', f'mipmap-{density}')
    png = create_png(size, size, 0x12, 0x12, 0x12)
    with open(os.path.join(res_dir, 'ic_launcher_round.png'), 'wb') as f:
        f.write(png)

print("Generated launcher icons for all densities")
