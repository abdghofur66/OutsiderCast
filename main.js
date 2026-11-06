const { app, BrowserWindow, ipcMain, desktopCapturer, session, shell } = require('electron');
const path = require('path');
const os = require('os');
const QRCode = require('qrcode');
const { startServer } = require('./server');

let win, srv, chosenSourceId = null, wantAudio = false;

function lanIPs() {
  const out = [];
  for (const [name, list] of Object.entries(os.networkInterfaces())) {
    for (const a of list || []) {
      if (a.family === 'IPv4' && !a.internal && !/^169\.254\./.test(a.address)) out.push({ name, address: a.address });
    }
  }
  // Prioritaskan alamat jaringan rumah/kantor umum
  return out.sort((x, y) => (/^192\.168\./.test(y.address) - /^192\.168\./.test(x.address)));
}

async function boot() {
  for (const p of [8080, 8081, 8090, 0]) {
    try { srv = await startServer({ port: p, viewerDir: path.join(__dirname, 'viewer') }); break; } catch (e) { /* port dipakai, coba berikutnya */ }
  }

  session.defaultSession.setDisplayMediaRequestHandler(async (_req, cb) => {
    const sources = await desktopCapturer.getSources({ types: ['screen', 'window'] });
    const src = sources.find(s => s.id === chosenSourceId) || sources[0];
    // Suara sistem (loopback): Windows, dan Mac dengan macOS 13+ (ScreenCaptureKit)
    cb({ video: src, audio: wantAudio && ['win32', 'darwin'].includes(process.platform) ? 'loopback' : undefined });
  });

  win = new BrowserWindow({
    width: 1100, height: 760, minWidth: 820, minHeight: 600,
    title: 'OutsiderCast', backgroundColor: '#0f172a',
    icon: path.join(__dirname, 'build', 'icon.png'),
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true }
  });
  win.setMenuBarVisibility(false);
  win.loadFile(path.join(__dirname, 'host', 'index.html'));
  win.webContents.setWindowOpenHandler(({ url }) => { shell.openExternal(url); return { action: 'deny' }; });
}

ipcMain.handle('info', async () => {
  const ips = lanIPs();
  const urls = ips.map(i => ({ ...i, url: `http://${i.address}:${srv.port}` }));
  const qr = urls[0] ? await QRCode.toDataURL(urls[0].url, { margin: 1, width: 260 }) : null;
  return { port: srv.port, urls, qr, platform: process.platform };
});
ipcMain.handle('qr', (_e, url) => QRCode.toDataURL(url, { margin: 1, width: 260 }));
ipcMain.handle('sources', async () => {
  const s = await desktopCapturer.getSources({ types: ['screen', 'window'], thumbnailSize: { width: 320, height: 180 } });
  return s.map(x => ({ id: x.id, name: x.name, thumb: x.thumbnail.toDataURL(), screen: x.id.startsWith('screen') }));
});
ipcMain.handle('choose', (_e, id, audio) => { chosenSourceId = id; wantAudio = !!audio; return true; });

app.whenReady().then(boot);
app.on('window-all-closed', () => app.quit());
