// visualizer.js — QuarkMind 3D visualizer (Three.js r128)

const TILE = 0.7;
const RECONNECT_MS = 2000;

let GRID_W = 64, GRID_H = 64;
let HALF_W, HALF_H;
let camTheta = Math.PI*0.25, camPhi = Math.PI/3.5, camDist = 30;
const camTarget = new THREE.Vector3(0, 0, 0);
let tTheta = camTheta, tPhi = camPhi, tDist = camDist;

let renderer, scene, camera;
let wsConnected = false;
let group2d, group3d;

const unitSprites    = new Map();
const unit3dMeshes   = new Map();
const enemySprites   = new Map();
const enemy3dMeshes  = new Map();
const buildingMeshes = new Map();
const geyserMeshes   = new Map();
const stagingSprites = new Map();
const fogPlanes      = new Map();
const prevPositions  = new Map();
const unitFacings    = new Map();

let mGround, mWall, mHigh, mRamp, lineMat;
function initMaterials() {
  mGround = new THREE.MeshLambertMaterial({ color: 0x1a2233 });
  mWall   = new THREE.MeshLambertMaterial({ color: 0x2e4055 });
  mHigh   = new THREE.MeshLambertMaterial({ color: 0x3a3020 });
  mRamp   = new THREE.MeshLambertMaterial({ color: 0x2a3a44 });
  lineMat = new THREE.LineBasicMaterial({ color: 0x1e2a3a, transparent: true, opacity: 0.4 });
}

let terrainLoaded = false;

window.__test = {
  threeReady:    () => !!renderer,
  terrainReady:  () => terrainLoaded,
  wsConnected:   () => wsConnected,
  hudText:       () => document.getElementById('hud')?.textContent ?? '',
  unitCount:     () => unitSprites.size,
  enemyCount:    () => enemySprites.size,
  buildingCount: () => buildingMeshes.size,
  stagingCount:  () => stagingSprites.size,
  geyserCount:   () => geyserMeshes.size,
  fogOpacity:    (x, z) => {
    const p = fogPlanes.get(`${x},${z}`);
    return p ? (p.visible ? p.material.opacity : 0) : -1;
  },
  fogVisible:    (x, z) => fogPlanes.get(`${x},${z}`)?.visible ?? false,
  spriteCount: prefix => {
    if (prefix === 'unit')     return unitSprites.size;
    if (prefix === 'enemy')    return enemySprites.size;
    if (prefix === 'building') return buildingMeshes.size;
    if (prefix === 'geyser')   return geyserMeshes.size;
    if (prefix === 'staging')  return stagingSprites.size;
    return 0;
  },
  sprite: key => {
    // key format: "unit:tag", "enemy:tag", "building:tag", "geyser:tag", "staging:tag"
    const [prefix, tag] = key.split(':');
    let obj = null;
    if (prefix === 'unit')     obj = unitSprites.get(tag);
    if (prefix === 'enemy')    obj = enemySprites.get(tag);
    if (prefix === 'building') obj = buildingMeshes.get(tag);
    if (prefix === 'geyser')   obj = geyserMeshes.get(tag);
    if (prefix === 'staging')  obj = stagingSprites.get(tag);
    if (!obj) return null;
    // Return a plain serialisable object for Playwright to receive
    return {
      x:       Math.round(obj.position.x),
      y:       Math.round(obj.position.z),
      visible: obj.visible,
      alpha:   obj.material?.opacity ?? 1,
      tint:    obj.userData?.tint ?? 0xffffff,
      hasMask: obj.userData?.hasMask ?? false,
    };
  },
  worldToScreen: (wx, wz) => {
    if (!camera || !renderer) return { x: 0, y: 0 };
    const v = new THREE.Vector3(wx, 0, wz).project(camera);
    const sz = renderer.getSize(new THREE.Vector2());
    return { x: Math.round((v.x+1)/2*sz.width), y: Math.round((-v.y+1)/2*sz.height) };
  },
};

async function init() {
  scene = new THREE.Scene();
  scene.background = new THREE.Color(0x0a0a1a);

  renderer = new THREE.WebGLRenderer({ antialias: true });
  renderer.setSize(window.innerWidth, window.innerHeight);
  renderer.shadowMap.enabled = true;
  document.body.appendChild(renderer.domElement);

  camera = new THREE.PerspectiveCamera(45, window.innerWidth/window.innerHeight, 0.1, 500);

  group2d = new THREE.Group(); scene.add(group2d);
  group3d = new THREE.Group(); scene.add(group3d);
  group3d.visible = false;

  window._three = { scene, camera, renderer };

  initMaterials();
  setupCamera();
  setupLighting();
  await loadTerrain();
  connectWebSocket();
  initConfigPanel();
  animate();
}

window.addEventListener('resize', () => {
  if (!renderer || !camera) return;
  camera.aspect = window.innerWidth / window.innerHeight;
  camera.updateProjectionMatrix();
  renderer.setSize(window.innerWidth, window.innerHeight);
});

function animate() {
  requestAnimationFrame(animate);
  smoothCamera();
  renderer.render(scene, camera);
}

function setupCamera() {
  updateCamera();
  let drag = false, lastX = 0, lastY = 0, rDrag = false;
  renderer.domElement.addEventListener('mousedown', e => {
    drag = true; lastX = e.clientX; lastY = e.clientY; rDrag = e.button === 2;
    e.preventDefault();
  });
  renderer.domElement.addEventListener('contextmenu', e => e.preventDefault());
  window.addEventListener('mousemove', e => {
    if (!drag) return;
    const dx = e.clientX - lastX, dy = e.clientY - lastY;
    lastX = e.clientX; lastY = e.clientY;
    if (rDrag) {
      const right = new THREE.Vector3();
      camera.getWorldDirection(right); right.cross(camera.up).normalize();
      camTarget.addScaledVector(right, -dx * 0.03);
      camTarget.y += dy * 0.03;
    } else {
      tTheta -= dx * 0.012;
      tPhi = Math.max(0.08, Math.min(Math.PI/2.05, tPhi - dy * 0.012));
    }
  });
  window.addEventListener('mouseup', () => { drag = false; });
  renderer.domElement.addEventListener('wheel', e => {
    tDist = Math.max(4, Math.min(120, tDist + e.deltaY * 0.05));
    e.preventDefault();
  }, { passive: false });
}

function updateCamera() {
  camera.position.set(
    camTarget.x + camDist * Math.sin(camPhi) * Math.sin(camTheta),
    camTarget.y + camDist * Math.cos(camPhi),
    camTarget.z + camDist * Math.sin(camPhi) * Math.cos(camTheta)
  );
  camera.lookAt(camTarget);
}

function smoothCamera() {
  camPhi   += (tPhi   - camPhi)   * 0.1;
  camTheta += (tTheta - camTheta) * 0.1;
  camDist  += (tDist  - camDist)  * 0.1;
  updateCamera();
}

function setAngle(p, btn) {
  document.querySelectorAll('.angle-btn').forEach(b => b.classList.remove('active'));
  if (btn) btn.classList.add('active');
  if (p === 'top') { tPhi = 0.12;        tDist = Math.max(GRID_W, GRID_H) * TILE * 0.9; }
  if (p === 'iso') { tPhi = Math.PI/3.5; tDist = Math.max(GRID_W, GRID_H) * TILE * 0.7; }
  if (p === 'low') { tPhi = Math.PI/2.3; tDist = Math.max(GRID_W, GRID_H) * TILE * 0.5; }
}

function setMode(m) {
  document.getElementById('btn2d').classList.toggle('active', m === '2d');
  document.getElementById('btn3d').classList.toggle('active', m === '3d');
  group2d.visible = m === '2d';
  group3d.visible = m === '3d';
}

function setupLighting() {
  scene.add(new THREE.AmbientLight(0x223355, 0.9));
  const sun = new THREE.DirectionalLight(0xaabbff, 1.3);
  sun.position.set(20, 40, 20);
  sun.castShadow = true;
  sun.shadow.mapSize.set(2048, 2048);
  sun.shadow.camera.near = 1; sun.shadow.camera.far = 200;
  sun.shadow.camera.left = -60; sun.shadow.camera.right = 60;
  sun.shadow.camera.top = 60; sun.shadow.camera.bottom = -60;
  scene.add(sun);
  const fill = new THREE.DirectionalLight(0x334466, 0.4);
  fill.position.set(-10, 20, -10);
  scene.add(fill);
}

async function loadTerrain() {
  let walls = [], highGround = [], ramps = [];
  try {
    const r = await fetch('/qa/emulated/terrain');
    if (r.ok) {
      const d = await r.json();
      GRID_W = d.width; GRID_H = d.height;
      walls      = d.walls      || [];
      highGround = d.highGround || [];
      ramps      = d.ramps      || [];
    }
  } catch (_) { /* non-emulated profiles: flat grid */ }

  HALF_W = (GRID_W * TILE) / 2;
  HALF_H = (GRID_H * TILE) / 2;

  const wallSet = new Set(walls.map(([x,z])      => `${x},${z}`));
  const highSet = new Set(highGround.map(([x,z]) => `${x},${z}`));
  const rampSet = new Set(ramps.map(([x,z])      => `${x},${z}`));

  const sharedEdgesGeo = new THREE.EdgesGeometry(new THREE.BoxGeometry(TILE, 0.01, TILE));
  for (let gz = 0; gz < GRID_H; gz++) {
    for (let gx = 0; gx < GRID_W; gx++) {
      const key = `${gx},${gz}`;
      const cx  = gx * TILE - HALF_W + TILE/2;
      const cz  = gz * TILE - HALF_H + TILE/2;

      let h = 0.08, mat = mGround;
      if      (wallSet.has(key)) { h = TILE * 1.2; mat = mWall; }
      else if (highSet.has(key)) { h = TILE * 0.6; mat = mHigh; }
      else if (rampSet.has(key)) { h = TILE * 0.25; mat = mRamp; }

      const tile = new THREE.Mesh(new THREE.BoxGeometry(TILE*0.98, h, TILE*0.98), mat);
      tile.position.set(cx, h/2, cz);
      tile.receiveShadow = true;
      if (mat === mWall) tile.castShadow = true;
      scene.add(tile);

      const el = new THREE.LineSegments(sharedEdgesGeo, lineMat);
      el.position.set(cx, h + 0.01, cz);
      scene.add(el);
    }
  }

  tDist = camDist = Math.max(GRID_W, GRID_H) * TILE * 0.7;
  updateCamera();
  terrainLoaded = true;
}

function connectWebSocket() {
  const ws = new WebSocket(`ws://${window.location.host}/ws/gamestate`);
  ws.onopen  = () => { wsConnected = true; };
  ws.onmessage = e => {
    try {
      const msg = JSON.parse(e.data);
      onFrame(msg.state, msg.visibility);
    } catch (err) { console.warn('Bad WS message', err); }
  };
  ws.onerror = () => ws.close();
  ws.onclose = () => {
    wsConnected = false;
    document.getElementById('hud').textContent = 'Disconnected — reconnecting...';
    setTimeout(connectWebSocket, RECONNECT_MS);
  };
}

function onFrame(state, visibility) {
  if (!state) return;
  updateHud(state);
  updateFog(visibility);
  syncUnits(state);
}

function updateHud(state) {
  document.getElementById('hud').textContent =
    `Minerals: ${state.minerals}   Gas: ${state.vespene}` +
    `   Supply: ${state.supplyUsed}/${state.supply}` +
    `   Frame: ${state.gameFrame}`;
}

function updateFog(visibility) {}  // stub — implemented in Task 5

function syncUnits(state) {}       // stub — implemented in Task 6

function initConfigPanel() {}

init();
