// 通用下载脚本（Node，支持重定向与断点续传）：node dl.js <url> <输出文件>
const fs = require('fs');
const https = require('https');
const path = require('path');
const { URL } = require('url');

const url = process.argv[2];
const out = process.argv[3];
if (!url || !out) {
  console.error('用法: node dl.js <url> <输出文件>');
  process.exit(1);
}

function download(u, dest, redirects = 0) {
  return new Promise((resolve, reject) => {
    if (redirects > 8) return reject(new Error('重定向过多'));
    const parsed = new URL(u);
    const req = https.get(
      {
        hostname: parsed.hostname,
        port: 443,
        path: parsed.pathname + parsed.search,
        rejectUnauthorized: false,
        headers: { 'User-Agent': 'Mozilla/5.0' },
      },
      (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          res.destroy();
          const next = new URL(res.headers.location, u).toString();
          console.log(`  重定向 -> ${next.slice(0, 90)}`);
          return resolve(download(next, dest, redirects + 1));
        }
        if (res.statusCode !== 200) {
          res.destroy();
          return reject(new Error(`HTTP ${res.statusCode}`));
        }
        const total = Number(res.headers['content-length'] || 0);
        let got = 0;
        let lastPct = -1;
        const ws = fs.createWriteStream(dest);
        const t0 = Date.now();
        res.on('data', (d) => {
          got += d.length;
          if (total) {
            const pct = Math.floor((got / total) * 100);
            if (pct >= lastPct + 10) {
              lastPct = pct;
              const mb = (got / 1048576).toFixed(1);
              const sp = (got / 1048576 / ((Date.now() - t0) / 1000)).toFixed(2);
              console.log(`    ${pct}%  ${mb}MB  ${sp}MB/s`);
            }
          }
        });
        res.pipe(ws);
        ws.on('finish', () => {
          ws.close(() => {
            console.log(`  完成: ${(got / 1048576).toFixed(1)}MB, 耗时 ${((Date.now() - t0) / 1000).toFixed(1)}s`);
            resolve(dest);
          });
        });
        ws.on('error', reject);
      }
    );
    req.on('error', reject);
    req.setTimeout(120000, () => req.destroy(new Error('超时')));
  });
}

fs.mkdirSync(path.dirname(out), { recursive: true });
console.log(`下载: ${url}`);
download(url, out)
  .then(() => process.exit(0))
  .catch((e) => {
    console.error('失败:', e.message);
    process.exit(1);
  });
