/**
 * C3 AGNOS 启动图引擎 —— 纯 JS 版（移除 sharp，使用浏览器 Canvas）
 *
 * 第一屏 BMP:   1080x2160 24-bit 未压缩 RGB BMP (精确 6,998,454 字节)
 * Splash 分区:  精确 34,226,176 字节 (BMP 从偏移 0x4000 / 16384 开始)
 * 第二屏:       /usr/comma/bg.jpg  1080x2160 JPEG
 */

export const SPLASH_IMG_SIZE = 34226176;
export const BMP_EXACT_SIZE = 6998454;
export const BMP_OFFSET = 0x4000; // 16384 bytes
export const IMG_W = 1080;
export const IMG_H = 2160;

/**
 * 用 Canvas 把任意图片缩放到指定尺寸，返回 { data: Uint8ClampedArray(RGBA), width, height }
 * fit: 'contain'（等比留黑） | 'cover'（拉伸裁剪）
 */
export function drawToCanvas(img, width = IMG_W, height = IMG_H, fit = 'contain') {
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');

  // 背景黑
  ctx.fillStyle = '#000000';
  ctx.fillRect(0, 0, width, height);

  const iw = img.width;
  const ih = img.height;
  const scale = fit === 'cover'
    ? Math.max(width / iw, height / ih)
    : Math.min(width / iw, height / ih);

  const dw = iw * scale;
  const dh = ih * scale;
  const dx = (width - dw) / 2;
  const dy = (height - dh) / 2;

  ctx.drawImage(img, dx, dy, dw, dh);

  const imageData = ctx.getImageData(0, 0, width, height);
  return { data: imageData.data, width, height };
}

/**
 * 把 RGBA 像素转换成 24-bit 未压缩 BMP (Bottom-Up BGR)
 * 精确输出 6,998,454 字节
 */
export function rgbaToBmp24(rgba, width = IMG_W, height = IMG_H) {
  const bytesPerPixel = 3;
  const rowStride = width * bytesPerPixel; // 1080 * 3 = 3240 (已是 4 字节对齐)
  const imageSize = rowStride * height;     // 6,998,400
  const fileSize = 54 + imageSize;          // 6,998,454

  const bmp = new Uint8Array(fileSize);

  // BITMAPFILEHEADER (14 bytes)
  bmp[0] = 0x42; // 'B'
  bmp[1] = 0x4D; // 'M'
  writeU32LE(bmp, 2, fileSize);   // bfSize
  writeU16LE(bmp, 6, 0);          // bfReserved1
  writeU16LE(bmp, 8, 0);          // bfReserved2
  writeU32LE(bmp, 10, 54);        // bfOffBits

  // BITMAPINFOHEADER (40 bytes)
  writeU32LE(bmp, 14, 40);        // biSize
  writeI32LE(bmp, 18, width);     // biWidth
  writeI32LE(bmp, 22, height);    // biHeight (正数 = bottom-up)
  writeU16LE(bmp, 26, 1);         // biPlanes
  writeU16LE(bmp, 28, 24);        // biBitCount
  writeU32LE(bmp, 30, 0);         // biCompression = BI_RGB
  writeU32LE(bmp, 34, imageSize); // biSizeImage
  writeI32LE(bmp, 38, 2835);      // biXPelsPerMeter
  writeI32LE(bmp, 42, 2835);      // biYPelsPerMeter
  writeU32LE(bmp, 46, 0);         // biClrUsed
  writeU32LE(bmp, 50, 0);         // biClrImportant

  // 像素: Canvas 是 Top-Down RGBA, BMP 需要 Bottom-Up BGR
  let dest = 54;
  for (let y = height - 1; y >= 0; y--) {
    const rowStart = y * width * 4;
    for (let x = 0; x < width; x++) {
      const s = rowStart + x * 4;
      const r = rgba[s];
      const g = rgba[s + 1];
      const b = rgba[s + 2];
      bmp[dest++] = b;
      bmp[dest++] = g;
      bmp[dest++] = r;
    }
  }

  if (bmp.length !== BMP_EXACT_SIZE) {
    throw new Error(`BMP 尺寸异常! 期望 ${BMP_EXACT_SIZE}, 实际 ${bmp.length}`);
  }
  return bmp;
}

/**
 * 把 6,998,454 字节 BMP 合成为 34,226,176 字节 splash_custom.img
 */
export function buildSplashCustomImg(bmp) {
  if (bmp.length !== BMP_EXACT_SIZE) {
    throw new Error(`BMP 尺寸无效: ${bmp.length}, 必须精确为 ${BMP_EXACT_SIZE} 字节`);
  }
  const splash = new Uint8Array(SPLASH_IMG_SIZE); // 全 0
  // Qualcomm C3 Splash 头
  const magic = 'SPLASH!!';
  for (let i = 0; i < magic.length; i++) splash[i] = magic.charCodeAt(i);
  writeU32LE(splash, 8, IMG_W);
  writeU32LE(splash, 12, IMG_H);
  writeU32LE(splash, 16, BMP_OFFSET);
  writeU32LE(splash, 20, BMP_EXACT_SIZE);
  splash.set(bmp, BMP_OFFSET);

  if (splash.length !== SPLASH_IMG_SIZE) {
    throw new Error(`splash_custom.img 尺寸无效: ${splash.length}, 必须为 ${SPLASH_IMG_SIZE}`);
  }
  return splash;
}

/**
 * 处理第一屏：图片 -> 1080x2160 BMP -> splash_custom.img
 */
export function processFirstScreen(img, fit = 'contain') {
  const { data } = drawToCanvas(img, IMG_W, IMG_H, fit);
  const bmp = rgbaToBmp24(data, IMG_W, IMG_H);
  const splash = buildSplashCustomImg(bmp);
  return { bmp, splash };
}

/**
 * 处理第二屏：图片 -> 1080x2160 JPEG (quality 95)
 * 返回 ArrayBuffer (JPEG)
 */
export function processSecondScreen(img) {
  const canvas = document.createElement('canvas');
  canvas.width = IMG_W;
  canvas.height = IMG_H;
  const ctx = canvas.getContext('2d');
  ctx.fillStyle = '#000000';
  ctx.fillRect(0, 0, IMG_W, IMG_H);
  // cover 裁剪
  const iw = (img.naturalWidth || img.width);
  const ih = (img.naturalHeight || img.height);
  const scale = Math.max(IMG_W / iw, IMG_H / ih);
  const dw = iw * scale, dh = ih * scale;
  ctx.drawImage(img, (IMG_W - dw) / 2, (IMG_H - dh) / 2, dw, dh);
  return new Promise((resolve, reject) => {
    canvas.toBlob(
      (blob) => {
        if (!blob) return reject(new Error('JPEG 编码失败'));
        blob.arrayBuffer().then(resolve).catch(reject);
      },
      'image/jpeg',
      0.95
    );
  });
}

/* ---------- 小端写入工具 ---------- */
function writeU16LE(a, o, v) { a[o] = v & 0xff; a[o + 1] = (v >>> 8) & 0xff; }
function writeU32LE(a, o, v) {
  a[o] = v & 0xff; a[o + 1] = (v >>> 8) & 0xff;
  a[o + 2] = (v >>> 16) & 0xff; a[o + 3] = (v >>> 24) & 0xff;
}
function writeI32LE(a, o, v) { writeU32LE(a, o, v < 0 ? v + 0x100000000 : v); }