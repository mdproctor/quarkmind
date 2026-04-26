const { app, BrowserWindow } = require('electron');
const { spawn } = require('child_process');
const path      = require('path');
const http      = require('http');

const PROJECT_DIR = path.join(__dirname, '..');
const JAR         = path.join(PROJECT_DIR, 'target', 'quarkus-app', 'quarkus-run.jar');
const PORT        = 8080;
const POLL_MS     = 1000;
const MAX_WAIT_MS = 60_000;

let javaProc = null;
let win      = null;

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

function startServer() {
  javaProc = spawn('java', [
    '-jar', JAR,
    '-Dquarkus.profile=replay',
  ], {
    cwd:      PROJECT_DIR,
    stdio:    'ignore',   // never write to any file
    env:      { ...process.env },
    detached: false,
  });
  javaProc.on('error', err => {
    win?.webContents.send('status', `Java error: ${err.message}`);
  });
}

// Poll /qa/replay/status — waits for totalLoops > 0 which means the replay
// is fully loaded and the game loop has run at least one tick.
function isReplayReady(cb) {
  const req = http.get(`http://localhost:${PORT}/qa/replay/status`, res => {
    if (res.statusCode !== 200) { cb(false); return; }
    let body = '';
    res.on('data', d => body += d);
    res.on('end', () => {
      try {
        const json = JSON.parse(body);
        cb(json.totalLoops > 0);
      } catch { cb(false); }
    });
  });
  req.on('error', () => cb(false));
  req.setTimeout(1000, () => { req.destroy(); cb(false); });
}

function pollUntilReady() {
  const started = Date.now();
  const tick = () => {
    isReplayReady(ready => {
      if (ready) {
        win?.webContents.send('status', 'Replay loaded — opening visualizer…');
        setTimeout(() => win?.loadURL(`http://localhost:${PORT}/visualizer.html`), 400);
      } else if (Date.now() - started > MAX_WAIT_MS) {
        win?.webContents.send('status', 'Timed out. Is Java installed?');
      } else {
        const elapsed = Math.floor((Date.now() - started) / 1000);
        win?.webContents.send('status', `Loading replay… ${elapsed}s`);
        setTimeout(tick, POLL_MS);
      }
    });
  };
  tick();
}

function killServer() {
  if (!javaProc) return;
  try { javaProc.kill('SIGTERM'); } catch (_) {}
  javaProc = null;
}

app.whenReady().then(() => {
  createWindow();
  startServer();
  pollUntilReady();
});

app.on('window-all-closed', () => { killServer(); app.quit(); });
app.on('before-quit', killServer);
