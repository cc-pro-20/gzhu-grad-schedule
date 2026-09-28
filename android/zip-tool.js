// 极简但精确的 zip 写出器（零依赖）。
//
// 为什么要自己写：`jar` 重打包会做两件安装器不能接受的事——
//   1. 把 resources.arsc 压成 deflate（Android 11+ 要求它必须未压缩）
//   2. 破坏 4 字节对齐
// 表现为 vivo/Android 安装器报"兼容性问题"、安装失败。
//
// 这里只做本项目需要的事：按条目原样拷贝或写入，并对指定条目保证
// 「未压缩 + 4 字节对齐」（用 local header 的 extra 字段做 padding）。
const fs = require('fs');
const zlib = require('zlib');

function crc32(buf) {
  let c;
  if (!crc32.table) {
    crc32.table = [];
    for (let n = 0; n < 256; n++) {
      c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      crc32.table[n] = c >>> 0;
    }
  }
  let crc = 0xffffffff;
  for (let i = 0; i < buf.length; i++) crc = crc32.table[(crc ^ buf[i]) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

/** 读出 zip 的全部条目（含原始压缩数据，便于原样拷贝） */
function readZip(zipPath) {
  const buf = fs.readFileSync(zipPath);
  let eocd = -1;
  for (let i = buf.length - 22; i >= 0 && i > buf.length - 22 - 65536; i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('不是有效 zip: ' + zipPath);
  const total = buf.readUInt16LE(eocd + 10);
  let off = buf.readUInt32LE(eocd + 16);

  const entries = [];
  for (let n = 0; n < total; n++) {
    if (buf.readUInt32LE(off) !== 0x02014b50) throw new Error('中央目录项异常 @' + off);
    const flags = buf.readUInt16LE(off + 8);
    const method = buf.readUInt16LE(off + 10);
    const modTime = buf.readUInt16LE(off + 12);
    const modDate = buf.readUInt16LE(off + 14);
    const crc = buf.readUInt32LE(off + 16);
    const compSize = buf.readUInt32LE(off + 20);
    const uncompSize = buf.readUInt32LE(off + 24);
    const nameLen = buf.readUInt16LE(off + 28);
    const extraLen = buf.readUInt16LE(off + 30);
    const commentLen = buf.readUInt16LE(off + 32);
    const externalAttr = buf.readUInt32LE(off + 38);
    const localOff = buf.readUInt32LE(off + 42);
    const name = buf.toString('utf8', off + 46, off + 46 + nameLen);

    const lNameLen = buf.readUInt16LE(localOff + 26);
    const lExtraLen = buf.readUInt16LE(localOff + 28);
    const dataStart = localOff + 30 + lNameLen + lExtraLen;
    const data = buf.subarray(dataStart, dataStart + compSize);
    const isDir = name.endsWith('/');

    entries.push({
      name, method, flags, modTime, modDate, crc,
      compSize, uncompSize, externalAttr, data: Buffer.from(data), isDir,
      // 记录原始压缩方式，未显式覆盖时按原样拷贝
    });
    off += 46 + nameLen + extraLen + commentLen;
  }
  return entries;
}

/** 组装 extra 字段：为满足 4 字节对齐插入 padding（0xCAFE 自定义头） */
function alignExtra(currentOffset, desiredAlign, nameLen) {
  // 数据起始 = currentOffset + 30 + nameLen + extraLen
  const base = currentOffset + 30 + nameLen;
  let need = (desiredAlign - (base % desiredAlign)) % desiredAlign;
  if (need === 0) return Buffer.alloc(0);
  // extra 头固定 4 字节，所以剩余部分作为 padding
  if (need < 4) need += desiredAlign;
  const pad = need - 4;
  const b = Buffer.alloc(need);
  b.writeUInt16LE(0xcafe, 0);
  b.writeUInt16LE(pad, 2);
  return b;
}

/**
 * 写出 zip。
 * @param {Array} entries 条目（见 readZip 的结构；method=0 表示不压缩）
 * @param {string[]} alignStore 需要「未压缩 + 4 字节对齐」的条目名
 */
function writeZip(outPath, entries, alignStore = []) {
  const alignSet = new Set(alignStore);
  const chunks = [];
  const central = [];
  let offset = 0;

  for (const e of entries) {
    if (e.isDir) continue; // 不写目录条目，安装器不需要
    let data = e.data;
    let method = e.method;

    // 需要对齐的条目：强制不压缩
    if (alignSet.has(e.name)) method = 0;

    if (method === 0) {
      // 已解压数据；若原始是压缩的，这里重新算
      if (e.method !== 0) {
        data = zlib.inflateRawSync(e.data);
      }
    } else {
      // 其它条目保持原有压缩数据
      data = e.data;
    }

    const compSize = data.length;
    const uncompSize = method === 0 ? data.length : (e.uncompSize || zlib.inflateRawSync(data).length);
    const crc = method === 0 ? crc32(data) : e.crc;

    const nameBuf = Buffer.from(e.name, 'utf8');
    const extra = alignSet.has(e.name) ? alignExtra(offset, 4, nameBuf.length) : Buffer.alloc(0);

    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);            // version needed
    local.writeUInt16LE(0, 6);             // flags（不用 data descriptor）
    local.writeUInt16LE(method, 8);
    local.writeUInt16LE(e.modTime || 0, 10);
    local.writeUInt16LE(e.modDate || 0, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(compSize, 18);
    local.writeUInt32LE(uncompSize, 22);
    local.writeUInt16LE(nameBuf.length, 26);
    local.writeUInt16LE(extra.length, 28);

    chunks.push(local, nameBuf, extra, data);

    const dataOffset = offset + 30 + nameBuf.length + extra.length;
    if (alignSet.has(e.name) && dataOffset % 4 !== 0) {
      throw new Error(`对齐失败: ${e.name} dataOffset=${dataOffset}`);
    }

    central.push({ ...e, nameBuf, method, crc, compSize, uncompSize, localOffset: offset, extra });
    offset += local.length + nameBuf.length + extra.length + data.length;
  }

  // 中央目录
  const cdStart = offset;
  for (const c of central) {
    const h = Buffer.alloc(46);
    h.writeUInt32LE(0x02014b50, 0);
    h.writeUInt16LE(20, 4);                 // version made by
    h.writeUInt16LE(20, 6);                 // version needed
    h.writeUInt16LE(0, 8);                  // flags
    h.writeUInt16LE(c.method, 10);
    h.writeUInt16LE(c.modTime || 0, 12);
    h.writeUInt16LE(c.modDate || 0, 14);
    h.writeUInt32LE(c.crc, 16);
    h.writeUInt32LE(c.compSize, 20);
    h.writeUInt32LE(c.uncompSize, 24);
    h.writeUInt16LE(c.nameBuf.length, 28);
    h.writeUInt16LE(c.extra.length, 30);
    h.writeUInt16LE(0, 32);                 // comment
    h.writeUInt16LE(0, 34);                 // disk
    h.writeUInt16LE(0, 36);                 // internal attrs
    h.writeUInt32LE(c.externalAttr || 0, 38);
    h.writeUInt32LE(c.localOffset, 42);
    chunks.push(h, c.nameBuf, c.extra);
    offset += h.length + c.nameBuf.length + c.extra.length;
  }
  const cdSize = offset - cdStart;

  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(0, 4);
  eocd.writeUInt16LE(0, 6);
  eocd.writeUInt16LE(central.length, 8);
  eocd.writeUInt16LE(central.length, 10);
  eocd.writeUInt32LE(cdSize, 12);
  eocd.writeUInt32LE(cdStart, 16);
  eocd.writeUInt16LE(0, 20);
  chunks.push(eocd);

  fs.writeFileSync(outPath, Buffer.concat(chunks));
  return central.length;
}

module.exports = { readZip, writeZip, crc32, alignExtra };
