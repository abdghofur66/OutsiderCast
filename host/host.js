const $ = (s) => document.querySelector(s);
let ws, stream = null, paused = false, info;
const peers = new Map(); // viewerId -> RTCPeerConnection
let viewers = [];

// ---------- Info jaringan & QR ----------
async function loadInfo() {
  info = await window.cast.info();
  const sel = $('#urlSel');
  sel.innerHTML = '';
  if (!info.urls.length) { $('#url').textContent = 'Tidak terhubung ke jaringan'; return; }
  info.urls.forEach((u, i) => sel.add(new Option(`${u.address} (${u.name})`, i)));
  sel.hidden = info.urls.length < 2;
  showUrl(0, info.qr);
  if (info.platform === 'darwin') $('#audioWrap').title = 'Mac: butuh macOS 13 (Ventura) ke atas';
}
async function showUrl(i, qr) {
  const u = info.urls[i].url;
  $('#url').textContent = u;
  $('#qr').src = qr || await window.cast.qr(u);
}
$('#urlSel').onchange = (e) => showUrl(+e.target.value);

// ---------- Signaling ----------
function connect() {
  ws = new WebSocket(`ws://127.0.0.1:${info.port}/ws`);
  ws.onopen = () => send({ type: 'host-hello' });
  ws.onclose = () => setTimeout(connect, 1000);
  ws.onmessage = async (ev) => {
    const m = JSON.parse(ev.data);
    if (m.type === 'viewers') renderViewers(m.list);
    else if (m.type === 'viewer-join') { if (stream) offerTo(m.id); }
    else if (m.type === 'viewer-leave') closePeer(m.id);
    else if (m.type === 'signal') {
      const pc = peers.get(m.from); if (!pc) return;
      if (m.data.sdp) await pc.setRemoteDescription(tuneSdp(m.data.sdp));
      else if (m.data.candidate) await pc.addIceCandidate(m.data.candidate).catch(() => {});
    }
  };
}
const send = (o) => ws && ws.readyState === 1 && ws.send(JSON.stringify(o));

// ---------- WebRTC ----------
async function offerTo(id) {
  closePeer(id);
  const pc = new RTCPeerConnection({ iceServers: [] }); // murni LAN, tanpa STUN/TURN
  peers.set(id, pc);
  stream.getTracks().forEach(t => pc.addTrack(t, stream));
  pc.onicecandidate = (e) => e.candidate && send({ type: 'signal', to: id, data: { candidate: e.candidate } });
  pc.onconnectionstatechange = () => { if (['failed', 'closed'].includes(pc.connectionState)) closePeer(id); };
  const offer = await pc.createOffer();
  await pc.setLocalDescription(offer);
  send({ type: 'signal', to: id, data: { sdp: pc.localDescription } });
  applyQuality();
}
// Mulai langsung di bitrate tinggi (bawaan WebRTC mulai pelan lalu naik bertahap),
// dan naikkan kualitas suara Opus.
function tuneSdp(d) {
  const kbps = Math.round(curBitrate() / 1000);
  const sdp = d.sdp.replace(/(a=fmtp:\d+ [^\r\n]+)/g, (l) =>
    /minptime|useinbandfec/.test(l) ? l + ';stereo=1;maxaveragebitrate=128000'
      : l + `;x-google-start-bitrate=${Math.round(kbps * 2 / 3)};x-google-min-bitrate=${Math.round(kbps / 4)}`);
  return { type: d.type, sdp };
}
function curBitrate() {
  const q = $('#quality').value, n = Math.max(1, peers.size);
  return ({ fast: 5_000_000, high: 4_000_000, medium: 1_800_000, low: 600_000 })[q] || Math.min(4_000_000, Math.max(400_000, 25_000_000 / n));
}

function closePeer(id) { const pc = peers.get(id); if (pc) { pc.close(); peers.delete(id); } }

function applyQuality() {
  const q = $('#quality').value, n = Math.max(1, peers.size);
  let p;
  if (q === 'fast') p = { maxBitrate: 5_000_000, maxFramerate: 60, scale: 1, smooth: true };
  else if (q === 'high') p = { maxBitrate: 4_000_000, maxFramerate: 30, scale: 1 };
  else if (q === 'medium') p = { maxBitrate: 1_800_000, maxFramerate: 20, scale: 1 };
  else if (q === 'low') p = { maxBitrate: 600_000, maxFramerate: 10, scale: 1.5 };
  else { // otomatis: bagi anggaran ~25 Mbps ke semua penonton
    const br = Math.min(4_000_000, Math.max(400_000, 25_000_000 / n));
    p = { maxBitrate: br, maxFramerate: n > 20 ? 12 : n > 8 ? 20 : 30, scale: n > 25 ? 1.5 : 1 };
  }
  peers.forEach(pc => pc.getSenders().forEach(s => {
    if (!s.track || s.track.kind !== 'video') return;
    const prm = s.getParameters();
    if (!prm.encodings || !prm.encodings.length) prm.encodings = [{}];
    prm.encodings[0].maxBitrate = p.maxBitrate;
    prm.encodings[0].maxFramerate = p.maxFramerate;
    prm.encodings[0].scaleResolutionDownBy = p.scale;
    // Responsif: jaga kelancaran gerak. Lainnya: jaga ketajaman teks presentasi.
    prm.degradationPreference = p.smooth ? 'maintain-framerate' : 'maintain-resolution';
    s.setParameters(prm).catch(() => {});
  }));
}
$('#quality').onchange = () => {
  applyQuality();
  const vt = stream && stream.getVideoTracks()[0];
  if (vt) {
    const fast = $('#quality').value === 'fast';
    vt.contentHint = fast ? 'motion' : 'detail';
    vt.applyConstraints({ frameRate: fast ? 60 : 30 }).catch(() => {});
  }
};

// ---------- Pilih sumber ----------
let srcCache = [], tab = 'screen';
$('#btnPick').onclick = async () => {
  $('#picker').hidden = false;
  $('#srcGrid').innerHTML = '<p class="muted">Memuat…</p>';
  srcCache = await window.cast.sources();
  renderSources();
};
$('#pickClose').onclick = () => $('#picker').hidden = true;
document.querySelectorAll('.tabs button').forEach(b => b.onclick = () => {
  tab = b.dataset.t;
  document.querySelectorAll('.tabs button').forEach(x => x.classList.toggle('on', x === b));
  renderSources();
});
function renderSources() {
  const g = $('#srcGrid'); g.innerHTML = '';
  srcCache.filter(s => s.screen === (tab === 'screen')).forEach(s => {
    const d = document.createElement('button'); d.className = 'src';
    d.innerHTML = `<img src="${s.thumb}"><span></span>`;
    d.querySelector('span').textContent = s.name;
    d.onclick = () => start(s.id);
    g.appendChild(d);
  });
  if (!g.children.length) g.innerHTML = '<p class="muted">Tidak ada.</p>';
}

async function start(id) {
  $('#picker').hidden = true;
  await window.cast.choose(id, $('#chkAudio').checked);
  const old = stream;
  try {
    const fps = $('#quality').value === 'fast' ? 60 : 30;
    try {
      stream = await navigator.mediaDevices.getDisplayMedia({ video: { frameRate: fps }, audio: $('#chkAudio').checked });
    } catch (e1) {
      // Suara sistem tidak didukung di perangkat ini: coba lagi tanpa suara
      if (!$('#chkAudio').checked) throw e1;
      await window.cast.choose(id, false);
      stream = await navigator.mediaDevices.getDisplayMedia({ video: { frameRate: fps }, audio: false });
      alertBox('Suara sistem tidak bisa ditangkap di perangkat ini, jadi hanya gambar yang dibagikan.');
    }
  } catch (e) {
    alertBox('Gagal mengambil layar. Di Mac, izinkan "Screen Recording" untuk OutsiderCast di System Settings → Privacy & Security, lalu buka ulang aplikasi.');
    stream = old; return;
  }
  const vt = stream.getVideoTracks()[0];
  vt.contentHint = $('#quality').value === 'fast' ? 'motion' : 'detail';
  vt.onended = stop;
  if (old) old.getTracks().forEach(t => t.stop());
  $('#preview').srcObject = stream;
  $('#previewWrap').classList.remove('empty');
  $('#btnPick').textContent = '🔄 Ganti layar';
  $('#btnPause').disabled = $('#btnStop').disabled = false;
  setPaused(false);
  viewers.forEach(v => offerTo(v.id)); // kirim (ulang) ke semua penonton
}

function stop() {
  if (!stream) return;
  stream.getTracks().forEach(t => t.stop());
  stream = null;
  peers.forEach((_, id) => closePeer(id));
  send({ type: 'stopped' });
  $('#preview').srcObject = null;
  $('#previewWrap').classList.add('empty');
  $('#btnPick').textContent = '🖥️ Pilih layar & mulai';
  $('#btnPause').disabled = $('#btnStop').disabled = true;
  setPaused(false); setStatus();
}
$('#btnStop').onclick = stop;

function setPaused(v) {
  paused = v;
  if (stream) stream.getTracks().forEach(t => t.enabled = !v);
  send({ type: 'pause', paused: v });
  $('#pausedBadge').hidden = !v;
  $('#btnPause').textContent = v ? '▶ Lanjutkan' : '⏸ Jeda';
  setStatus();
}
$('#btnPause').onclick = () => setPaused(!paused);

function setStatus() {
  const s = $('#status');
  s.className = 'pill' + (stream ? (paused ? ' paused' : ' live') : '');
  s.textContent = stream ? (paused ? 'Dijeda' : '● Sedang berbagi') : 'Belum berbagi';
}

// ---------- Penonton ----------
function renderViewers(list) {
  viewers = list;
  $('#count').textContent = list.length;
  const ul = $('#viewers'); ul.innerHTML = '';
  if (!list.length) { ul.innerHTML = '<li class="muted">Belum ada penonton</li>'; return; }
  list.forEach(v => {
    const li = document.createElement('li');
    li.innerHTML = '<span></span><button title="Keluarkan">Keluarkan</button>';
    li.querySelector('span').textContent = v.name;
    li.querySelector('button').onclick = () => send({ type: 'kick', id: v.id });
    ul.appendChild(li);
  });
  applyQuality();
}

$('#btnPin').onclick = () => {
  const pin = $('#pin').value.replace(/\D/g, '');
  $('#pin').value = pin;
  send({ type: 'set-pin', pin });
  $('#btnPin').textContent = pin ? 'PIN aktif ✓' : 'Tanpa PIN';
  setTimeout(() => $('#btnPin').textContent = 'Simpan', 1800);
};

function alertBox(msg) {
  const m = document.createElement('div'); m.className = 'modal';
  m.innerHTML = '<div class="sheet" style="max-width:440px"><p></p><button class="primary">OK</button></div>';
  m.querySelector('p').textContent = msg;
  m.querySelector('button').onclick = () => m.remove();
  document.body.appendChild(m);
}

loadInfo().then(connect);
