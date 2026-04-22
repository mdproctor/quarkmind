// visualizer.js — QuarkMind 3D visualizer (Three.js r128)

const TILE = 0.7;
const TEAM_COLOR_FRIENDLY = '#4488ff';
const TEAM_COLOR_ENEMY    = '#ff4422';
const FLYING_UNITS = new Set(['MEDIVAC']);
const RECONNECT_MS = 2000;

let GRID_W = 64, GRID_H = 64;
let HALF_W, HALF_H;
let TERRAIN_SURFACE_Y = 0.08; // updated in loadTerrain — mock=0.08, emulated=TILE
let camTheta = Math.PI*0.25, camPhi = Math.PI/3.5, camDist = 18;
// Game content starts at tile (9,9) = world (-16.1, -16.1). Start camera looking there.
const camTarget = new THREE.Vector3(-16, 0, -16);
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
const stagingMeshes  = new Map();
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

  unitMatsKeys: () => Object.keys(UNIT_MATS),

  allEnemyWorldY: () => Array.from(enemySprites.values()).map(sp => sp.position.y),

  smokeTestDrawFn: (name, dir, teamColor) => {
    // typeof guard works for `function` declarations (hoisted to scope).
    // Does NOT work for const/let — add those after their declaration point instead.
    const lookup = {};
    if (typeof drawProbe    !== 'undefined') lookup.drawProbe    = drawProbe;
    if (typeof drawZealot   !== 'undefined') lookup.drawZealot   = drawZealot;
    if (typeof drawStalker  !== 'undefined') lookup.drawStalker  = drawStalker;
    if (typeof drawEnemy    !== 'undefined') lookup.drawEnemy    = drawEnemy;
    if (typeof drawMarine   !== 'undefined') lookup.drawMarine   = drawMarine;
    if (typeof drawMarauder !== 'undefined') lookup.drawMarauder = drawMarauder;
    if (typeof drawMedivac  !== 'undefined') lookup.drawMedivac  = drawMedivac;
    if (typeof drawZergling  !== 'undefined') lookup.drawZergling  = drawZergling;
    if (typeof drawRoach     !== 'undefined') lookup.drawRoach     = drawRoach;
    if (typeof drawHydralisk !== 'undefined') lookup.drawHydralisk = drawHydralisk;
    const fn = lookup[name];
    if (!fn) return -1;
    const c = document.createElement('canvas');
    c.width = c.height = 128;
    const ctx2d = c.getContext('2d');
    fn(ctx2d, 128, dir, teamColor);
    return ctx2d.getImageData(64, 64, 1, 1).data[3]; // alpha at centre
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
  initSpriteMaterials();  // ← directional canvas texture materials
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
  updateSpriteDirs();
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
  let hasRealTerrain = false;
  try {
    const r = await fetch('/qa/emulated/terrain');
    if (r.ok) {
      const d = await r.json();
      GRID_W = d.width; GRID_H = d.height;
      walls      = d.walls      || [];
      highGround = d.highGround || [];
      ramps      = d.ramps      || [];
      hasRealTerrain = true;
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
      if (h >= TILE * 0.4) tile.receiveShadow = true; // only emulated terrain has real depth worth shadowing
      if (mat === mWall) tile.castShadow = true;
      scene.add(tile);

      const el = new THREE.LineSegments(sharedEdgesGeo, lineMat);
      el.position.set(cx, h + 0.01, cz);
      scene.add(el);
    }
  }

  // Fog planes — only created in emulated mode where visibility data exists.
  // Skipping them in mock/replay modes removes 4096 objects from the scene graph,
  // cutting per-frame traversal cost roughly in half.
  if (hasRealTerrain) {
    const fogMat = new THREE.MeshBasicMaterial({
      color: 0x000000, transparent: true,
      side: THREE.DoubleSide, depthWrite: false
    });
    const fogGeo = new THREE.PlaneGeometry(TILE*0.98, TILE*0.98);
    for (let gz = 0; gz < GRID_H; gz++) {
      for (let gx = 0; gx < GRID_W; gx++) {
        const cx = gx * TILE - HALF_W + TILE/2;
        const cz = gz * TILE - HALF_H + TILE/2;
        const plane = new THREE.Mesh(fogGeo, fogMat.clone());
        plane.rotation.x = -Math.PI/2;
        plane.position.set(cx, 0.18, cz);
        plane.renderOrder = 5;
        plane.userData.isFog = true;
        plane.visible = true;
        plane.material.opacity = 1.0; // start fully UNSEEN — updateFog reveals tiles
        scene.add(plane);
        fogPlanes.set(`${gx},${gz}`, plane);
      }
    }
  }

  // Only set camera distance for emulated mode (real terrain). Mock mode keeps the
  // closer default distance so the player base at tile (9,9) fills the frame.
  if (hasRealTerrain) {
    tDist = camDist = Math.max(GRID_W, GRID_H) * TILE * 0.7;
    TERRAIN_SURFACE_Y = TILE; // emulated ground tiles are TILE tall
  }
  updateCamera();
  terrainLoaded = true;
}

function gw(gx, gz) {
  return { x: gx * TILE - HALF_W, z: gz * TILE - HALF_H };
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

function updateFog(visibility) {
  if (!visibility) return;
  for (let gz = 0; gz < GRID_H; gz++) {
    for (let gx = 0; gx < GRID_W; gx++) {
      const plane = fogPlanes.get(`${gx},${gz}`);
      if (!plane) continue;
      const ch = visibility.charAt(gz * GRID_W + gx);
      if (ch === '2') {
        plane.visible = false;
      } else {
        plane.visible = true;
        plane.material.opacity = ch === '1' ? 0.45 : 1.0;
      }
    }
  }
}

const BUILDING_H = { NEXUS: 1.4, PYLON: 1.7, GATEWAY: 1.1 };
const BUILDING_W = { NEXUS: 2.5, PYLON: 0.8, GATEWAY: 1.8 };
const BUILDING_COLOUR = {
  NEXUS:   [0x2255aa, 0x112244],
  PYLON:   [0x553399, 0x221144],
  GATEWAY: [0x336688, 0x112233],
};

function syncUnits(state) {
  syncBuildings(state.myBuildings   || []);
  syncGeysers(state.geysers         || []);
  syncUnitLayer(unitSprites,   unit3dMeshes,  state.myUnits          || [], false);
  syncUnitLayer(enemySprites,  enemy3dMeshes, state.enemyUnits        || [], true);
  syncUnitLayer(stagingSprites, stagingMeshes, state.enemyStagingArea  || [], true);
}

function syncBuildings(buildings) {
  const seen = new Set();
  buildings.forEach(b => {
    seen.add(b.tag);
    if (!buildingMeshes.has(b.tag)) {
      const h = BUILDING_H[b.type] ?? 1.0;
      const w = BUILDING_W[b.type] ?? 1.5;
      const [color, emissive] = BUILDING_COLOUR[b.type] ?? [0x334455, 0x111122];
      const mesh = new THREE.Mesh(
        new THREE.BoxGeometry(w * TILE * 0.7, h, w * TILE * 0.7),
        new THREE.MeshLambertMaterial({ color, emissive })
      );
      mesh.castShadow = mesh.receiveShadow = true;
      const wp = gw(b.position.x, b.position.y);
      mesh.position.set(wp.x, h/2, wp.z);
      // Buildings are always-visible anchors like terrain tiles — not in group2d/group3d
      scene.add(mesh);
      buildingMeshes.set(b.tag, mesh);
    }
  });
  buildingMeshes.forEach((m, tag) => {
    if (!seen.has(tag)) { scene.remove(m); buildingMeshes.delete(tag); }
  });
}

function syncGeysers(geysers) {
  const seen = new Set();
  geysers.forEach(g => {
    seen.add(g.tag);
    if (!geyserMeshes.has(g.tag)) {
      const mesh = new THREE.Mesh(
        new THREE.BoxGeometry(TILE*0.6, TILE*0.25, TILE*0.6),
        new THREE.MeshLambertMaterial({ color: 0x224422, emissive: 0x001100 })
      );
      const wp = gw(g.position.x, g.position.y);
      mesh.position.set(wp.x, TILE*0.125, wp.z);
      // Geysers are always-visible anchors — not toggled by 2D/3D mode
      scene.add(mesh);
      geyserMeshes.set(g.tag, mesh);
    }
  });
  geyserMeshes.forEach((m, tag) => {
    if (!seen.has(tag)) { scene.remove(m); geyserMeshes.delete(tag); }
  });
}

function syncUnitLayer(spriteMap, meshMap, units, isEnemy) {
  const seen = new Set();
  units.forEach(u => {
    seen.add(u.tag);
    const wp = gw(u.position.x, u.position.y);

    // Update facing from position delta
    const prev = prevPositions.get(u.tag);
    if (prev) {
      const dx = wp.x - prev.x, dz = wp.z - prev.z;
      if (dx*dx + dz*dz > 0.0001) {
        unitFacings.set(u.tag, Math.atan2(-dx, dz));
      }
    }
    prevPositions.set(u.tag, { x: wp.x, z: wp.z });

    if (!spriteMap.has(u.tag)) {
      // 2D sprite — directional canvas texture material
      const key  = u.type + (isEnemy ? '_E' : '_F');
      const mats = UNIT_MATS[key] ?? UNIT_MATS['UNKNOWN_' + (isEnemy ? 'E' : 'F')];
      const sp = new THREE.Sprite(mats[0]);
      sp.userData.mats = mats;
      sp.scale.set(TILE * 1.4, TILE * 1.4, 1);
      const unitY = FLYING_UNITS.has(u.type) ? TILE * 1.5 : TILE * 0.65;
      sp.position.set(wp.x, unitY, wp.z);
      group2d.add(sp);
      spriteMap.set(u.tag, sp);

      // 3D sphere model
      const g = make3dModel(isEnemy ? 0xcc3322 : 0x4488dd, isEnemy ? 0x330000 : 0x112244);
      g.position.set(wp.x, FLYING_UNITS.has(u.type) ? TILE * 1.5 : TERRAIN_SURFACE_Y, wp.z);
      group3d.add(g);
      if (meshMap instanceof Map) meshMap.set(u.tag, g);
    } else {
      const sp = spriteMap.get(u.tag);
      sp.position.x = wp.x; sp.position.z = wp.z;
      const g = meshMap instanceof Map ? meshMap.get(u.tag) : null;
      if (g) { g.position.x = wp.x; g.position.z = wp.z; }
    }
  });

  spriteMap.forEach((sp, tag) => {
    if (!seen.has(tag)) {
      group2d.remove(sp);
      spriteMap.delete(tag);
      prevPositions.delete(tag);
      unitFacings.delete(tag);
      if (meshMap instanceof Map) {
        const g = meshMap.get(tag);
        if (g) { group3d.remove(g); meshMap.delete(tag); }
      }
    }
  });
}

// ── Sprite direction system ───────────────────────────────────────────────────

// Returns 0=front, 1=right, 2=back, 3=left relative to unit facing.
// Negated dx corrects for Three.js screen-space handedness.
function getDir4(facingAngle, unitPos, camPos) {
  const camAngle = Math.atan2(-(camPos.x - unitPos.x), camPos.z - unitPos.z);
  let rel = camAngle - facingAngle;
  while (rel < 0)          rel += Math.PI * 2;
  while (rel >= Math.PI*2) rel -= Math.PI * 2;
  return Math.round(rel / (Math.PI/2)) % 4;
}

function hexToRgba(hex, a) {
  // Expects 6-char hex only: '#rrggbb' — 3-char shorthand and named colours are not supported
  const r = parseInt(hex.slice(1,3), 16);
  const g = parseInt(hex.slice(3,5), 16);
  const b = parseInt(hex.slice(5,7), 16);
  return `rgba(${r},${g},${b},${a})`;
}

function makeDirTextures(drawFn, teamColor, size = 128) {
  return [0, 1, 2, 3].map(dir => {
    const c = document.createElement('canvas');
    c.width = c.height = size;
    drawFn(c.getContext('2d'), size, dir, teamColor);
    const tex = new THREE.CanvasTexture(c);
    tex.premultiplyAlpha = true;
    return new THREE.SpriteMaterial({
      map: tex, transparent: true,
      depthWrite: true, alphaTest: 0.1
    });
  });
}

// Art stubs — replaced in Tasks 8-11
function drawProbe(ctx, S, dir, teamColor) {
  const cx = S/2, cy = S/2 + 4;
  const grd = ctx.createRadialGradient(cx, cy, S*.05, cx, cy, S*.46);
  grd.addColorStop(0, hexToRgba(teamColor, 0.3)); grd.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.fillStyle = grd; ctx.beginPath(); ctx.ellipse(cx,cy,S*.46,S*.46,0,0,Math.PI*2); ctx.fill();

  if (dir === 2) { // BACK
    const b = ctx.createRadialGradient(cx+S*.08,cy-S*.08,S*.02,cx,cy,S*.3);
    b.addColorStop(0, hexToRgba(teamColor, 0.9));
    b.addColorStop(.7, teamColor);
    b.addColorStop(1, hexToRgba(teamColor, 0.25));
    ctx.fillStyle=b; ctx.beginPath(); ctx.ellipse(cx,cy,S*.3,S*.28,0,0,Math.PI*2); ctx.fill();
    ctx.strokeStyle=hexToRgba(teamColor, 0.7); ctx.lineWidth=3;
    ctx.beginPath(); ctx.moveTo(cx,cy-S*.28); ctx.lineTo(cx,cy-S*.42); ctx.stroke();
    ctx.fillStyle=hexToRgba(teamColor, 0.8); ctx.beginPath(); ctx.ellipse(cx,cy-S*.44,S*.04,S*.04,0,0,Math.PI*2); ctx.fill();
    ctx.fillStyle=teamColor;
    ctx.beginPath(); ctx.ellipse(cx-S*.1,cy+S*.26,S*.08,S*.05,0,0,Math.PI*2); ctx.fill();
    ctx.beginPath(); ctx.ellipse(cx+S*.1,cy+S*.26,S*.08,S*.05,0,0,Math.PI*2); ctx.fill();
    return;
  }
  if (dir === 1 || dir === 3) { // SIDE
    const flip = dir===3 ? -1 : 1;
    const b = ctx.createRadialGradient(cx+flip*S*.06,cy-S*.08,S*.02,cx,cy,S*.28);
    b.addColorStop(0, hexToRgba(teamColor, 0.9));
    b.addColorStop(.6, teamColor);
    b.addColorStop(1, hexToRgba(teamColor, 0.3));
    ctx.fillStyle=b; ctx.beginPath(); ctx.ellipse(cx,cy,S*.28,S*.24,0,0,Math.PI*2); ctx.fill();
    ctx.fillStyle='rgba(255,255,255,0.12)';
    ctx.beginPath(); ctx.ellipse(cx+flip*S*.08,cy-S*.1,S*.1,S*.07,-.3,0,Math.PI*2); ctx.fill();
    ctx.fillStyle='white'; ctx.beginPath(); ctx.ellipse(cx+flip*S*.16,cy-S*.04,S*.08,S*.08,0,0,Math.PI*2); ctx.fill();
    ctx.fillStyle='#112244'; ctx.beginPath(); ctx.ellipse(cx+flip*S*.18,cy-S*.03,S*.046,S*.046,0,0,Math.PI*2); ctx.fill();
    ctx.fillStyle='white'; ctx.beginPath(); ctx.ellipse(cx+flip*S*.15,cy-S*.07,S*.02,S*.02,0,0,Math.PI*2); ctx.fill();
    ctx.strokeStyle=hexToRgba(teamColor, 0.7); ctx.lineWidth=2;
    ctx.beginPath(); ctx.moveTo(cx+flip*S*.24,cy-S*.02); ctx.lineTo(cx+flip*S*.37,cy-S*.14); ctx.stroke();
    const eg = ctx.createRadialGradient(cx+flip*S*.4,cy-S*.17,0,cx+flip*S*.4,cy-S*.17,S*.09);
    eg.addColorStop(0,'#ffff88'); eg.addColorStop(.5,'#ffcc00'); eg.addColorStop(1,'rgba(255,180,0,0)');
    ctx.fillStyle=eg; ctx.beginPath(); ctx.ellipse(cx+flip*S*.4,cy-S*.17,S*.09,S*.09,0,0,Math.PI*2); ctx.fill();
    ctx.fillStyle=teamColor;
    ctx.beginPath(); ctx.ellipse(cx,cy+S*.26,S*.1,S*.055,0,0,Math.PI*2); ctx.fill();
    return;
  }
  // FRONT
  const b = ctx.createRadialGradient(cx-S*.08,cy-S*.1,S*.04,cx,cy,S*.3);
  b.addColorStop(0, hexToRgba(teamColor, 0.9));
  b.addColorStop(.6, teamColor);
  b.addColorStop(1, hexToRgba(teamColor, 0.3));
  ctx.fillStyle=b; ctx.beginPath(); ctx.ellipse(cx,cy,S*.3,S*.28,0,0,Math.PI*2); ctx.fill();
  ctx.fillStyle='rgba(255,255,255,0.15)';
  ctx.beginPath(); ctx.ellipse(cx-S*.06,cy-S*.1,S*.12,S*.08,-.4,0,Math.PI*2); ctx.fill();
  [[-S*.1],[S*.1]].forEach(([ex]) => {
    const exx=cx+ex, eyy=cy-S*.06;
    ctx.fillStyle='white'; ctx.beginPath(); ctx.ellipse(exx,eyy,S*.07,S*.07,0,0,Math.PI*2); ctx.fill();
    ctx.fillStyle='#112244'; ctx.beginPath(); ctx.ellipse(exx+1,eyy+1,S*.04,S*.04,0,0,Math.PI*2); ctx.fill();
    ctx.fillStyle='white'; ctx.beginPath(); ctx.ellipse(exx-1,eyy-2,S*.018,S*.018,0,0,Math.PI*2); ctx.fill();
  });
  ctx.strokeStyle=hexToRgba(teamColor, 0.7); ctx.lineWidth=2;
  ctx.beginPath(); ctx.moveTo(cx+S*.22,cy-S*.04); ctx.lineTo(cx+S*.35,cy-S*.16); ctx.stroke();
  const eg = ctx.createRadialGradient(cx+S*.38,cy-S*.19,0,cx+S*.38,cy-S*.19,S*.09);
  eg.addColorStop(0,'#ffff88'); eg.addColorStop(.5,'#ffcc00'); eg.addColorStop(1,'rgba(255,180,0,0)');
  ctx.fillStyle=eg; ctx.beginPath(); ctx.ellipse(cx+S*.38,cy-S*.19,S*.09,S*.09,0,0,Math.PI*2); ctx.fill();
  ctx.fillStyle=teamColor;
  ctx.beginPath(); ctx.ellipse(cx-S*.1,cy+S*.26,S*.08,S*.05,0,0,Math.PI*2); ctx.fill();
  ctx.beginPath(); ctx.ellipse(cx+S*.1,cy+S*.26,S*.08,S*.05,0,0,Math.PI*2); ctx.fill();
}
function drawZealot(ctx, S, dir, teamColor) {
  const cx = S/2, cy = S/2 + 2;
  const grd = ctx.createRadialGradient(cx,cy,S*.1,cx,cy,S*.48);
  grd.addColorStop(0,'rgba(140,80,255,0.25)'); grd.addColorStop(1,'rgba(0,0,0,0)');
  ctx.fillStyle=grd; ctx.beginPath(); ctx.ellipse(cx,cy,S*.48,S*.48,0,0,Math.PI*2); ctx.fill();

  if (dir === 2) { // BACK
    const b = ctx.createRadialGradient(cx+S*.08,cy-S*.08,S*.02,cx,cy,S*.3);
    b.addColorStop(0,'#aa88dd'); b.addColorStop(.7,'#5533aa'); b.addColorStop(1,'#331177');
    ctx.fillStyle=b; ctx.beginPath(); ctx.ellipse(cx,cy,S*.3,S*.28,0,0,Math.PI*2); ctx.fill();
    ctx.fillStyle='#8855cc';
    ctx.beginPath(); ctx.moveTo(cx-S*.08,cy-S*.28); ctx.lineTo(cx,cy-S*.44); ctx.lineTo(cx+S*.08,cy-S*.28); ctx.fill();
    const bg1 = ctx.createLinearGradient(cx-S*.36,cy,cx-S*.28,cy);
    bg1.addColorStop(0,'rgba(0,0,0,0)'); bg1.addColorStop(1, teamColor);
    ctx.fillStyle=bg1; ctx.beginPath(); ctx.ellipse(cx-S*.33,cy,S*.06,S*.18,0,0,Math.PI*2); ctx.fill();
    const bg2 = ctx.createLinearGradient(cx+S*.28,cy,cx+S*.36,cy);
    bg2.addColorStop(0, teamColor); bg2.addColorStop(1,'rgba(0,0,0,0)');
    ctx.fillStyle=bg2; ctx.beginPath(); ctx.ellipse(cx+S*.33,cy,S*.06,S*.18,0,0,Math.PI*2); ctx.fill();
    ctx.fillStyle='#5533aa';
    ctx.beginPath(); ctx.ellipse(cx-S*.1,cy+S*.26,S*.09,S*.055,0,0,Math.PI*2); ctx.fill();
    ctx.beginPath(); ctx.ellipse(cx+S*.1,cy+S*.26,S*.09,S*.055,0,0,Math.PI*2); ctx.fill();
    return;
  }
  if (dir === 1 || dir === 3) { // SIDE
    const flip = dir===3 ? -1 : 1;
    const bg = ctx.createLinearGradient(cx+flip*S*.22,cy,cx+flip*S*.42,cy);
    bg.addColorStop(flip>0?0:1, hexToRgba(teamColor, 0.9));
    bg.addColorStop(flip>0?1:0, hexToRgba(teamColor, 0.0));
    ctx.fillStyle=bg; ctx.beginPath(); ctx.ellipse(cx+flip*S*.36,cy,S*.07,S*.2,0,0,Math.PI*2); ctx.fill();
    const b = ctx.createRadialGradient(cx+flip*S*.06,cy-S*.08,S*.02,cx,cy,S*.26);
    b.addColorStop(0,'#ccaaff'); b.addColorStop(.5,'#7755cc'); b.addColorStop(1,'#441199');
    ctx.fillStyle=b; ctx.beginPath(); ctx.ellipse(cx,cy,S*.26,S*.24,0,0,Math.PI*2); ctx.fill();
    ctx.fillStyle='white'; ctx.beginPath(); ctx.ellipse(cx+flip*S*.15,cy-S*.07,S*.08,S*.08,0,0,Math.PI*2); ctx.fill();
    ctx.fillStyle='#221144'; ctx.beginPath(); ctx.ellipse(cx+flip*S*.17,cy-S*.06,S*.046,S*.046,0,0,Math.PI*2); ctx.fill();
    ctx.fillStyle='white'; ctx.beginPath(); ctx.ellipse(cx+flip*S*.14,cy-S*.1,S*.02,S*.02,0,0,Math.PI*2); ctx.fill();
    ctx.fillStyle='#5533aa';
    ctx.beginPath(); ctx.ellipse(cx,cy+S*.26,S*.1,S*.055,0,0,Math.PI*2); ctx.fill();
    return;
  }
  // FRONT
  function blade(bx) {
    const bg = ctx.createLinearGradient(bx-8,cy-S*.2,bx+8,cy+S*.2);
    bg.addColorStop(0, hexToRgba(teamColor, 0.9));
    bg.addColorStop(.5, teamColor);
    bg.addColorStop(1, hexToRgba(teamColor, 0.2));
    ctx.fillStyle=bg; ctx.beginPath(); ctx.ellipse(bx,cy,S*.07,S*.2,0,0,Math.PI*2); ctx.fill();
  }
  blade(cx-S*.36); blade(cx+S*.36);
  const b = ctx.createRadialGradient(cx-S*.08,cy-S*.1,S*.04,cx,cy,S*.3);
  b.addColorStop(0,'#ccaaff'); b.addColorStop(.5,'#7755cc'); b.addColorStop(1,'#441199');
  ctx.fillStyle=b; ctx.beginPath(); ctx.ellipse(cx,cy,S*.3,S*.28,0,0,Math.PI*2); ctx.fill();
  ctx.fillStyle='rgba(255,255,255,0.12)';
  ctx.beginPath(); ctx.ellipse(cx-S*.07,cy-S*.1,S*.12,S*.08,-.4,0,Math.PI*2); ctx.fill();
  [[-S*.11],[S*.11]].forEach(([ex]) => {
    const exx=cx+ex, eyy=cy-S*.07;
    ctx.fillStyle='white'; ctx.beginPath(); ctx.ellipse(exx,eyy,S*.075,S*.075,0,0,Math.PI*2); ctx.fill();
    ctx.fillStyle='#221144'; ctx.beginPath(); ctx.ellipse(exx+1,eyy+1,S*.042,S*.042,0,0,Math.PI*2); ctx.fill();
    ctx.fillStyle='white'; ctx.beginPath(); ctx.ellipse(exx-1,eyy-2,S*.018,S*.018,0,0,Math.PI*2); ctx.fill();
  });
  ctx.fillStyle='#5533aa';
  ctx.beginPath(); ctx.ellipse(cx-S*.1,cy+S*.26,S*.09,S*.055,0,0,Math.PI*2); ctx.fill();
  ctx.beginPath(); ctx.ellipse(cx+S*.1,cy+S*.26,S*.09,S*.055,0,0,Math.PI*2); ctx.fill();
}
function drawStalker(ctx, S, dir, teamColor) {
  const cx = S/2, cy = S/2 + 2;
  const grd = ctx.createRadialGradient(cx,cy,S*.05,cx,cy,S*.44);
  grd.addColorStop(0,'rgba(50,100,150,0.22)'); grd.addColorStop(1,'rgba(0,0,0,0)');
  ctx.fillStyle=grd; ctx.beginPath(); ctx.ellipse(cx,cy,S*.44,S*.44,0,0,Math.PI*2); ctx.fill();

  function drawEye(ex, ey, r) {
    r = r ?? S*.09;
    ctx.fillStyle='#001122'; ctx.beginPath(); ctx.ellipse(ex,ey,r*1.3,r*1.3,0,0,Math.PI*2); ctx.fill();
    ctx.fillStyle=teamColor;  ctx.beginPath(); ctx.ellipse(ex,ey,r,r,0,0,Math.PI*2); ctx.fill();
    ctx.fillStyle=hexToRgba(teamColor, 0.7); ctx.beginPath(); ctx.ellipse(ex,ey,r*.6,r*.6,0,0,Math.PI*2); ctx.fill();
    ctx.fillStyle='white';    ctx.beginPath(); ctx.ellipse(ex-r*.4,ey-r*.4,r*.22,r*.22,0,0,Math.PI*2); ctx.fill();
  }

  function legs4(ox) {
    ctx.strokeStyle='#445566'; ctx.lineWidth=4; ctx.lineCap='round';
    [[-S*.22,S*.1,-S*.34,S*.28],[-S*.1,S*.14,-S*.14,S*.3],
     [S*.1,S*.14,S*.14,S*.3],[S*.22,S*.1,S*.34,S*.28]].forEach(([x1,y1,x2,y2])=>{
      ctx.beginPath(); ctx.moveTo(cx+(x1+(ox||0)),cy+y1); ctx.lineTo(cx+(x2+(ox||0)),cy+y2); ctx.stroke();
    });
  }

  if (dir === 2) { // BACK
    const b = ctx.createRadialGradient(cx+S*.06,cy-S*.06,S*.02,cx,cy,S*.28);
    b.addColorStop(0,'#556677'); b.addColorStop(.7,'#2a3a44'); b.addColorStop(1,'#111822');
    ctx.fillStyle=b; ctx.beginPath(); ctx.ellipse(cx,cy,S*.28,S*.24,0,0,Math.PI*2); ctx.fill();
    ctx.strokeStyle='#445566'; ctx.lineWidth=4; ctx.lineCap='round';
    [[-S*.2,S*.12,-S*.3,S*.28],[-S*.08,S*.14,-S*.12,S*.3],
     [S*.08,S*.14,S*.12,S*.3],[S*.2,S*.12,S*.3,S*.28]].forEach(([x1,y1,x2,y2])=>{
      ctx.beginPath(); ctx.moveTo(cx+x1,cy+y1); ctx.lineTo(cx+x2,cy+y2); ctx.stroke();
    });
    ctx.fillStyle='#334455';
    ctx.beginPath(); ctx.ellipse(cx,cy-S*.28,S*.08,S*.06,0,0,Math.PI*2); ctx.fill();
    return;
  }
  if (dir === 1 || dir === 3) { // SIDE
    const flip = dir===3 ? -1 : 1;
    ctx.strokeStyle='#445566'; ctx.lineWidth=4; ctx.lineCap='round';
    [[-S*.08,S*.12,-S*.14,S*.28],[S*.08,S*.12,S*.14,S*.28]].forEach(([x1,y1,x2,y2])=>{
      ctx.beginPath(); ctx.moveTo(cx+x1,cy+y1); ctx.lineTo(cx+x2,cy+y2); ctx.stroke();
    });
    const b = ctx.createRadialGradient(cx+flip*S*.04,cy-S*.06,S*.02,cx,cy,S*.24);
    b.addColorStop(0,'#556677'); b.addColorStop(.6,'#334455'); b.addColorStop(1,'#111822');
    ctx.fillStyle=b; ctx.beginPath(); ctx.ellipse(cx,cy-S*.02,S*.26,S*.2,0,0,Math.PI*2); ctx.fill();
    ctx.fillStyle='#334455';
    ctx.beginPath(); ctx.ellipse(cx+flip*S*.08,cy-S*.24,S*.1,S*.07,flip*0.4,0,Math.PI*2); ctx.fill();
    drawEye(cx+flip*S*.14, cy-S*.04, S*.075);
    return;
  }
  // FRONT
  legs4();
  const b = ctx.createRadialGradient(cx-S*.06,cy-S*.08,S*.02,cx,cy,S*.3);
  b.addColorStop(0,'#667788'); b.addColorStop(.6,'#334455'); b.addColorStop(1,'#111822');
  ctx.fillStyle=b; ctx.beginPath(); ctx.ellipse(cx,cy-S*.02,S*.3,S*.26,0,0,Math.PI*2); ctx.fill();
  ctx.fillStyle='#445566';
  ctx.fillRect(cx-S*.07, cy-S*.4, S*.14, S*.16);
  ctx.beginPath(); ctx.ellipse(cx,cy-S*.4,S*.07,S*.07,0,0,Math.PI*2); ctx.fill();
  drawEye(cx, cy-S*.04, S*.1);
}
function drawEnemy(ctx, S, dir, teamColor) {
  const cx = S/2, cy = S/2 + 2;
  const grd = ctx.createRadialGradient(cx,cy,S*.05,cx,cy,S*.44);
  grd.addColorStop(0,'rgba(255,60,30,0.28)'); grd.addColorStop(1,'rgba(0,0,0,0)');
  ctx.fillStyle=grd; ctx.beginPath(); ctx.ellipse(cx,cy,S*.44,S*.44,0,0,Math.PI*2); ctx.fill();

  function spikes(num, startAngle) {
    ctx.fillStyle = teamColor;
    for (let i=0; i<num; i++) {
      const a = startAngle + i*(Math.PI*2/num);
      ctx.beginPath();
      ctx.moveTo(cx+Math.cos(a)*S*.28, cy+Math.sin(a)*S*.28);
      ctx.lineTo(cx+Math.cos(a-.1)*S*.22, cy+Math.sin(a-.1)*S*.22);
      ctx.lineTo(cx+Math.cos(a+.1)*S*.22, cy+Math.sin(a+.1)*S*.22);
      ctx.fill();
    }
  }

  if (dir === 2) { // BACK
    const b = ctx.createRadialGradient(cx+S*.08,cy-S*.08,S*.04,cx,cy,S*.3);
    b.addColorStop(0,'#dd5533'); b.addColorStop(.5,'#cc3322'); b.addColorStop(1,'#881111');
    ctx.fillStyle=b; ctx.beginPath(); ctx.ellipse(cx,cy+S*.02,S*.3,S*.26,0,0,Math.PI*2); ctx.fill();
    spikes(5, -Math.PI*.6);
    return;
  }
  if (dir === 1 || dir === 3) { // SIDE
    const flip = dir===3 ? -1 : 1;
    spikes(3, flip>0 ? -Math.PI*.3 : Math.PI*.7);
    const b = ctx.createRadialGradient(cx-S*.06,cy-S*.08,S*.04,cx,cy,S*.28);
    b.addColorStop(0,'#ff9966'); b.addColorStop(.5,'#cc3322'); b.addColorStop(1,'#881111');
    ctx.fillStyle=b; ctx.beginPath(); ctx.ellipse(cx,cy+S*.02,S*.22,S*.26,0,0,Math.PI*2); ctx.fill();
    ctx.fillStyle=teamColor; ctx.beginPath(); ctx.ellipse(cx+flip*S*.08,cy-S*.05,S*.075,S*.065,0,0,Math.PI*2); ctx.fill();
    ctx.fillStyle='#220000'; ctx.beginPath(); ctx.ellipse(cx+flip*S*.08,cy-S*.04,S*.04,S*.05,0,0,Math.PI*2); ctx.fill();
    ctx.strokeStyle='#881111'; ctx.lineWidth=2.5;
    ctx.beginPath(); ctx.moveTo(cx+flip*S*.01,cy-S*.12); ctx.lineTo(cx+flip*S*.13,cy-S*.08); ctx.stroke();
    return;
  }
  // FRONT
  spikes(5, -Math.PI*.6);
  const b = ctx.createRadialGradient(cx-S*.08,cy-S*.08,S*.04,cx,cy,S*.3);
  b.addColorStop(0,'#ff9966'); b.addColorStop(.5,'#cc3322'); b.addColorStop(1,'#881111');
  ctx.fillStyle=b; ctx.beginPath(); ctx.ellipse(cx,cy+S*.02,S*.3,S*.26,0,0,Math.PI*2); ctx.fill();
  ctx.fillStyle='rgba(255,200,180,0.18)';
  ctx.beginPath(); ctx.ellipse(cx-S*.06,cy-S*.08,S*.12,S*.08,-.4,0,Math.PI*2); ctx.fill();
  [[-S*.1],[S*.1]].forEach(([ex],i) => {
    const exx=cx+ex, eyy=cy-S*.05;
    ctx.fillStyle=teamColor; ctx.beginPath(); ctx.ellipse(exx,eyy,S*.075,S*.065,0,0,Math.PI*2); ctx.fill();
    ctx.fillStyle='#220000'; ctx.beginPath(); ctx.ellipse(exx,eyy+1,S*.04,S*.05,0,0,Math.PI*2); ctx.fill();
    ctx.strokeStyle='#881111'; ctx.lineWidth=2.5;
    ctx.beginPath();
    if (i===0) { ctx.moveTo(exx-S*.07,eyy-S*.07); ctx.lineTo(exx+S*.04,eyy-S*.04); }
    else       { ctx.moveTo(exx-S*.04,eyy-S*.04); ctx.lineTo(exx+S*.07,eyy-S*.07); }
    ctx.stroke();
  });
}

function drawMarine(ctx, S, dir, teamColor) {
  const cx = S/2, cy = S/2 + 4;
  // Outer glow
  const grd = ctx.createRadialGradient(cx, cy, S*.05, cx, cy, S*.44);
  grd.addColorStop(0, 'rgba(100,120,140,0.3)'); grd.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.fillStyle = grd; ctx.beginPath(); ctx.ellipse(cx,cy,S*.44,S*.44,0,0,Math.PI*2); ctx.fill();

  if (dir === 2) { // BACK
    const b = ctx.createRadialGradient(cx+S*.06,cy-S*.06,S*.02,cx,cy,S*.3);
    b.addColorStop(0,'#8899aa'); b.addColorStop(.6,'#556677'); b.addColorStop(1,'#223344');
    ctx.fillStyle=b; ctx.beginPath(); ctx.ellipse(cx,cy,S*.3,S*.28,0,0,Math.PI*2); ctx.fill();
    // Shoulder pads in teamColor
    ctx.fillStyle=teamColor;
    ctx.beginPath(); ctx.ellipse(cx-S*.24,cy-S*.08,S*.1,S*.07,0,0,Math.PI*2); ctx.fill();
    ctx.beginPath(); ctx.ellipse(cx+S*.24,cy-S*.08,S*.1,S*.07,0,0,Math.PI*2); ctx.fill();
    // Backpack power cell
    ctx.fillStyle='#334455';
    ctx.fillRect(cx-S*.06,cy-S*.28,S*.12,S*.18);
    ctx.fillStyle='#445566'; ctx.beginPath(); ctx.ellipse(cx,cy-S*.28,S*.06,S*.05,0,0,Math.PI*2); ctx.fill();
    // Boots
    ctx.fillStyle='#223344';
    ctx.beginPath(); ctx.ellipse(cx-S*.1,cy+S*.28,S*.1,S*.055,0,0,Math.PI*2); ctx.fill();
    ctx.beginPath(); ctx.ellipse(cx+S*.1,cy+S*.28,S*.1,S*.055,0,0,Math.PI*2); ctx.fill();
    return;
  }
  if (dir === 1 || dir === 3) { // SIDE
    const flip = dir===3 ? -1 : 1;
    // Body
    const b = ctx.createRadialGradient(cx+flip*S*.04,cy-S*.04,S*.02,cx,cy,S*.28);
    b.addColorStop(0,'#8899aa'); b.addColorStop(.5,'#556677'); b.addColorStop(1,'#223344');
    ctx.fillStyle=b; ctx.beginPath(); ctx.ellipse(cx,cy,S*.28,S*.26,0,0,Math.PI*2); ctx.fill();
    // Helmet
    const h = ctx.createRadialGradient(cx+flip*S*.04,cy-S*.2,S*.02,cx,cy-S*.14,S*.18);
    h.addColorStop(0,'#6677aa'); h.addColorStop(.6,'#334466'); h.addColorStop(1,'#0d1822');
    ctx.fillStyle=h; ctx.beginPath(); ctx.ellipse(cx,cy-S*.14,S*.18,S*.18,0,0,Math.PI*2); ctx.fill();
    // Visor in teamColor (side — small slit)
    ctx.fillStyle=hexToRgba(teamColor, 0.75);
    ctx.beginPath(); ctx.ellipse(cx+flip*S*.1,cy-S*.16,S*.08,S*.04,0,0,Math.PI*2); ctx.fill();
    // Shoulder pad on visible side
    ctx.fillStyle=teamColor;
    ctx.beginPath(); ctx.ellipse(cx+flip*S*.22,cy-S*.08,S*.09,S*.065,0,0,Math.PI*2); ctx.fill();
    // Rifle barrel
    ctx.fillStyle='#222';
    ctx.fillRect(cx+flip*S*.2,cy-S*.02,flip*S*.26,S*.07);
    // Boot
    ctx.fillStyle='#223344';
    ctx.beginPath(); ctx.ellipse(cx,cy+S*.28,S*.12,S*.055,0,0,Math.PI*2); ctx.fill();
    return;
  }
  // FRONT
  const b = ctx.createRadialGradient(cx-S*.06,cy-S*.04,S*.04,cx,cy,S*.32);
  b.addColorStop(0,'#8899aa'); b.addColorStop(.5,'#556677'); b.addColorStop(1,'#223344');
  ctx.fillStyle=b; ctx.beginPath(); ctx.ellipse(cx,cy+S*.02,S*.3,S*.28,0,0,Math.PI*2); ctx.fill();
  // Shoulder pads in teamColor
  ctx.fillStyle=teamColor;
  ctx.beginPath(); ctx.ellipse(cx-S*.26,cy-S*.06,S*.11,S*.07,0,0,Math.PI*2); ctx.fill();
  ctx.beginPath(); ctx.ellipse(cx+S*.26,cy-S*.06,S*.11,S*.07,0,0,Math.PI*2); ctx.fill();
  // Helmet dome
  const h = ctx.createRadialGradient(cx-S*.06,cy-S*.2,S*.02,cx,cy-S*.16,S*.2);
  h.addColorStop(0,'#6677aa'); h.addColorStop(.5,'#334466'); h.addColorStop(1,'#0d1822');
  ctx.fillStyle=h; ctx.beginPath(); ctx.ellipse(cx,cy-S*.16,S*.2,S*.2,0,0,Math.PI*2); ctx.fill();
  // Visor in teamColor
  ctx.fillStyle=hexToRgba(teamColor, 0.75);
  ctx.beginPath(); ctx.ellipse(cx,cy-S*.17,S*.13,S*.065,0,0,Math.PI*2); ctx.fill();
  // Visor highlight
  ctx.fillStyle='rgba(255,255,255,0.35)';
  ctx.beginPath(); ctx.ellipse(cx-S*.04,cy-S*.19,S*.055,S*.025,-0.3,0,Math.PI*2); ctx.fill();
  // Right arm + rifle
  ctx.fillStyle='#445566';
  ctx.fillRect(cx+S*.2,cy-S*.03,S*.22,S*.08);
  ctx.fillStyle='#222';
  ctx.fillRect(cx+S*.3,cy-S*.01,S*.18,S*.05);
  // Boots
  ctx.fillStyle='#223344';
  ctx.beginPath(); ctx.ellipse(cx-S*.1,cy+S*.28,S*.1,S*.055,0,0,Math.PI*2); ctx.fill();
  ctx.beginPath(); ctx.ellipse(cx+S*.1,cy+S*.28,S*.1,S*.055,0,0,Math.PI*2); ctx.fill();
}

function drawMarauder(ctx, S, dir, teamColor) {
  const cx = S/2, cy = S/2 + 2;
  // Outer glow — heavier green-grey
  const grd = ctx.createRadialGradient(cx,cy,S*.06,cx,cy,S*.46);
  grd.addColorStop(0,'rgba(90,110,90,0.3)'); grd.addColorStop(1,'rgba(0,0,0,0)');
  ctx.fillStyle=grd; ctx.beginPath(); ctx.ellipse(cx,cy,S*.46,S*.46,0,0,Math.PI*2); ctx.fill();

  if (dir === 2) { // BACK
    const b = ctx.createRadialGradient(cx+S*.08,cy-S*.06,S*.04,cx,cy,S*.34);
    b.addColorStop(0,'#778866'); b.addColorStop(.6,'#445544'); b.addColorStop(1,'#1a221a');
    ctx.fillStyle=b; ctx.beginPath(); ctx.ellipse(cx,cy+S*.04,S*.34,S*.3,0,0,Math.PI*2); ctx.fill();
    // Wide shoulder pads
    ctx.fillStyle='#556644';
    ctx.beginPath(); ctx.ellipse(cx-S*.3,cy-S*.06,S*.12,S*.09,0,0,Math.PI*2); ctx.fill();
    ctx.beginPath(); ctx.ellipse(cx+S*.3,cy-S*.06,S*.12,S*.09,0,0,Math.PI*2); ctx.fill();
    // Knee plates in teamColor
    ctx.fillStyle=teamColor;
    ctx.beginPath(); ctx.ellipse(cx-S*.12,cy+S*.22,S*.1,S*.06,0,0,Math.PI*2); ctx.fill();
    ctx.beginPath(); ctx.ellipse(cx+S*.12,cy+S*.22,S*.1,S*.06,0,0,Math.PI*2); ctx.fill();
    // Dual grenade launchers visible from back
    ctx.fillStyle='#222';
    ctx.fillRect(cx-S*.06,cy-S*.34,S*.12,S*.18);
    // Boots
    ctx.fillStyle='#223311';
    ctx.beginPath(); ctx.ellipse(cx-S*.12,cy+S*.3,S*.12,S*.06,0,0,Math.PI*2); ctx.fill();
    ctx.beginPath(); ctx.ellipse(cx+S*.12,cy+S*.3,S*.12,S*.06,0,0,Math.PI*2); ctx.fill();
    return;
  }
  if (dir === 1 || dir === 3) { // SIDE
    const flip = dir===3 ? -1 : 1;
    // Heavy body
    const b = ctx.createRadialGradient(cx+flip*S*.06,cy-S*.04,S*.04,cx,cy,S*.32);
    b.addColorStop(0,'#778866'); b.addColorStop(.5,'#445544'); b.addColorStop(1,'#1a221a');
    ctx.fillStyle=b; ctx.beginPath(); ctx.ellipse(cx,cy,S*.32,S*.28,0,0,Math.PI*2); ctx.fill();
    // Angular helmet
    const h = ctx.createRadialGradient(cx+flip*S*.04,cy-S*.2,S*.02,cx,cy-S*.14,S*.2);
    h.addColorStop(0,'#778866'); h.addColorStop(.6,'#445544'); h.addColorStop(1,'#1a221a');
    ctx.fillStyle=h; ctx.beginPath(); ctx.ellipse(cx,cy-S*.14,S*.2,S*.18,0,0,Math.PI*2); ctx.fill();
    // Visor slit in teamColor
    ctx.fillStyle=hexToRgba(teamColor, 0.8);
    ctx.beginPath(); ctx.ellipse(cx+flip*S*.1,cy-S*.16,S*.09,S*.035,0,0,Math.PI*2); ctx.fill();
    // Visible shoulder pad
    ctx.fillStyle='#556644';
    ctx.beginPath(); ctx.ellipse(cx+flip*S*.28,cy-S*.06,S*.11,S*.08,0,0,Math.PI*2); ctx.fill();
    // Dual launchers
    ctx.fillStyle='#222';
    ctx.fillRect(cx+flip*S*.18,cy-S*.06,flip*S*.3,S*.07);
    ctx.fillRect(cx+flip*S*.18,cy+S*.02,flip*S*.3,S*.07);
    // Knee plate in teamColor
    ctx.fillStyle=teamColor;
    ctx.beginPath(); ctx.ellipse(cx,cy+S*.22,S*.1,S*.06,0,0,Math.PI*2); ctx.fill();
    // Boot
    ctx.fillStyle='#223311';
    ctx.beginPath(); ctx.ellipse(cx,cy+S*.3,S*.13,S*.06,0,0,Math.PI*2); ctx.fill();
    return;
  }
  // FRONT
  const b = ctx.createRadialGradient(cx-S*.08,cy-S*.06,S*.04,cx,cy,S*.36);
  b.addColorStop(0,'#889977'); b.addColorStop(.5,'#445544'); b.addColorStop(1,'#1a221a');
  ctx.fillStyle=b; ctx.beginPath(); ctx.ellipse(cx,cy+S*.04,S*.36,S*.3,0,0,Math.PI*2); ctx.fill();
  // Wide shoulder pads
  ctx.fillStyle='#667755';
  ctx.beginPath(); ctx.ellipse(cx-S*.32,cy-S*.04,S*.13,S*.09,0,0,Math.PI*2); ctx.fill();
  ctx.beginPath(); ctx.ellipse(cx+S*.32,cy-S*.04,S*.13,S*.09,0,0,Math.PI*2); ctx.fill();
  // Helmet
  const h = ctx.createRadialGradient(cx-S*.04,cy-S*.2,S*.02,cx,cy-S*.16,S*.22);
  h.addColorStop(0,'#889977'); h.addColorStop(.6,'#445544'); h.addColorStop(1,'#0d1108');
  ctx.fillStyle=h; ctx.beginPath(); ctx.ellipse(cx,cy-S*.16,S*.22,S*.2,0,0,Math.PI*2); ctx.fill();
  // Visor slit in teamColor (narrower than Marine visor)
  ctx.fillStyle=hexToRgba(teamColor, 0.85);
  ctx.beginPath(); ctx.ellipse(cx,cy-S*.18,S*.14,S*.04,0,0,Math.PI*2); ctx.fill();
  // Chin guard
  ctx.fillStyle='#334433';
  ctx.beginPath(); ctx.ellipse(cx,cy-S*.08,S*.1,S*.06,0,0,Math.PI*2); ctx.fill();
  // Dual Punisher grenade launchers
  ctx.fillStyle='#222';
  ctx.fillRect(cx+S*.2,cy-S*.07,S*.24,S*.07);
  ctx.fillRect(cx+S*.2,cy+S*.01,S*.24,S*.07);
  ctx.fillStyle='#444';
  ctx.beginPath(); ctx.ellipse(cx+S*.46,cy-S*.035,S*.06,S*.05,0,0,Math.PI*2); ctx.fill();
  ctx.beginPath(); ctx.ellipse(cx+S*.46,cy+S*.045,S*.06,S*.05,0,0,Math.PI*2); ctx.fill();
  // Knee plates in teamColor
  ctx.fillStyle=teamColor;
  ctx.beginPath(); ctx.ellipse(cx-S*.14,cy+S*.22,S*.11,S*.062,0,0,Math.PI*2); ctx.fill();
  ctx.beginPath(); ctx.ellipse(cx+S*.14,cy+S*.22,S*.11,S*.062,0,0,Math.PI*2); ctx.fill();
  // Boots
  ctx.fillStyle='#223311';
  ctx.beginPath(); ctx.ellipse(cx-S*.12,cy+S*.3,S*.12,S*.06,0,0,Math.PI*2); ctx.fill();
  ctx.beginPath(); ctx.ellipse(cx+S*.12,cy+S*.3,S*.12,S*.06,0,0,Math.PI*2); ctx.fill();
}

function drawMedivac(ctx, S, dir, teamColor) {
  const cx = S/2, cy = S/2;
  // Hover glow
  const grd = ctx.createRadialGradient(cx,cy,S*.06,cx,cy,S*.46);
  grd.addColorStop(0,'rgba(180,200,220,0.28)'); grd.addColorStop(1,'rgba(0,0,0,0)');
  ctx.fillStyle=grd; ctx.beginPath(); ctx.ellipse(cx,cy,S*.46,S*.46,0,0,Math.PI*2); ctx.fill();

  if (dir === 2) { // BACK — engine pods visible
    ctx.fillStyle='#7788aa'; ctx.beginPath(); ctx.ellipse(cx,cy,S*.36,S*.2,0,0,Math.PI*2); ctx.fill();
    // Engine pods
    ctx.fillStyle='#556688';
    ctx.beginPath(); ctx.ellipse(cx-S*.3,cy+S*.04,S*.1,S*.08,0.3,0,Math.PI*2); ctx.fill();
    ctx.beginPath(); ctx.ellipse(cx+S*.3,cy+S*.04,S*.1,S*.08,-0.3,0,Math.PI*2); ctx.fill();
    // Engine glow in teamColor
    ctx.fillStyle=hexToRgba(teamColor, 0.8);
    ctx.beginPath(); ctx.ellipse(cx-S*.3,cy+S*.09,S*.07,S*.05,0.3,0,Math.PI*2); ctx.fill();
    ctx.beginPath(); ctx.ellipse(cx+S*.3,cy+S*.09,S*.07,S*.05,-0.3,0,Math.PI*2); ctx.fill();
    // Tail fin
    ctx.fillStyle='#445577';
    ctx.fillRect(cx-S*.04,cy-S*.24,S*.08,S*.16);
    // Running lights in teamColor
    ctx.fillStyle=teamColor;
    ctx.beginPath(); ctx.ellipse(cx-S*.36,cy-S*.04,S*.03,S*.03,0,0,Math.PI*2); ctx.fill();
    ctx.beginPath(); ctx.ellipse(cx+S*.36,cy-S*.04,S*.03,S*.03,0,0,Math.PI*2); ctx.fill();
    return;
  }
  if (dir === 1 || dir === 3) { // SIDE
    const flip = dir===3 ? -1 : 1;
    // Far engine pod
    ctx.fillStyle='#445577';
    ctx.beginPath(); ctx.ellipse(cx-flip*S*.28,cy+S*.04,S*.1,S*.08,flip*0.3,0,Math.PI*2); ctx.fill();
    ctx.fillStyle=hexToRgba(teamColor, 0.7);
    ctx.beginPath(); ctx.ellipse(cx-flip*S*.28,cy+S*.09,S*.06,S*.04,flip*0.3,0,Math.PI*2); ctx.fill();
    // Main hull
    ctx.fillStyle='#8899bb'; ctx.beginPath(); ctx.ellipse(cx,cy-S*.02,S*.4,S*.18,0,0,Math.PI*2); ctx.fill();
    // Red cross — canonical, never team-coloured
    ctx.fillStyle='rgba(255,60,60,0.9)';
    ctx.fillRect(cx-S*.04,cy-S*.12,S*.08,S*.24);
    ctx.fillRect(cx-S*.1,cy-S*.04,S*.2,S*.08);
    // Cockpit
    ctx.fillStyle='rgba(100,200,255,0.6)';
    ctx.beginPath(); ctx.ellipse(cx+flip*S*.18,cy-S*.04,S*.08,S*.07,0,0,Math.PI*2); ctx.fill();
    // Near engine pod
    ctx.fillStyle='#556688';
    ctx.beginPath(); ctx.ellipse(cx+flip*S*.3,cy+S*.04,S*.1,S*.08,-flip*0.3,0,Math.PI*2); ctx.fill();
    ctx.fillStyle=hexToRgba(teamColor, 0.75);
    ctx.beginPath(); ctx.ellipse(cx+flip*S*.3,cy+S*.09,S*.07,S*.05,-flip*0.3,0,Math.PI*2); ctx.fill();
    // Running light
    ctx.fillStyle=teamColor;
    ctx.beginPath(); ctx.ellipse(cx+flip*S*.4,cy-S*.04,S*.03,S*.03,0,0,Math.PI*2); ctx.fill();
    return;
  }
  // FRONT — nose view
  // Engine pods
  ctx.fillStyle='#556688';
  ctx.beginPath(); ctx.ellipse(cx-S*.3,cy+S*.06,S*.1,S*.07,0.3,0,Math.PI*2); ctx.fill();
  ctx.beginPath(); ctx.ellipse(cx+S*.3,cy+S*.06,S*.1,S*.07,-0.3,0,Math.PI*2); ctx.fill();
  // Engine glow in teamColor
  ctx.fillStyle=hexToRgba(teamColor, 0.8);
  ctx.beginPath(); ctx.ellipse(cx-S*.3,cy+S*.1,S*.06,S*.04,0.3,0,Math.PI*2); ctx.fill();
  ctx.beginPath(); ctx.ellipse(cx+S*.3,cy+S*.1,S*.06,S*.04,-0.3,0,Math.PI*2); ctx.fill();
  // Main hull
  ctx.fillStyle='#8899bb'; ctx.beginPath(); ctx.ellipse(cx,cy,S*.36,S*.2,0,0,Math.PI*2); ctx.fill();
  // Red cross — canonical medical cross, never team-coloured
  ctx.fillStyle='rgba(255,60,60,0.9)';
  ctx.fillRect(cx-S*.04,cy-S*.14,S*.08,S*.28);
  ctx.fillRect(cx-S*.12,cy-S*.06,S*.24,S*.08);
  // Cockpit window
  ctx.fillStyle='rgba(100,200,255,0.6)';
  ctx.beginPath(); ctx.ellipse(cx-S*.14,cy-S*.04,S*.08,S*.06,0,0,Math.PI*2); ctx.fill();
  ctx.fillStyle='rgba(200,240,255,0.4)';
  ctx.beginPath(); ctx.ellipse(cx-S*.16,cy-S*.06,S*.03,S*.02,-0.3,0,Math.PI*2); ctx.fill();
  // Running lights in teamColor
  ctx.fillStyle=teamColor;
  ctx.beginPath(); ctx.ellipse(cx-S*.36,cy-S*.02,S*.03,S*.03,0,0,Math.PI*2); ctx.fill();
  ctx.beginPath(); ctx.ellipse(cx+S*.36,cy-S*.02,S*.03,S*.03,0,0,Math.PI*2); ctx.fill();
  // Hover ring
  ctx.strokeStyle=hexToRgba(teamColor, 0.35); ctx.lineWidth=2;
  ctx.beginPath(); ctx.ellipse(cx,cy,S*.42,S*.26,0,0,Math.PI*2); ctx.stroke();
}

function drawZergling(ctx, S, dir, teamColor) {
  // dir=3 is mirror of dir=1
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawZergling(ctx, S, 1, teamColor);
    ctx.restore(); return;
  }
  const cx = S / 2, cy = S * 0.52;

  if (dir === 0 || dir === 2) {
    // Legs drawn first (behind body)
    ctx.strokeStyle = '#5c1a6e'; ctx.lineWidth = 3;
    [[-0.28, 0.14], [-0.14, 0.2], [0.14, 0.2], [0.28, 0.14]].forEach(([dx, dy]) => {
      ctx.beginPath();
      ctx.moveTo(cx + dx * S * 0.6, cy + S * 0.08);
      ctx.lineTo(cx + dx * S, cy + dy * S + S * 0.14);
      ctx.stroke();
    });
    // Carapace body
    ctx.fillStyle = '#2a0a3a';
    ctx.beginPath(); ctx.ellipse(cx, cy, S * 0.22, S * 0.2, 0, 0, Math.PI * 2); ctx.fill();
    // Dorsal plates
    ctx.fillStyle = '#8b3a9e';
    [[-0.08, -0.08], [0, -0.12], [0.08, -0.08]].forEach(([dx, dy]) => {
      ctx.beginPath(); ctx.ellipse(cx + dx * S, cy + dy * S, S * 0.055, S * 0.04, 0, 0, Math.PI * 2); ctx.fill();
    });
    if (dir === 0) {
      // Flesh belly
      ctx.fillStyle = '#5c1a6e';
      ctx.beginPath(); ctx.ellipse(cx, cy + S * 0.06, S * 0.13, S * 0.1, 0, 0, Math.PI * 2); ctx.fill();
      // Scythe blades
      ctx.strokeStyle = '#8b3a9e'; ctx.lineWidth = 3;
      ctx.beginPath();
      ctx.moveTo(cx - S * 0.14, cy - S * 0.1);
      ctx.quadraticCurveTo(cx - S * 0.34, cy - S * 0.22, cx - S * 0.28, cy - S * 0.38);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(cx + S * 0.14, cy - S * 0.1);
      ctx.quadraticCurveTo(cx + S * 0.34, cy - S * 0.22, cx + S * 0.28, cy - S * 0.38);
      ctx.stroke();
      // Head
      ctx.fillStyle = '#2a0a3a';
      ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.22, S * 0.12, S * 0.11, 0, 0, Math.PI * 2); ctx.fill();
      // Eyes
      ctx.fillStyle = '#ffe066';
      ctx.beginPath(); ctx.arc(cx - S * 0.05, cy - S * 0.24, S * 0.03, 0, Math.PI * 2); ctx.fill();
      ctx.beginPath(); ctx.arc(cx + S * 0.05, cy - S * 0.24, S * 0.03, 0, Math.PI * 2); ctx.fill();
      // Belly bio-sac
      const g = ctx.createRadialGradient(cx, cy + S * 0.09, 2, cx, cy + S * 0.09, S * 0.09);
      g.addColorStop(0, teamColor + 'ff'); g.addColorStop(0.55, teamColor + '88'); g.addColorStop(1, teamColor + '00');
      ctx.fillStyle = g; ctx.shadowColor = teamColor; ctx.shadowBlur = 8;
      ctx.beginPath(); ctx.ellipse(cx, cy + S * 0.09, S * 0.08, S * 0.07, 0, 0, Math.PI * 2); ctx.fill();
      // Shoulder sacs
      [cx - S * 0.18, cx + S * 0.18].forEach(x => {
        const g2 = ctx.createRadialGradient(x, cy, 1, x, cy, S * 0.05);
        g2.addColorStop(0, teamColor + 'cc'); g2.addColorStop(1, teamColor + '00');
        ctx.fillStyle = g2; ctx.beginPath(); ctx.arc(x, cy, S * 0.045, 0, Math.PI * 2); ctx.fill();
      });
      ctx.shadowBlur = 0;
    } else {
      // Back: carapace highlight, back of head
      ctx.fillStyle = '#8b3a9e';
      ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.05, S * 0.15, S * 0.1, 0, 0, Math.PI * 2); ctx.fill();
      ctx.fillStyle = '#2a0a3a';
      ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.22, S * 0.11, S * 0.1, 0, 0, Math.PI * 2); ctx.fill();
      ctx.fillStyle = '#8b3a9e';
      ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.24, S * 0.07, S * 0.05, 0, 0, Math.PI * 2); ctx.fill();
    }
  } else {
    // dir=1: right side profile
    ctx.fillStyle = '#2a0a3a';
    ctx.beginPath(); ctx.ellipse(cx, cy, S * 0.28, S * 0.17, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = '#8b3a9e';
    ctx.beginPath(); ctx.ellipse(cx - S * 0.04, cy - S * 0.1, S * 0.2, S * 0.07, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = '#5c1a6e';
    ctx.beginPath(); ctx.ellipse(cx + S * 0.04, cy + S * 0.05, S * 0.16, S * 0.07, 0, 0, Math.PI * 2); ctx.fill();
    // Legs
    ctx.strokeStyle = '#5c1a6e'; ctx.lineWidth = 3;
    [[cx - S * 0.1, cy + S * 0.1, cx - S * 0.2, cy + S * 0.28],
     [cx + S * 0.08, cy + S * 0.1, cx + S * 0.16, cy + S * 0.28]].forEach(([x1, y1, x2, y2]) => {
      ctx.beginPath(); ctx.moveTo(x1, y1); ctx.lineTo(x2, y2); ctx.stroke();
    });
    // Head
    ctx.fillStyle = '#2a0a3a';
    ctx.beginPath(); ctx.ellipse(cx - S * 0.2, cy - S * 0.04, S * 0.1, S * 0.09, 0, 0, Math.PI * 2); ctx.fill();
    // Blade
    ctx.strokeStyle = '#8b3a9e'; ctx.lineWidth = 3;
    ctx.beginPath();
    ctx.moveTo(cx - S * 0.22, cy - S * 0.09);
    ctx.quadraticCurveTo(cx - S * 0.4, cy - S * 0.22, cx - S * 0.32, cy - S * 0.36);
    ctx.stroke();
    // Eye
    ctx.fillStyle = '#ffe066';
    ctx.beginPath(); ctx.arc(cx - S * 0.24, cy - S * 0.07, S * 0.028, 0, Math.PI * 2); ctx.fill();
    // Belly bio-sac
    const g = ctx.createRadialGradient(cx + S * 0.06, cy + S * 0.05, 1, cx + S * 0.06, cy + S * 0.05, S * 0.07);
    g.addColorStop(0, teamColor + 'ff'); g.addColorStop(0.5, teamColor + '77'); g.addColorStop(1, teamColor + '00');
    ctx.fillStyle = g; ctx.shadowColor = teamColor; ctx.shadowBlur = 7;
    ctx.beginPath(); ctx.ellipse(cx + S * 0.06, cy + S * 0.05, S * 0.06, S * 0.055, 0, 0, Math.PI * 2); ctx.fill();
    ctx.shadowBlur = 0;
  }
}

function drawRoach(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawRoach(ctx, S, 1, teamColor);
    ctx.restore(); return;
  }
  const cx = S / 2, cy = S * 0.55;

  if (dir === 0 || dir === 2) {
    // Legs behind body
    ctx.strokeStyle = '#5c1a6e'; ctx.lineWidth = 3.5;
    [[-0.3, 0.12], [-0.18, 0.18], [-0.06, 0.2], [0.06, 0.2], [0.18, 0.18], [0.3, 0.12]].forEach(([dx, dy]) => {
      ctx.beginPath();
      ctx.moveTo(cx + dx * S * 0.7, cy + S * 0.04);
      ctx.lineTo(cx + dx * S, cy + dy * S + S * 0.08);
      ctx.stroke();
    });
    // Wide low body
    ctx.fillStyle = '#2a0a3a';
    ctx.beginPath(); ctx.ellipse(cx, cy, S * 0.34, S * 0.22, 0, 0, Math.PI * 2); ctx.fill();
    // Armour plates
    ctx.fillStyle = '#8b3a9e';
    ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.06, S * 0.28, S * 0.14, 0, 0, Math.PI * 2); ctx.fill();
    if (dir === 0) {
      // Head — heavy jaw
      ctx.fillStyle = '#2a0a3a';
      ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.26, S * 0.18, S * 0.14, 0, 0, Math.PI * 2); ctx.fill();
      ctx.fillStyle = '#5c1a6e';
      ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.16, S * 0.14, S * 0.07, 0, 0, Math.PI * 2); ctx.fill();
      // Eyes
      ctx.fillStyle = '#ffe066';
      ctx.beginPath(); ctx.arc(cx - S * 0.1, cy - S * 0.3, S * 0.035, 0, Math.PI * 2); ctx.fill();
      ctx.beginPath(); ctx.arc(cx + S * 0.1, cy - S * 0.3, S * 0.035, 0, Math.PI * 2); ctx.fill();
    } else {
      // Back of head
      ctx.fillStyle = '#2a0a3a';
      ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.24, S * 0.16, S * 0.12, 0, 0, Math.PI * 2); ctx.fill();
    }
    // Acid gland sacs (3 on carapace ridge)
    [[cx - S * 0.12, cy - S * 0.06], [cx, cy - S * 0.1], [cx + S * 0.12, cy - S * 0.06]].forEach(([x, y]) => {
      const g = ctx.createRadialGradient(x, y, 1, x, y, S * 0.065);
      g.addColorStop(0, teamColor + 'ff'); g.addColorStop(0.5, teamColor + '88'); g.addColorStop(1, teamColor + '00');
      ctx.fillStyle = g; ctx.shadowColor = teamColor; ctx.shadowBlur = 8;
      ctx.beginPath(); ctx.ellipse(x, y, S * 0.058, S * 0.05, 0, 0, Math.PI * 2); ctx.fill();
    });
    ctx.shadowBlur = 0;
  } else {
    // dir=1: right side profile — hunched low
    ctx.strokeStyle = '#5c1a6e'; ctx.lineWidth = 3.5;
    [[cx - S * 0.15, cy + S * 0.06, cx - S * 0.3, cy + S * 0.22],
     [cx,            cy + S * 0.1,  cx,            cy + S * 0.28],
     [cx + S * 0.15, cy + S * 0.06, cx + S * 0.28, cy + S * 0.22]].forEach(([x1, y1, x2, y2]) => {
      ctx.beginPath(); ctx.moveTo(x1, y1); ctx.lineTo(x2, y2); ctx.stroke();
    });
    ctx.fillStyle = '#2a0a3a';
    ctx.beginPath(); ctx.ellipse(cx, cy, S * 0.32, S * 0.2, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = '#8b3a9e';
    ctx.beginPath(); ctx.ellipse(cx - S * 0.04, cy - S * 0.08, S * 0.26, S * 0.12, 0, 0, Math.PI * 2); ctx.fill();
    // Head
    ctx.fillStyle = '#2a0a3a';
    ctx.beginPath(); ctx.ellipse(cx - S * 0.22, cy - S * 0.1, S * 0.12, S * 0.1, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = '#5c1a6e';
    ctx.beginPath(); ctx.ellipse(cx - S * 0.22, cy - S * 0.04, S * 0.1, S * 0.055, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = '#ffe066';
    ctx.beginPath(); ctx.arc(cx - S * 0.28, cy - S * 0.14, S * 0.03, 0, Math.PI * 2); ctx.fill();
    // Two visible sacs
    [[cx - S * 0.06, cy - S * 0.09], [cx + S * 0.1, cy - S * 0.09]].forEach(([x, y]) => {
      const g = ctx.createRadialGradient(x, y, 1, x, y, S * 0.062);
      g.addColorStop(0, teamColor + 'ff'); g.addColorStop(0.5, teamColor + '77'); g.addColorStop(1, teamColor + '00');
      ctx.fillStyle = g; ctx.shadowColor = teamColor; ctx.shadowBlur = 7;
      ctx.beginPath(); ctx.ellipse(x, y, S * 0.055, S * 0.048, 0, 0, Math.PI * 2); ctx.fill();
    });
    ctx.shadowBlur = 0;
  }
}

function drawHydralisk(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawHydralisk(ctx, S, 1, teamColor);
    ctx.restore(); return;
  }
  const cx = S / 2, cy = S * 0.5;

  if (dir === 0 || dir === 2) {
    // Snake lower body (drawn first)
    ctx.strokeStyle = '#2a0a3a'; ctx.lineWidth = 18;
    ctx.beginPath();
    ctx.moveTo(cx - S * 0.06, cy + S * 0.44);
    ctx.quadraticCurveTo(cx + S * 0.08, cy + S * 0.28, cx, cy + S * 0.12);
    ctx.stroke();
    ctx.strokeStyle = '#5c1a6e'; ctx.lineWidth = 10;
    ctx.beginPath();
    ctx.moveTo(cx - S * 0.06, cy + S * 0.44);
    ctx.quadraticCurveTo(cx + S * 0.08, cy + S * 0.28, cx, cy + S * 0.12);
    ctx.stroke();
    // Cobra frill
    ctx.fillStyle = '#5c1a6e';
    if (dir === 0) {
      ctx.beginPath();
      ctx.moveTo(cx, cy - S * 0.04);
      ctx.quadraticCurveTo(cx - S * 0.32, cy - S * 0.02, cx - S * 0.26, cy + S * 0.14);
      ctx.lineTo(cx - S * 0.14, cy + S * 0.12); ctx.closePath(); ctx.fill();
      ctx.beginPath();
      ctx.moveTo(cx, cy - S * 0.04);
      ctx.quadraticCurveTo(cx + S * 0.32, cy - S * 0.02, cx + S * 0.26, cy + S * 0.14);
      ctx.lineTo(cx + S * 0.14, cy + S * 0.12); ctx.closePath(); ctx.fill();
    } else {
      ctx.beginPath();
      ctx.moveTo(cx, cy);
      ctx.quadraticCurveTo(cx - S * 0.28, cy - S * 0.04, cx - S * 0.22, cy + S * 0.12);
      ctx.lineTo(cx - S * 0.12, cy + S * 0.1); ctx.closePath(); ctx.fill();
      ctx.beginPath();
      ctx.moveTo(cx, cy);
      ctx.quadraticCurveTo(cx + S * 0.28, cy - S * 0.04, cx + S * 0.22, cy + S * 0.12);
      ctx.lineTo(cx + S * 0.12, cy + S * 0.1); ctx.closePath(); ctx.fill();
    }
    // Torso
    ctx.fillStyle = '#2a0a3a';
    ctx.beginPath(); ctx.ellipse(cx, cy + S * 0.06, S * 0.18, S * 0.2, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = '#8b3a9e';
    ctx.beginPath(); ctx.ellipse(cx, cy + S * 0.02, S * 0.12, S * 0.12, 0, 0, Math.PI * 2); ctx.fill();
    // Head
    ctx.fillStyle = '#2a0a3a';
    ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.12, S * 0.13, S * 0.12, 0, 0, Math.PI * 2); ctx.fill();
    if (dir === 0) {
      ctx.fillStyle = '#ffe066';
      ctx.beginPath(); ctx.arc(cx - S * 0.055, cy - S * 0.13, S * 0.035, 0, Math.PI * 2); ctx.fill();
      ctx.beginPath(); ctx.arc(cx + S * 0.055, cy - S * 0.13, S * 0.035, 0, Math.PI * 2); ctx.fill();
    }
    // Throat sac
    const g = ctx.createRadialGradient(cx, cy + S * 0.01, 2, cx, cy + S * 0.01, S * 0.085);
    g.addColorStop(0, teamColor + 'ff'); g.addColorStop(0.55, teamColor + '88'); g.addColorStop(1, teamColor + '00');
    ctx.fillStyle = g; ctx.shadowColor = teamColor; ctx.shadowBlur = 9;
    ctx.beginPath(); ctx.ellipse(cx, cy + S * 0.01, S * 0.075, S * 0.07, 0, 0, Math.PI * 2); ctx.fill();
    // Shoulder pustules
    [cx - S * 0.16, cx + S * 0.16].forEach(x => {
      const g2 = ctx.createRadialGradient(x, cy + S * 0.06, 1, x, cy + S * 0.06, S * 0.055);
      g2.addColorStop(0, teamColor + 'dd'); g2.addColorStop(1, teamColor + '00');
      ctx.fillStyle = g2; ctx.beginPath(); ctx.arc(x, cy + S * 0.06, S * 0.05, 0, Math.PI * 2); ctx.fill();
    });
    ctx.shadowBlur = 0;
  } else {
    // dir=1: side profile
    ctx.strokeStyle = '#2a0a3a'; ctx.lineWidth = 16;
    ctx.beginPath();
    ctx.moveTo(cx + S * 0.08, cy + S * 0.44);
    ctx.quadraticCurveTo(cx + S * 0.14, cy + S * 0.28, cx, cy + S * 0.14);
    ctx.stroke();
    ctx.strokeStyle = '#5c1a6e'; ctx.lineWidth = 8;
    ctx.beginPath();
    ctx.moveTo(cx + S * 0.08, cy + S * 0.44);
    ctx.quadraticCurveTo(cx + S * 0.14, cy + S * 0.28, cx, cy + S * 0.14);
    ctx.stroke();
    // Side frill
    ctx.fillStyle = '#5c1a6e';
    ctx.beginPath();
    ctx.moveTo(cx, cy - S * 0.02);
    ctx.quadraticCurveTo(cx + S * 0.28, cy - S * 0.1, cx + S * 0.24, cy + S * 0.14);
    ctx.lineTo(cx + S * 0.12, cy + S * 0.12); ctx.closePath(); ctx.fill();
    // Torso
    ctx.fillStyle = '#2a0a3a';
    ctx.beginPath(); ctx.ellipse(cx, cy + S * 0.06, S * 0.16, S * 0.2, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = '#8b3a9e';
    ctx.beginPath(); ctx.ellipse(cx - S * 0.02, cy + S * 0.02, S * 0.1, S * 0.12, 0, 0, Math.PI * 2); ctx.fill();
    // Head
    ctx.fillStyle = '#2a0a3a';
    ctx.beginPath(); ctx.ellipse(cx - S * 0.04, cy - S * 0.12, S * 0.12, S * 0.11, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = '#ffe066';
    ctx.beginPath(); ctx.arc(cx - S * 0.1, cy - S * 0.14, S * 0.032, 0, Math.PI * 2); ctx.fill();
    // Throat sac
    const g = ctx.createRadialGradient(cx, cy + S * 0.01, 2, cx, cy + S * 0.01, S * 0.08);
    g.addColorStop(0, teamColor + 'ff'); g.addColorStop(0.5, teamColor + '88'); g.addColorStop(1, teamColor + '00');
    ctx.fillStyle = g; ctx.shadowColor = teamColor; ctx.shadowBlur = 8;
    ctx.beginPath(); ctx.ellipse(cx, cy + S * 0.01, S * 0.07, S * 0.065, 0, 0, Math.PI * 2); ctx.fill();
    // One shoulder pustule
    const g2 = ctx.createRadialGradient(cx - S * 0.14, cy + S * 0.08, 1, cx - S * 0.14, cy + S * 0.08, S * 0.05);
    g2.addColorStop(0, teamColor + 'dd'); g2.addColorStop(1, teamColor + '00');
    ctx.fillStyle = g2; ctx.beginPath(); ctx.arc(cx - S * 0.14, cy + S * 0.08, S * 0.045, 0, Math.PI * 2); ctx.fill();
    ctx.shadowBlur = 0;
  }
}

// Populated by initSpriteMaterials() — do not read before init() runs
const UNIT_MATS = {};

function initSpriteMaterials() {
  UNIT_MATS['PROBE_F']    = makeDirTextures(drawProbe,   TEAM_COLOR_FRIENDLY);
  UNIT_MATS['PROBE_E']    = makeDirTextures(drawProbe,   TEAM_COLOR_ENEMY);
  UNIT_MATS['ZEALOT_F']   = makeDirTextures(drawZealot,  TEAM_COLOR_FRIENDLY);
  UNIT_MATS['ZEALOT_E']   = makeDirTextures(drawZealot,  TEAM_COLOR_ENEMY);
  UNIT_MATS['STALKER_F']  = makeDirTextures(drawStalker, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['STALKER_E']  = makeDirTextures(drawStalker, TEAM_COLOR_ENEMY);
  UNIT_MATS['MARINE_F']    = makeDirTextures(drawMarine,    TEAM_COLOR_FRIENDLY);
  UNIT_MATS['MARINE_E']    = makeDirTextures(drawMarine,    TEAM_COLOR_ENEMY);
  UNIT_MATS['MARAUDER_F']  = makeDirTextures(drawMarauder,  TEAM_COLOR_FRIENDLY);
  UNIT_MATS['MARAUDER_E']  = makeDirTextures(drawMarauder,  TEAM_COLOR_ENEMY);
  UNIT_MATS['MEDIVAC_F']   = makeDirTextures(drawMedivac,   TEAM_COLOR_FRIENDLY);
  UNIT_MATS['MEDIVAC_E']   = makeDirTextures(drawMedivac,   TEAM_COLOR_ENEMY);
  UNIT_MATS['ZERGLING_F']  = makeDirTextures(drawZergling,  TEAM_COLOR_FRIENDLY);
  UNIT_MATS['ZERGLING_E']  = makeDirTextures(drawZergling,  TEAM_COLOR_ENEMY);
  UNIT_MATS['ROACH_F']      = makeDirTextures(drawRoach,      TEAM_COLOR_FRIENDLY);
  UNIT_MATS['ROACH_E']      = makeDirTextures(drawRoach,      TEAM_COLOR_ENEMY);
  UNIT_MATS['HYDRALISK_F']  = makeDirTextures(drawHydralisk,  TEAM_COLOR_FRIENDLY);
  UNIT_MATS['HYDRALISK_E']  = makeDirTextures(drawHydralisk,  TEAM_COLOR_ENEMY);
  UNIT_MATS['UNKNOWN_F']    = makeDirTextures(drawEnemy,      TEAM_COLOR_FRIENDLY);
  UNIT_MATS['UNKNOWN_E']    = makeDirTextures(drawEnemy,      TEAM_COLOR_ENEMY);
}

function updateSpriteDirs() {
  [unitSprites, enemySprites, stagingSprites].forEach(map => {
    map.forEach((sp, tag) => {
      const mats = sp.userData.mats;
      if (!mats) return;
      const facing = unitFacings.get(tag) ?? 0;
      sp.material = mats[getDir4(facing, sp.position, camera.position)];
    });
  });
}

function make3dModel(color, emissive) {
  const g = new THREE.Group();
  const body = new THREE.Mesh(
    new THREE.SphereGeometry(TILE*0.38, 16, 12),
    new THREE.MeshLambertMaterial({ color, emissive })
  );
  body.position.y = TILE*0.42; g.add(body);
  const eyeM = new THREE.MeshLambertMaterial({ color: 0xffffff });
  const pupM = new THREE.MeshLambertMaterial({ color: 0x111122 });
  [-0.14, 0.14].forEach(ex => {
    const eye = new THREE.Mesh(new THREE.SphereGeometry(TILE*0.1, 8, 8), eyeM);
    eye.position.set(ex*TILE, TILE*0.52, TILE*0.32); g.add(eye);
    const pup = new THREE.Mesh(new THREE.SphereGeometry(TILE*0.055, 8, 8), pupM);
    pup.position.set(ex*TILE, TILE*0.52, TILE*0.37); g.add(pup);
  });
  return g;
}

function initConfigPanel() {
  const panel     = document.getElementById('config-panel');
  const speedSlider = document.getElementById('cfg-speed');
  const speedVal  = document.getElementById('cfg-speed-val');
  const status    = document.getElementById('cfg-status');

  fetch('/qa/emulated/config')
    .then(r => { if (!r.ok) return null; return r.json(); })
    .then(cfg => {
      if (!cfg || !cfg.active) return;
      panel.style.display = 'block';
      document.getElementById('cfg-wave-frame').value = cfg.waveSpawnFrame;
      document.getElementById('cfg-unit-count').value = cfg.waveUnitCount;
      document.getElementById('cfg-unit-type').value  = cfg.waveUnitType;
      speedSlider.value    = cfg.unitSpeed;
      speedVal.textContent = cfg.unitSpeed;
    })
    .catch(() => {});

  speedSlider.addEventListener('input', () => {
    speedVal.textContent = speedSlider.value;
    sendConfig({ unitSpeed: parseFloat(speedSlider.value) });
  });

  document.getElementById('cfg-apply').addEventListener('click', () => {
    sendConfig(currentConfig())
      .then(() => showStatus('Applied — restart to activate wave'))
      .catch(() => showStatus('Failed', true));
  });

  document.getElementById('cfg-restart').addEventListener('click', () => {
    sendConfig(currentConfig())
      .then(() => fetch('/sc2/start', { method: 'POST' }))
      .then(() => showStatus('Restarted'))
      .catch(() => showStatus('Failed', true));
  });

  function currentConfig() {
    return {
      waveSpawnFrame: parseInt(document.getElementById('cfg-wave-frame').value),
      waveUnitCount:  parseInt(document.getElementById('cfg-unit-count').value),
      waveUnitType:   document.getElementById('cfg-unit-type').value,
      unitSpeed:      parseFloat(speedSlider.value),
    };
  }

  function sendConfig(partial) {
    return fetch('/qa/emulated/config', {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(partial),
    }).then(r => r.json()).catch(() => showStatus('Update failed', true));
  }

  function showStatus(msg, isError=false) {
    status.textContent = msg;
    status.style.color = isError ? '#ff4444' : '#88ff88';
    setTimeout(() => { status.textContent = ''; }, 2500);
  }
}

init();
