const { app, BrowserWindow } = require('electron');
const { spawn } = require('child_process');
const http = require('http');
const path = require('path');

const QUARKUS_URL    = 'http://localhost:8080';
const HEALTH_URL     = `${QUARKUS_URL}/q/health/live`;
const VISUALIZER_URL = `${QUARKUS_URL}/visualizer.html`;
const MAX_WAIT_MS    = 30000;
const POLL_MS        = 500;

let quarkusProcess = null;
let mainWindow     = null;

function waitForQuarkus() {
    return new Promise((resolve, reject) => {
        const deadline = Date.now() + MAX_WAIT_MS;
        let firstError = true;
        function poll() {
            if (Date.now() > deadline) {
                return reject(new Error('Quarkus did not start within 30 seconds'));
            }
            http.get(HEALTH_URL, res => {
                if (res.statusCode === 200) resolve();
                else setTimeout(poll, POLL_MS);
            }).on('error', err => {
                if (firstError) {
                    console.log(`[ELECTRON] Waiting for Quarkus (${err.code})...`);
                    firstError = false;
                }
                setTimeout(poll, POLL_MS);
            });
        }
        setTimeout(poll, POLL_MS);
    });
}

function startQuarkus() {
    if (process.env.DEV_MODE) {
        console.log('[ELECTRON] Dev mode — assuming Quarkus already running on :8080');
        return Promise.resolve();
    }
    const jar = path.join(__dirname, '..', 'target', 'quarkmind-agent-1.0.0-SNAPSHOT-runner.jar');
    quarkusProcess = spawn('java', ['-jar', jar], {
        env: { ...process.env, 'quarkus.profile': 'emulated' }
    });
    quarkusProcess.stdout.on('data', d => process.stdout.write('[QUARKUS] ' + d));
    quarkusProcess.stderr.on('data', d => process.stderr.write('[QUARKUS] ' + d));
    quarkusProcess.on('error', err => console.error('[ELECTRON] Failed to spawn Quarkus:', err.message));
    console.log('[ELECTRON] Waiting for Quarkus...');
    return waitForQuarkus();
}

function createWindow() {
    mainWindow = new BrowserWindow({
        width: 900,
        height: 740,
        autoHideMenuBar: true,
        title: 'QuarkMind',
        backgroundColor: '#1a1a2e'
    });
    mainWindow.loadURL(VISUALIZER_URL);
    mainWindow.on('closed', () => { mainWindow = null; });
}

app.whenReady().then(async () => {
    try {
        await startQuarkus();
        createWindow();
    } catch (e) {
        console.error('[ELECTRON] Startup failed:', e.message);
        if (quarkusProcess) {
            quarkusProcess.kill();
        }
        app.quit();
    }
});

app.on('before-quit', () => {
    if (quarkusProcess) {
        console.log('[ELECTRON] Shutting down Quarkus...');
        quarkusProcess.kill();
    }
});

app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit();
});
