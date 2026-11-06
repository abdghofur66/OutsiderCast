const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('cast', {
  info: () => ipcRenderer.invoke('info'),
  qr: (url) => ipcRenderer.invoke('qr', url),
  sources: () => ipcRenderer.invoke('sources'),
  choose: (id, audio) => ipcRenderer.invoke('choose', id, audio)
});
