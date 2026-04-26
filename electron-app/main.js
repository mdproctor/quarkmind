const { app, BrowserWindow, ipcMain } = require('electron');
const { spawn }  = require('child_process');
const path       = require('path');
const http       = require('http');

const PROJECT_DIR = path.join(__dirname, '..');
const PORT        = 8080;
const POLL_MS     = 1500;
const MAX_WAIT_MS = 90_000;

let quarkusProc = null;
let win         = null;

function createWindow() {
  win = new BrowserWindow({
    width:  1440,
    height: 900,
    title:  'QuarkMind — SC2 Replay Viewer',
    backgroundColor: '#0a0a14',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
    },
  });
  win.loadFile(path.join(__dirname, 'loading.html'));
  win.setMenuBarVisibility(false);
}

function startQuarkus() {
  const mvn = process.platform === 'win32' ? 'mvn.cmd' : 'mvn';
  quarkusProc = spawn(mvn, ['quarkus:dev', '-Dquarkus.profile=replay'], {
    cwd:   PROJECT_DIR,
    stdio: 'ignore',            // never write to any file
    env:   { ...process.env },
    detached: false,
  });
  quarkusProc.on('error', err => {
    win?.webContents.send('status', `Maven error: ${err.message}`);
  });
}

function isServerReady(cb) {
  const req = http.get(`http://localhost:${PORT}/qa/current-map`, res => {
    cb(res.statusCode === 200);
  });
  req.on('error', () => cb(false));
  req.setTimeout(1000, () => { req.destroy(); cb(false); });
}

function pollUntilReady() {
  const started = Date.now();
  const tick = () => {
    isServerReady(ready => {
      if (ready) {
        win?.webContents.send('status', 'Server ready — loading visualizer…');
        setTimeout(() => win?.loadURL(`http://localhost:${PORT}/visualizer.html`), 600);
      } else if (Date.now() - started > MAX_WAIT_MS) {
        win?.webContents.send('status', 'Timed out waiting for server.');
      } else {
        const elapsed = Math.floor((Date.now() - started) / 1000);
        win?.webContents.send('status', `Starting server… ${elapsed}s`);
        setTimeout(tick, POLL_MS);
      }
    });
  };
  tick();
}

function killQuarkus() {
  if (!quarkusProc) return;
  try {
    // Kill the process group so Maven's child JVM also dies
    if (process.platform !== 'win32') {
      process.kill(-quarkusProc.pid, 'SIGTERM');
    } else {
      quarkusProc.kill();
    }
  } catch (_) {
    quarkusProc.kill();
  }
  quarkusProc = null;
}

app.whenReady().then(() => {
  createWindow();
  startQuarkus();
  pollUntilReady();
});

app.on('window-all-closed', () => {
  killQuarkus();
  app.quit();
});

app.on('before-quit', killQuarkus);
