const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
  onStatus: cb => ipcRenderer.on('status', (_e, msg) => cb(msg)),
});
