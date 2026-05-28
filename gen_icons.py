"""生成 宁宁模拟 APP图标 - 纯Python无依赖"""
import struct, zlib, os, math

def create_png(width, height, pixels):
    """创建PNG文件"""
    def chunk(chunk_type, data):
        c = chunk_type + data
        crc = struct.pack('>I', zlib.crc32(c) & 0xffffffff)
        return struct.pack('>I', len(data)) + c + crc

    header = b'\x89PNG\r\n\x1a\n'
    ihdr = chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))

    raw = b''
    for y in range(height):
        raw += b'\x00'
        for x in range(width):
            raw += bytes(pixels[y][x])

    idat = chunk(b'IDAT', zlib.compress(raw))
    iend = chunk(b'IEND', b'')
    return header + ihdr + idat + iend

def draw_circle(cx, cy, r, color):
    """用像素填充画圆"""
    for y in range(len(pixels)):
        for x in range(len(pixels[0])):
            if (x - cx) ** 2 + (y - cy) ** 2 <= r ** 2:
                pixels[y][x] = color

def gen_icon(size):
    """生成指定尺寸的图标 (地图定位针 风格)"""
    global pixels
    pixels = [[[255, 255, 255] for _ in range(size)] for _ in range(size)]

    primary = [26, 115, 232]  # #1A73E8 蓝色
    white = [255, 255, 255]

    cx, cy = size // 2, size // 2
    big_r = int(size * 0.32)
    small_r = int(size * 0.18)
    tip_h = int(size * 0.22)

    # 圆形主体
    draw_circle(cx, int(cy - size * 0.08), big_r, primary)

    # 内部镂空
    draw_circle(cx, int(cy - size * 0.08), small_r, white)

    # 底部三角尖端
    tip_y = int(cy + size * 0.25)
    base = big_r
    for py in range(int(cy - size * 0.08) + big_r - 5, tip_y + 1):
        progress = (py - (int(cy - size * 0.08) + big_r - 5)) / max(1, tip_y - (int(cy - size * 0.08) + big_r - 5))
        half_w = int(base * (1 - progress))
        for px in range(cx - half_w, cx + half_w + 1):
            if 0 <= px < size and 0 <= py < size:
                pixels[py][px] = primary

    return create_png(size, size, pixels)

# 生成各密度图标
sizes = {
    'mdpi': 48,
    'hdpi': 72,
    'xhdpi': 96,
    'xxhdpi': 144
}

base = 'E:/TEST/NingNingMock/app/src/main/res'

for density, size in sizes.items():
    d = os.path.join(base, f'mipmap-{density}')
    os.makedirs(d, exist_ok=True)

    data = gen_icon(size)
    for name in ['ic_launcher.png', 'ic_launcher_round.png']:
        path = os.path.join(d, name)
        with open(path, 'wb') as f:
            f.write(data)

    print(f'  ✓ {density} ({size}px)')

print('\n图标生成完成！')
