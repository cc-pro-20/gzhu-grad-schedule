// 生成启动图标 PNG（不依赖任何图像库：手写 PNG 编码）
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

function crc32(buf) {
  let c;
  const table = [];
  for (let n = 0; n < 256; n++) {
    c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  let crc = 0xffffffff;
  for (let i = 0; i < buf.length; i++) crc = table[(crc ^ buf[i]) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const typeBuf = Buffer.from(type, 'ascii');
  const body = Buffer.concat([typeBuf, data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body), 0);
  return Buffer.concat([len, body, crc]);
}

function png(width, height, rgba) {
  const sig = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;   // bit depth
  ihdr[9] = 6;   // RGBA
  ihdr[10] = 0;  // compression
  ihdr[11] = 0;  // filter
  ihdr[12] = 0;  // interlace
  // 每行前置 filter 字节 0
  const raw = Buffer.alloc((width * 4 + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (width * 4 + 1)] = 0;
    rgba.copy(raw, y * (width * 4 + 1) + 1, y * width * 4, (y + 1) * width * 4);
  }
  return Buffer.concat([sig, chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
}

// ---- 绘制：圆角方块 + 白色「课」字（用位图点阵描述汉字太复杂，改用简洁的网格符号）----
// 设计：绿底圆角方形，中间 2x3 白色小格 + 一条高亮列，象征课表
function draw(size) {
  const rgba = Buffer.alloc(size * size * 4);
  const GREEN = [15, 110, 86];
  const LIGHT = [255, 255, 255];
  const ACCENT = [126, 217, 187];
  const r = size * 0.22;
  const set = (x, y, c, a = 255) => {
    if (x < 0 || y < 0 || x >= size || y >= size) return;
    const i = (y * size + x) * 4;
    rgba[i] = c[0]; rgba[i + 1] = c[1]; rgba[i + 2] = c[2]; rgba[i + 3] = a;
  };
  // 圆角矩形底
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const dx = Math.max(r - x, x - (size - 1 - r), 0);
      const dy = Math.max(r - y, y - (size - 1 - r), 0);
      const inside = Math.sqrt(dx * dx + dy * dy) <= r;
      if (inside) set(x, y, GREEN);
    }
  }
  // 课表网格：3 列 x 4 行
  const pad = size * 0.2;
  const gx = size - pad * 2;
  const gy = size - pad * 2;
  const cols = 3, gridRows = 4;
  const cellW = gx / cols, cellH = gy / gridRows;
  const gap = Math.max(1, Math.round(size * 0.028));
  for (let cIdx = 0; cIdx < cols; cIdx++) {
    for (let rIdx = 0; rIdx < gridRows; rIdx++) {
      const accent = cIdx === 1 && rIdx >= 1 && rIdx <= 2;
      const color = accent ? ACCENT : LIGHT;
      const x0 = Math.round(pad + cIdx * cellW) + gap;
      const y0 = Math.round(pad + rIdx * cellH) + gap;
      const x1 = Math.round(pad + (cIdx + 1) * cellW) - gap;
      const y1 = Math.round(pad + (rIdx + 1) * cellH) - gap;
      for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) set(x, y, color);
    }
  }
  return rgba;
}

const res = path.join(__dirname, 'app', 'res');
const sizes = [
  ['mipmap-mdpi', 48],
  ['mipmap-hdpi', 72],
  ['mipmap-xhdpi', 96],
  ['mipmap-xxhdpi', 144],
  ['mipmap-xxxhdpi', 192],
];
for (const [dir, size] of sizes) {
  const out = path.join(res, dir);
  fs.mkdirSync(out, { recursive: true });
  fs.writeFileSync(path.join(out, 'ic_launcher.png'), png(size, size, draw(size)));
  console.log(`  ${dir}/ic_launcher.png  ${size}x${size}`);
}
console.log('图标生成完成');
