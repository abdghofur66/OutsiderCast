// Server LAN: menyajikan halaman penonton + signaling WebRTC via WebSocket.
const http = require('http');
const fs = require('fs');
const path = require('path');
const { WebSocketServer } = require('ws');

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.svg': 'image/svg+xml' };

function startServer({ port = 8080, viewerDir, onEvent }) {
  const state = { pin: '', host: null, viewers: new Map(), paused: false };
  let nextId = 1;

  const server = http.createServer((req, res) => {
    let p = decodeURIComponent(req.url.split('?')[0]);
    if (p === '/' || p === '') p = '/index.html';
    const file = path.normalize(path.join(viewerDir, p));
    if (!file.startsWith(viewerDir)) { res.writeHead(403); return res.end(); }
    fs.readFile(file, (err, data) => {
      if (err) { res.writeHead(404); return res.end('Tidak ditemukan'); }
      res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-cache' });
      res.end(data);
    });
  });

  const wss = new WebSocketServer({ server, path: '/ws' });
  const send = (ws, msg) => { if (ws && ws.readyState === 1) ws.send(JSON.stringify(msg)); };
  const viewerList = () => [...state.viewers.values()].map(v => ({ id: v.id, name: v.name }));
  const notifyHost = () => { send(state.host, { type: 'viewers', list: viewerList() }); onEvent && onEvent('viewers', viewerList()); };

  wss.on('connection', (ws) => {
    let me = null;
    ws.on('message', (raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'host-hello') {
        // Hanya koneksi dari mesin ini yang boleh jadi host
        const ip = ws._socket.remoteAddress || '';
        if (!/^(::1|127\.|::ffff:127\.)/.test(ip)) return ws.close();
        state.host = ws; me = { role: 'host' };
        return notifyHost();
      }
      if (m.type === 'join') {
        if (state.pin && String(m.pin || '') !== state.pin) return send(ws, { type: 'error', code: 'pin', message: 'PIN salah' });
        const id = String(nextId++);
        me = { role: 'viewer', id, name: String(m.name || 'Penonton ' + id).slice(0, 40), ws };
        state.viewers.set(id, me);
        send(ws, { type: 'joined', id, paused: state.paused });
        send(state.host, { type: 'viewer-join', id, name: me.name });
        return notifyHost();
      }
      if (!me) return;
      if (me.role === 'host') {
        if (m.type === 'signal') { const v = state.viewers.get(m.to); if (v) send(v.ws, { type: 'signal', data: m.data }); }
        else if (m.type === 'set-pin') { state.pin = String(m.pin || ''); }
        else if (m.type === 'pause') { state.paused = !!m.paused; state.viewers.forEach(v => send(v.ws, { type: 'pause', paused: state.paused })); }
        else if (m.type === 'kick') { const v = state.viewers.get(m.id); if (v) { send(v.ws, { type: 'kicked' }); v.ws.close(); } }
        else if (m.type === 'stopped') { state.viewers.forEach(v => send(v.ws, { type: 'stopped' })); }
      } else if (m.type === 'signal') {
        send(state.host, { type: 'signal', from: me.id, data: m.data });
      }
    });
    ws.on('close', () => {
      if (!me) return;
      if (me.role === 'host') state.host = null;
      else { state.viewers.delete(me.id); send(state.host, { type: 'viewer-leave', id: me.id }); notifyHost(); }
    });
  });

  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, '0.0.0.0', () => resolve({ server, port: server.address().port, state }));
  });
}

module.exports = { startServer };
