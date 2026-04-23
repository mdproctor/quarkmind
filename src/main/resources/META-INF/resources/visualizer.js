// visualizer.js — QuarkMind 3D visualizer (Three.js r128)

const TILE = 0.7;
const TEAM_COLOR_FRIENDLY = '#4488ff';
const TEAM_COLOR_ENEMY    = '#ff4422';
const FLYING_UNITS = new Set([
  'MEDIVAC', 'MUTALISK',
  'VIKING', 'RAVEN', 'BANSHEE', 'LIBERATOR', 'BATTLECRUISER',
  'OBSERVER', 'VOID_RAY', 'CARRIER',
  'CORRUPTOR'
]);
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
  mGround = new THREE.MeshLambertMaterial({ color: 0xb8956a }); // sandy light brown — visible ground
  mWall   = new THREE.MeshLambertMaterial({ color: 0x3a3028 }); // dark stone
  mHigh   = new THREE.MeshLambertMaterial({ color: 0xc8a870 }); // lighter sandy — high ground
  mRamp   = new THREE.MeshLambertMaterial({ color: 0x9a7a50 }); // medium brown — ramps
  lineMat = new THREE.LineBasicMaterial({ color: 0x7a5a30, transparent: true, opacity: 0.5 });
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
    if (typeof drawZergling !== 'undefined') lookup.drawZergling = drawZergling;
    if (typeof drawRoach     !== 'undefined') lookup.drawRoach     = drawRoach;
    if (typeof drawHydralisk !== 'undefined') lookup.drawHydralisk = drawHydralisk;
    if (typeof drawMutalisk  !== 'undefined') lookup.drawMutalisk  = drawMutalisk;
    if (typeof drawGhost     !== 'undefined') lookup.drawGhost     = drawGhost;
    if (typeof drawCyclone   !== 'undefined') lookup.drawCyclone   = drawCyclone;
    if (typeof drawWidowMine  !== 'undefined') lookup.drawWidowMine  = drawWidowMine;
    if (typeof drawSiegeTank  !== 'undefined') lookup.drawSiegeTank  = drawSiegeTank;
    if (typeof drawThor !== 'undefined') lookup.drawThor = drawThor;
    if (typeof drawViking !== 'undefined') lookup.drawViking = drawViking;
    if (typeof drawRaven    !== 'undefined') lookup.drawRaven    = drawRaven;
    if (typeof drawBanshee   !== 'undefined') lookup.drawBanshee   = drawBanshee;
    if (typeof drawLiberator !== 'undefined') lookup.drawLiberator = drawLiberator;
    if (typeof drawBattlecruiser !== 'undefined') lookup.drawBattlecruiser = drawBattlecruiser;
    if (typeof drawSentry !== 'undefined') lookup.drawSentry = drawSentry;
    if (typeof drawAdept !== 'undefined') lookup.drawAdept = drawAdept;
    if (typeof drawDarkTemplar !== 'undefined') lookup.drawDarkTemplar = drawDarkTemplar;
    if (typeof drawHighTemplar !== 'undefined') lookup.drawHighTemplar = drawHighTemplar;
    if (typeof drawDisruptor !== 'undefined') lookup.drawDisruptor = drawDisruptor;
    if (typeof drawImmortal !== 'undefined') lookup.drawImmortal = drawImmortal;
    if (typeof drawArchon   !== 'undefined') lookup.drawArchon   = drawArchon;
    if (typeof drawColossus !== 'undefined') lookup.drawColossus = drawColossus;
    if (typeof drawObserver !== 'undefined') lookup.drawObserver = drawObserver;
    if (typeof drawVoidRay  !== 'undefined') lookup.drawVoidRay  = drawVoidRay;
    if (typeof drawCarrier  !== 'undefined') lookup.drawCarrier  = drawCarrier;
    if (typeof drawRavager !== 'undefined') lookup.drawRavager = drawRavager;
    if (typeof drawInfestor !== 'undefined') lookup.drawInfestor = drawInfestor;
    if (typeof drawLurker   !== 'undefined') lookup.drawLurker   = drawLurker;
    if (typeof drawSwarmHost !== 'undefined') lookup.drawSwarmHost = drawSwarmHost;
    if (typeof drawQueen !== 'undefined') lookup.drawQueen = drawQueen;
    if (typeof drawUltralisk !== 'undefined') lookup.drawUltralisk = drawUltralisk;
    if (typeof drawCorruptor !== 'undefined') lookup.drawCorruptor = drawCorruptor;
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
  scene.add(new THREE.AmbientLight(0x706050, 0.8)); // warm neutral ambient
  const sun = new THREE.DirectionalLight(0xffffff, 1.2);  // neutral white — lets brown read as brown
  sun.position.set(20, 40, 20);
  sun.castShadow = true;
  sun.shadow.mapSize.set(2048, 2048);
  sun.shadow.camera.near = 1; sun.shadow.camera.far = 200;
  sun.shadow.camera.left = -60; sun.shadow.camera.right = 60;
  sun.shadow.camera.top = 60; sun.shadow.camera.bottom = -60;
  scene.add(sun);
  const fill = new THREE.DirectionalLight(0x806040, 0.3); // warm fill from opposite side
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
      color: 0x888888, transparent: true,  // light grey for out-of-vision areas
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
      // Base both sprite and 3D model on TERRAIN_SURFACE_Y so they sit above ground
      // in both mock (TERRAIN_SURFACE_Y=0.08) and emulated (TERRAIN_SURFACE_Y=TILE) profiles.
      const groundY = TERRAIN_SURFACE_Y + TILE * 0.5;
      const flyingY = TERRAIN_SURFACE_Y + TILE * 1.1;
      const unitY   = FLYING_UNITS.has(u.type) ? flyingY : groundY;
      sp.position.set(wp.x, unitY, wp.z);
      group2d.add(sp);
      spriteMap.set(u.tag, sp);

      // 3D sphere model — ground center one sphere-radius above terrain surface
      const g = make3dModel(isEnemy ? 0xcc3322 : 0x4488dd, isEnemy ? 0x330000 : 0x112244);
      g.position.set(wp.x, FLYING_UNITS.has(u.type) ? flyingY : TERRAIN_SURFACE_Y + TILE * 0.4, wp.z);
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

function drawMutalisk(ctx, S, dir, teamColor) {
  // All 4 directions show the manta silhouette — slight rotation per dir for variety
  const cx = S / 2, cy = S / 2;
  const angle = dir === 0 ? 0 : dir === 1 ? Math.PI * 0.06 : dir === 2 ? Math.PI * 0.1 : -Math.PI * 0.06;
  ctx.save();
  ctx.translate(cx, cy); ctx.rotate(angle); ctx.translate(-cx, -cy);

  // Wing membranes (behind body)
  ctx.fillStyle = '#5c1a6e';
  ctx.beginPath();
  ctx.moveTo(cx, cy + S * 0.02);
  ctx.quadraticCurveTo(cx - S * 0.44, cy - S * 0.1, cx - S * 0.46, cy + S * 0.14);
  ctx.quadraticCurveTo(cx - S * 0.28, cy + S * 0.2, cx, cy + S * 0.1);
  ctx.closePath(); ctx.fill();
  ctx.beginPath();
  ctx.moveTo(cx, cy + S * 0.02);
  ctx.quadraticCurveTo(cx + S * 0.44, cy - S * 0.1, cx + S * 0.46, cy + S * 0.14);
  ctx.quadraticCurveTo(cx + S * 0.28, cy + S * 0.2, cx, cy + S * 0.1);
  ctx.closePath(); ctx.fill();
  // Wing veins
  ctx.strokeStyle = '#8b3a9e'; ctx.lineWidth = 1.5; ctx.globalAlpha = 0.5;
  [[cx, cy + S * 0.06, cx - S * 0.36, cy + S * 0.14],
   [cx, cy + S * 0.06, cx - S * 0.24, cy - S * 0.06],
   [cx, cy + S * 0.06, cx + S * 0.36, cy + S * 0.14],
   [cx, cy + S * 0.06, cx + S * 0.24, cy - S * 0.06]].forEach(([x1, y1, x2, y2]) => {
    ctx.beginPath(); ctx.moveTo(x1, y1); ctx.lineTo(x2, y2); ctx.stroke();
  });
  ctx.globalAlpha = 1;
  // Central body
  ctx.fillStyle = '#2a0a3a';
  ctx.beginPath(); ctx.ellipse(cx, cy + S * 0.04, S * 0.1, S * 0.16, 0, 0, Math.PI * 2); ctx.fill();
  // Tail
  ctx.strokeStyle = '#2a0a3a'; ctx.lineWidth = 8;
  ctx.beginPath();
  ctx.moveTo(cx, cy + S * 0.18);
  ctx.quadraticCurveTo(cx + S * 0.06, cy + S * 0.32, cx + S * 0.04, cy + S * 0.44);
  ctx.stroke();
  ctx.strokeStyle = '#5c1a6e'; ctx.lineWidth = 4;
  ctx.beginPath();
  ctx.moveTo(cx, cy + S * 0.18);
  ctx.quadraticCurveTo(cx + S * 0.06, cy + S * 0.32, cx + S * 0.04, cy + S * 0.44);
  ctx.stroke();
  // Head
  ctx.fillStyle = '#8b3a9e';
  ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.12, S * 0.09, S * 0.09, 0, 0, Math.PI * 2); ctx.fill();
  ctx.fillStyle = '#2a0a3a';
  ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.12, S * 0.07, S * 0.07, 0, 0, Math.PI * 2); ctx.fill();
  // Eyes
  ctx.fillStyle = '#ffe066';
  ctx.beginPath(); ctx.arc(cx - S * 0.035, cy - S * 0.14, S * 0.028, 0, Math.PI * 2); ctx.fill();
  ctx.beginPath(); ctx.arc(cx + S * 0.035, cy - S * 0.14, S * 0.028, 0, Math.PI * 2); ctx.fill();
  // Wing-joint bio-sacs
  [[cx - S * 0.18, cy + S * 0.06], [cx + S * 0.18, cy + S * 0.06]].forEach(([x, y]) => {
    const g = ctx.createRadialGradient(x, y, 1, x, y, S * 0.062);
    g.addColorStop(0, teamColor + 'ff'); g.addColorStop(0.5, teamColor + '88'); g.addColorStop(1, teamColor + '00');
    ctx.fillStyle = g; ctx.shadowColor = teamColor; ctx.shadowBlur = 9;
    ctx.beginPath(); ctx.ellipse(x, y, S * 0.055, S * 0.05, 0, 0, Math.PI * 2); ctx.fill();
  });
  ctx.shadowBlur = 0;
  ctx.restore();
}

function drawGhost(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawGhost(ctx, S, 1, teamColor); ctx.restore(); return;
  }
  const cx = S / 2, cy = S / 2 + 2;

  if (dir === 2) {
    ctx.fillStyle = '#2a2a2a';
    ctx.beginPath(); ctx.ellipse(cx, cy, S * 0.2, S * 0.23, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = '#333';
    ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.16, S * 0.14, S * 0.15, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = '#444';
    ctx.fillRect(cx + S * 0.06, cy - S * 0.28, S * 0.04, S * 0.22);
    ctx.fillStyle = '#1a1a1a';
    ctx.beginPath(); ctx.ellipse(cx - S * 0.08, cy + S * 0.25, S * 0.08, S * 0.04, 0, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.ellipse(cx + S * 0.08, cy + S * 0.25, S * 0.08, S * 0.04, 0, 0, Math.PI * 2); ctx.fill();
    return;
  }
  if (dir === 1) {
    const bg = ctx.createRadialGradient(cx - S * 0.04, cy, S * 0.02, cx, cy, S * 0.22);
    bg.addColorStop(0, '#4a4a4a'); bg.addColorStop(1, '#1a1a1a');
    ctx.fillStyle = bg; ctx.beginPath(); ctx.ellipse(cx, cy, S * 0.18, S * 0.23, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = '#333';
    ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.16, S * 0.14, S * 0.16, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = hexToRgba(teamColor, 0.85);
    ctx.shadowColor = teamColor; ctx.shadowBlur = 6;
    ctx.beginPath(); ctx.ellipse(cx + S * 0.07, cy - S * 0.18, S * 0.055, S * 0.022, 0, 0, Math.PI * 2); ctx.fill();
    ctx.shadowBlur = 0;
    ctx.fillStyle = '#555'; ctx.fillRect(cx + S * 0.08, cy - S * 0.04, S * 0.3, S * 0.05);
    ctx.fillStyle = '#333'; ctx.fillRect(cx + S * 0.28, cy - S * 0.02, S * 0.1, S * 0.03);
    ctx.fillStyle = '#1a1a1a';
    ctx.beginPath(); ctx.ellipse(cx, cy + S * 0.25, S * 0.1, S * 0.042, 0, 0, Math.PI * 2); ctx.fill();
    return;
  }
  // FRONT
  const bg = ctx.createRadialGradient(cx - S * 0.05, cy - S * 0.04, S * 0.03, cx, cy, S * 0.25);
  bg.addColorStop(0, '#4a4a4a'); bg.addColorStop(0.5, '#2a2a2a'); bg.addColorStop(1, '#111');
  ctx.fillStyle = bg; ctx.beginPath(); ctx.ellipse(cx, cy + S * 0.02, S * 0.2, S * 0.25, 0, 0, Math.PI * 2); ctx.fill();
  const hg = ctx.createRadialGradient(cx - S * 0.04, cy - S * 0.22, S * 0.02, cx, cy - S * 0.15, S * 0.16);
  hg.addColorStop(0, '#555'); hg.addColorStop(1, '#1a1a1a');
  ctx.fillStyle = hg; ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.15, S * 0.14, S * 0.16, 0, 0, Math.PI * 2); ctx.fill();
  ctx.fillStyle = hexToRgba(teamColor, 0.9);
  ctx.shadowColor = teamColor; ctx.shadowBlur = 8;
  ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.18, S * 0.08, S * 0.028, 0, 0, Math.PI * 2); ctx.fill();
  ctx.shadowBlur = 0;
  ctx.fillStyle = '#444'; ctx.fillRect(cx + S * 0.16, cy - S * 0.02, S * 0.25, S * 0.05);
  ctx.fillStyle = '#1a1a1a';
  ctx.beginPath(); ctx.ellipse(cx - S * 0.09, cy + S * 0.25, S * 0.08, S * 0.04, 0, 0, Math.PI * 2); ctx.fill();
  ctx.beginPath(); ctx.ellipse(cx + S * 0.09, cy + S * 0.25, S * 0.08, S * 0.04, 0, 0, Math.PI * 2); ctx.fill();
}

function drawCyclone(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawCyclone(ctx, S, 1, teamColor); ctx.restore(); return;
  }
  const cx = S / 2, cy = S / 2 + 4;

  if (dir === 1) {
    const cg = ctx.createLinearGradient(cx - S * 0.34, cy, cx + S * 0.34, cy);
    cg.addColorStop(0, '#2a3a4a'); cg.addColorStop(0.5, '#3a5060'); cg.addColorStop(1, '#2a3a4a');
    ctx.fillStyle = cg;
    ctx.beginPath(); ctx.ellipse(cx, cy + S * 0.06, S * 0.32, S * 0.12, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = '#1a2a3a';
    ctx.beginPath(); ctx.ellipse(cx, cy + S * 0.16, S * 0.1, S * 0.05, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = '#3a4a5a';
    ctx.beginPath(); ctx.ellipse(cx - S * 0.06, cy - S * 0.04, S * 0.12, S * 0.1, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = '#555'; ctx.fillRect(cx - S * 0.04, cy - S * 0.1, S * 0.25, S * 0.04);
    ctx.fillStyle = hexToRgba(teamColor, 0.7);
    ctx.shadowColor = teamColor; ctx.shadowBlur = 8;
    ctx.beginPath(); ctx.ellipse(cx - S * 0.08, cy - S * 0.04, S * 0.055, S * 0.045, 0, 0, Math.PI * 2); ctx.fill();
    ctx.shadowBlur = 0;
  } else {
    const cg = ctx.createLinearGradient(cx - S * 0.36, cy, cx + S * 0.36, cy);
    cg.addColorStop(0, '#2a3a4a'); cg.addColorStop(0.5, '#3a5060'); cg.addColorStop(1, '#2a3a4a');
    ctx.fillStyle = cg;
    ctx.beginPath(); ctx.ellipse(cx, cy + S * 0.06, S * 0.36, S * 0.14, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = '#1a2a3a';
    [-0.26, 0.26].forEach(dx => {
      ctx.beginPath(); ctx.ellipse(cx + dx * S, cy + S * 0.14, S * 0.07, S * 0.04, 0, 0, Math.PI * 2); ctx.fill();
    });
    ctx.fillStyle = '#3a4a5a';
    ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.06, S * 0.14, S * 0.12, 0, 0, Math.PI * 2); ctx.fill();
    const armLen = dir === 0 ? -S * 0.22 : S * 0.22;
    ctx.fillStyle = '#555'; ctx.fillRect(cx - S * 0.025, cy - S * 0.12, S * 0.05, armLen);
    ctx.fillStyle = '#666';
    ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.12 + armLen, S * 0.06, S * 0.06, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = hexToRgba(teamColor, 0.7);
    ctx.shadowColor = teamColor; ctx.shadowBlur = 8;
    ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.06, S * 0.07, S * 0.055, 0, 0, Math.PI * 2); ctx.fill();
    ctx.shadowBlur = 0;
  }
}

function drawWidowMine(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawWidowMine(ctx, S, 1, teamColor); ctx.restore(); return;
  }
  const cx = S / 2, cy = S / 2 + 4;

  ctx.strokeStyle = '#445566'; ctx.lineWidth = S * 0.035;
  if (dir === 1) {
    [[-0.55 * Math.PI, 0.28], [0.15 * Math.PI, 0.28]].forEach(([a, r]) => {
      ctx.beginPath(); ctx.moveTo(cx, cy + S * 0.04);
      ctx.lineTo(cx + Math.cos(a) * S * r, cy + S * 0.16 + Math.sin(a) * S * 0.06); ctx.stroke();
    });
  } else {
    [[-0.28, 0.2], [0, 0.24], [0.28, 0.2]].forEach(([dx, dy]) => {
      ctx.beginPath(); ctx.moveTo(cx, cy + S * 0.04);
      ctx.lineTo(cx + dx * S, cy + dy * S); ctx.stroke();
    });
  }

  const bg = ctx.createRadialGradient(cx - S * 0.06, cy - S * 0.06, S * 0.02, cx, cy, S * 0.2);
  bg.addColorStop(0, '#556677'); bg.addColorStop(0.6, '#334455'); bg.addColorStop(1, '#1a2a33');
  ctx.fillStyle = bg; ctx.beginPath(); ctx.ellipse(cx, cy, S * 0.19, S * 0.17, 0, 0, Math.PI * 2); ctx.fill();

  ctx.fillStyle = '#3a4a55';
  ctx.beginPath();
  ctx.moveTo(cx - S * 0.03, cy - S * 0.12); ctx.lineTo(cx + S * 0.03, cy - S * 0.12);
  ctx.lineTo(cx + S * 0.015, cy - S * 0.36); ctx.lineTo(cx - S * 0.015, cy - S * 0.36);
  ctx.closePath(); ctx.fill();

  ctx.fillStyle = teamColor;
  ctx.shadowColor = teamColor; ctx.shadowBlur = 10;
  ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.34, S * 0.04, S * 0.04, 0, 0, Math.PI * 2); ctx.fill();
  ctx.shadowBlur = 0;

  ctx.strokeStyle = '#667788'; ctx.lineWidth = S * 0.02;
  ctx.beginPath(); ctx.ellipse(cx, cy, S * 0.19, S * 0.17, 0, 0, Math.PI * 2); ctx.stroke();
}

function drawSiegeTank(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawSiegeTank(ctx, S, 1, teamColor); ctx.restore(); return;
  }
  const cx = S / 2, cy = S / 2 + 6;

  if (dir === 1) {
    ctx.fillStyle = '#1a1a1a';
    ctx.fillRect(cx - S * 0.36, cy + S * 0.06, S * 0.72, S * 0.14);
    ctx.fillStyle = '#2a2a2a';
    ctx.fillRect(cx - S * 0.34, cy + S * 0.08, S * 0.68, S * 0.1);
    ctx.fillStyle = '#333';
    for (let i = 0; i < 5; i++) {
      ctx.beginPath(); ctx.ellipse(cx - S * 0.28 + i * S * 0.14, cy + S * 0.13, S * 0.03, S * 0.025, 0, 0, Math.PI * 2); ctx.fill();
    }
    const bg = ctx.createLinearGradient(cx, cy - S * 0.14, cx, cy + S * 0.06);
    bg.addColorStop(0, '#5a6a4a'); bg.addColorStop(1, '#3a4a32');
    ctx.fillStyle = bg; ctx.fillRect(cx - S * 0.28, cy - S * 0.12, S * 0.56, S * 0.2);
    ctx.fillStyle = '#4a5a3a';
    ctx.beginPath(); ctx.ellipse(cx - S * 0.06, cy - S * 0.14, S * 0.16, S * 0.12, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = '#3a4a2a'; ctx.fillRect(cx - S * 0.06, cy - S * 0.17, S * 0.42, S * 0.06);
    ctx.fillStyle = '#2a3a22'; ctx.fillRect(cx + S * 0.32, cy - S * 0.16, S * 0.06, S * 0.04);
    ctx.fillStyle = hexToRgba(teamColor, 0.65);
    ctx.shadowColor = teamColor; ctx.shadowBlur = 6;
    ctx.beginPath(); ctx.ellipse(cx - S * 0.08, cy - S * 0.14, S * 0.05, S * 0.04, 0, 0, Math.PI * 2); ctx.fill();
    ctx.shadowBlur = 0;
  } else if (dir === 2) {
    ctx.fillStyle = '#1a1a1a'; ctx.fillRect(cx - S * 0.32, cy + S * 0.06, S * 0.64, S * 0.14);
    const bg = ctx.createLinearGradient(cx, cy - S * 0.14, cx, cy + S * 0.06);
    bg.addColorStop(0, '#4a5a3a'); bg.addColorStop(1, '#2a3a22');
    ctx.fillStyle = bg; ctx.fillRect(cx - S * 0.3, cy - S * 0.1, S * 0.6, S * 0.18);
    ctx.fillStyle = '#2a2a2a';
    [-0.14, 0.14].forEach(dx => { ctx.fillRect(cx + dx * S - S * 0.03, cy - S * 0.08, S * 0.06, S * 0.12); });
    ctx.fillStyle = '#4a5a3a';
    ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.12, S * 0.14, S * 0.1, 0, 0, Math.PI * 2); ctx.fill();
  } else {
    ctx.fillStyle = '#1a1a1a'; ctx.fillRect(cx - S * 0.34, cy + S * 0.06, S * 0.68, S * 0.14);
    const bg = ctx.createLinearGradient(cx, cy - S * 0.14, cx, cy + S * 0.06);
    bg.addColorStop(0, '#5a6a4a'); bg.addColorStop(1, '#3a4a32');
    ctx.fillStyle = bg; ctx.fillRect(cx - S * 0.3, cy - S * 0.1, S * 0.6, S * 0.18);
    ctx.fillStyle = '#4a5a3a';
    ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.14, S * 0.18, S * 0.13, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = '#2a3a22';
    ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.14, S * 0.07, S * 0.055, 0, 0, Math.PI * 2); ctx.fill();
    ctx.strokeStyle = '#3a4a2a'; ctx.lineWidth = S * 0.015;
    ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.14, S * 0.09, S * 0.07, 0, 0, Math.PI * 2); ctx.stroke();
    ctx.fillStyle = hexToRgba(teamColor, 0.65);
    ctx.shadowColor = teamColor; ctx.shadowBlur = 6;
    ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.04, S * 0.05, S * 0.04, 0, 0, Math.PI * 2); ctx.fill();
    ctx.shadowBlur = 0;
  }
}

function drawThor(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawThor(ctx, S, 1, teamColor); ctx.restore(); return;
  }
  const cx = S / 2, cy = S / 2 + 2;

  if (dir === 1) {
    ctx.fillStyle = '#2a3040';
    [[-0.12, 0.22], [0.08, 0.22]].forEach(([dx, dy]) => {
      ctx.fillRect(cx + dx * S, cy + S * 0.04, S * 0.12, dy * S);
    });
    ctx.fillStyle = '#1a2030';
    ctx.beginPath(); ctx.ellipse(cx, cy + S * 0.28, S * 0.26, S * 0.06, 0, 0, Math.PI * 2); ctx.fill();
    const bg = ctx.createLinearGradient(cx, cy - S * 0.2, cx, cy + S * 0.04);
    bg.addColorStop(0, '#4a5060'); bg.addColorStop(1, '#2a3040');
    ctx.fillStyle = bg; ctx.fillRect(cx - S * 0.2, cy - S * 0.18, S * 0.4, S * 0.22);
    ctx.fillStyle = '#3a4050';
    ctx.fillRect(cx + S * 0.18, cy - S * 0.24, S * 0.14, S * 0.12);
    ctx.fillStyle = '#555'; ctx.fillRect(cx + S * 0.26, cy - S * 0.2, S * 0.08, S * 0.06);
    ctx.fillStyle = hexToRgba(teamColor, 0.85);
    ctx.shadowColor = teamColor; ctx.shadowBlur = 12;
    ctx.beginPath(); ctx.ellipse(cx - S * 0.04, cy - S * 0.07, S * 0.08, S * 0.07, 0, 0, Math.PI * 2); ctx.fill();
    ctx.shadowBlur = 0;
  } else {
    ctx.fillStyle = '#2a3040';
    [-0.28, 0.28].forEach(dx => {
      ctx.fillRect(cx + dx * S - S * 0.06, cy + S * 0.04, S * 0.12, S * 0.22);
    });
    ctx.fillStyle = '#1a2030';
    ctx.beginPath(); ctx.ellipse(cx, cy + S * 0.26, S * 0.36, S * 0.07, 0, 0, Math.PI * 2); ctx.fill();
    const bg = ctx.createRadialGradient(cx - S * 0.06, cy - S * 0.06, S * 0.04, cx, cy, S * 0.32);
    bg.addColorStop(0, '#505566'); bg.addColorStop(1, '#1e2434');
    ctx.fillStyle = bg; ctx.fillRect(cx - S * 0.24, cy - S * 0.2, S * 0.48, S * 0.26);
    ctx.fillStyle = '#3a4050';
    [-0.28, 0.28].forEach(dx => { ctx.fillRect(cx + dx * S - S * 0.06, cy - S * 0.26, S * 0.14, S * 0.12); });
    ctx.fillStyle = '#556';
    if (dir === 0) {
      [-0.28, 0.28].forEach(dx => {
        ctx.beginPath(); ctx.ellipse(cx + dx * S, cy - S * 0.22, S * 0.05, S * 0.04, 0, 0, Math.PI * 2); ctx.fill();
      });
      ctx.fillStyle = '#445';
      [-0.2, 0.2].forEach(dx => { ctx.fillRect(cx + dx * S - S * 0.02, cy - S * 0.12, S * 0.04, S * 0.2); });
    } else {
      [-0.28, 0.28].forEach(dx => { ctx.fillRect(cx + dx * S - S * 0.04, cy - S * 0.3, S * 0.08, S * 0.08); });
    }
    ctx.fillStyle = hexToRgba(teamColor, 0.85);
    ctx.shadowColor = teamColor; ctx.shadowBlur = 14;
    ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.07, S * 0.1, S * 0.09, 0, 0, Math.PI * 2); ctx.fill();
    ctx.shadowBlur = 0;
  }
}

function drawViking(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawViking(ctx, S, 1, teamColor); ctx.restore(); return;
  }
  const cx = S / 2, cy = S / 2;

  if (dir === 0 || dir === 2) {
    ctx.fillStyle = '#3a4a5a';
    ctx.beginPath();
    ctx.moveTo(cx - S * 0.04, cy - S * 0.04); ctx.lineTo(cx - S * 0.44, cy + S * 0.06);
    ctx.lineTo(cx - S * 0.4, cy + S * 0.16); ctx.lineTo(cx - S * 0.06, cy + S * 0.06); ctx.closePath(); ctx.fill();
    ctx.beginPath();
    ctx.moveTo(cx + S * 0.04, cy - S * 0.04); ctx.lineTo(cx + S * 0.44, cy + S * 0.06);
    ctx.lineTo(cx + S * 0.4, cy + S * 0.16); ctx.lineTo(cx + S * 0.06, cy + S * 0.06); ctx.closePath(); ctx.fill();
    ctx.fillStyle = '#2a3a4a';
    [-0.4, 0.4].forEach(dx => {
      ctx.beginPath(); ctx.ellipse(cx + dx * S, cy + S * 0.1, S * 0.06, S * 0.1, 0, 0, Math.PI * 2); ctx.fill();
    });
    ctx.fillStyle = hexToRgba(teamColor, 0.8);
    ctx.shadowColor = teamColor; ctx.shadowBlur = 10;
    const exhaustY = dir === 0 ? S * 0.18 : S * 0.02;
    [-0.4, 0.4].forEach(dx => {
      ctx.beginPath(); ctx.ellipse(cx + dx * S, cy + exhaustY, S * 0.04, S * 0.04, 0, 0, Math.PI * 2); ctx.fill();
    });
    ctx.shadowBlur = 0;
    ctx.fillStyle = '#4a5a6a';
    ctx.beginPath(); ctx.ellipse(cx, cy, S * 0.07, S * 0.22, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = hexToRgba(teamColor, 0.65);
    ctx.shadowColor = teamColor; ctx.shadowBlur = 6;
    const cockpitY = dir === 0 ? cy - S * 0.12 : cy - S * 0.06;
    ctx.beginPath(); ctx.ellipse(cx, cockpitY, S * 0.05, S * 0.045, 0, 0, Math.PI * 2); ctx.fill();
    ctx.shadowBlur = 0;
  } else {
    ctx.fillStyle = '#3a4a5a';
    ctx.beginPath();
    ctx.moveTo(cx - S * 0.28, cy + S * 0.04); ctx.lineTo(cx + S * 0.28, cy - S * 0.04);
    ctx.lineTo(cx + S * 0.28, cy + S * 0.1); ctx.lineTo(cx - S * 0.28, cy + S * 0.1); ctx.closePath(); ctx.fill();
    ctx.beginPath();
    ctx.moveTo(cx - S * 0.04, cy); ctx.lineTo(cx + S * 0.04, cy - S * 0.02);
    ctx.lineTo(cx + S * 0.02, cy - S * 0.26); ctx.lineTo(cx - S * 0.04, cy - S * 0.22); ctx.closePath(); ctx.fill();
    ctx.fillStyle = '#2a3a4a';
    ctx.beginPath(); ctx.ellipse(cx + S * 0.02, cy - S * 0.22, S * 0.055, S * 0.06, -0.2, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = hexToRgba(teamColor, 0.7);
    ctx.shadowColor = teamColor; ctx.shadowBlur = 8;
    ctx.beginPath(); ctx.ellipse(cx + S * 0.04, cy - S * 0.25, S * 0.035, S * 0.035, 0, 0, Math.PI * 2); ctx.fill();
    ctx.shadowBlur = 0;
    ctx.fillStyle = '#4a5a6a';
    ctx.beginPath(); ctx.ellipse(cx - S * 0.16, cy + S * 0.02, S * 0.06, S * 0.04, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = hexToRgba(teamColor, 0.65);
    ctx.shadowColor = teamColor; ctx.shadowBlur = 5;
    ctx.beginPath(); ctx.ellipse(cx - S * 0.18, cy + S * 0.01, S * 0.035, S * 0.026, 0, 0, Math.PI * 2); ctx.fill();
    ctx.shadowBlur = 0;
  }
}

function drawRaven(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawRaven(ctx, S, 1, teamColor); ctx.restore(); return;
  }
  const cx = S / 2, cy = S / 2;

  ctx.strokeStyle = '#556677'; ctx.lineWidth = S * 0.03;
  ctx.beginPath(); ctx.ellipse(cx, cy, S * 0.36, S * 0.3, 0, 0, Math.PI * 2); ctx.stroke();

  ctx.strokeStyle = hexToRgba(teamColor, 0.5);
  ctx.shadowColor = teamColor; ctx.shadowBlur = 12;
  ctx.lineWidth = S * 0.025;
  ctx.beginPath(); ctx.ellipse(cx, cy, S * 0.32, S * 0.26, 0, 0, Math.PI * 2); ctx.stroke();
  ctx.shadowBlur = 0;

  const bg = ctx.createRadialGradient(cx - S * 0.08, cy - S * 0.08, S * 0.03, cx, cy, S * 0.24);
  bg.addColorStop(0, '#5a6a7a'); bg.addColorStop(0.6, '#3a4a5a'); bg.addColorStop(1, '#1a2a3a');
  ctx.fillStyle = bg; ctx.beginPath(); ctx.ellipse(cx, cy, S * 0.24, S * 0.2, 0, 0, Math.PI * 2); ctx.fill();

  const offset = (dir === 0 || dir === 2) ? 0 : S * 0.04;
  ctx.fillStyle = '#667788';
  [-S * 0.16, S * 0.16].forEach(dx => {
    ctx.beginPath(); ctx.ellipse(cx + dx + offset, cy + S * 0.04, S * 0.055, S * 0.04, 0, 0, Math.PI * 2); ctx.fill();
  });

  ctx.fillStyle = hexToRgba(teamColor, 0.9);
  ctx.shadowColor = teamColor; ctx.shadowBlur = 10;
  ctx.beginPath(); ctx.ellipse(cx, cy, S * 0.07, S * 0.06, 0, 0, Math.PI * 2); ctx.fill();
  ctx.shadowBlur = 0;
}

function drawBanshee(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawBanshee(ctx, S, 1, teamColor); ctx.restore(); return;
  }
  const cx = S / 2, cy = S / 2;

  if (dir === 0 || dir === 2) {
    ctx.fillStyle = '#2a3340';
    [-0.24, 0.24].forEach(dx => {
      ctx.beginPath(); ctx.ellipse(cx + dx * S, cy + S * 0.04, S * 0.1, S * 0.18, 0, 0, Math.PI * 2); ctx.fill();
    });
    ctx.strokeStyle = hexToRgba(teamColor, 0.35); ctx.lineWidth = S * 0.02;
    [-0.24, 0.24].forEach(dx => {
      ctx.beginPath(); ctx.ellipse(cx + dx * S, cy + S * 0.04, S * 0.18, S * 0.06, 0, 0, Math.PI * 2); ctx.stroke();
    });
    ctx.fillStyle = hexToRgba(teamColor, 0.75);
    ctx.shadowColor = teamColor; ctx.shadowBlur = 10;
    const exhaustY = dir === 0 ? S * 0.2 : -S * 0.12;
    [-0.24, 0.24].forEach(dx => {
      ctx.beginPath(); ctx.ellipse(cx + dx * S, cy + exhaustY, S * 0.05, S * 0.05, 0, 0, Math.PI * 2); ctx.fill();
    });
    ctx.shadowBlur = 0;
    ctx.fillStyle = '#3a4050';
    ctx.beginPath();
    ctx.moveTo(cx - S * 0.06, cy - S * 0.04); ctx.lineTo(cx - S * 0.26, cy + S * 0.06);
    ctx.lineTo(cx - S * 0.24, cy + S * 0.12); ctx.lineTo(cx - S * 0.06, cy + S * 0.04); ctx.closePath(); ctx.fill();
    ctx.beginPath();
    ctx.moveTo(cx + S * 0.06, cy - S * 0.04); ctx.lineTo(cx + S * 0.26, cy + S * 0.06);
    ctx.lineTo(cx + S * 0.24, cy + S * 0.12); ctx.lineTo(cx + S * 0.06, cy + S * 0.04); ctx.closePath(); ctx.fill();
    const fg = ctx.createLinearGradient(cx, cy - S * 0.32, cx, cy + S * 0.1);
    fg.addColorStop(0, '#4a5060'); fg.addColorStop(1, '#2a3040');
    ctx.fillStyle = fg; ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.1, S * 0.09, S * 0.24, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = hexToRgba(teamColor, 0.7);
    ctx.shadowColor = teamColor; ctx.shadowBlur = 8;
    const cockY = dir === 0 ? cy - S * 0.28 : cy + S * 0.06;
    ctx.beginPath(); ctx.ellipse(cx, cockY, S * 0.055, S * 0.05, 0, 0, Math.PI * 2); ctx.fill();
    ctx.shadowBlur = 0;
  } else {
    const fg = ctx.createLinearGradient(cx - S * 0.3, cy, cx + S * 0.3, cy);
    fg.addColorStop(0, '#2a3040'); fg.addColorStop(0.5, '#4a5060'); fg.addColorStop(1, '#2a3040');
    ctx.fillStyle = fg; ctx.beginPath(); ctx.ellipse(cx, cy, S * 0.32, S * 0.1, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = '#2a3340';
    ctx.beginPath(); ctx.ellipse(cx + S * 0.16, cy + S * 0.04, S * 0.1, S * 0.12, 0.3, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = hexToRgba(teamColor, 0.7);
    ctx.shadowColor = teamColor; ctx.shadowBlur = 9;
    ctx.beginPath(); ctx.ellipse(cx + S * 0.24, cy + S * 0.04, S * 0.04, S * 0.04, 0, 0, Math.PI * 2); ctx.fill();
    ctx.shadowBlur = 0;
    ctx.fillStyle = hexToRgba(teamColor, 0.65);
    ctx.shadowColor = teamColor; ctx.shadowBlur = 6;
    ctx.beginPath(); ctx.ellipse(cx - S * 0.24, cy - S * 0.02, S * 0.055, S * 0.04, 0, 0, Math.PI * 2); ctx.fill();
    ctx.shadowBlur = 0;
  }
}

function drawLiberator(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawLiberator(ctx, S, 1, teamColor); ctx.restore(); return;
  }
  const cx = S / 2, cy = S / 2;

  if (dir === 0 || dir === 2) {
    ctx.fillStyle = '#2a3848';
    [-1, 1].forEach(side => {
      ctx.beginPath();
      ctx.moveTo(cx + side * S * 0.06, cy + S * 0.04);
      ctx.lineTo(cx + side * S * 0.44, cy + S * 0.1);
      ctx.lineTo(cx + side * S * 0.44, cy + S * 0.2);
      ctx.lineTo(cx + side * S * 0.04, cy + S * 0.1);
      ctx.closePath(); ctx.fill();
    });
    ctx.fillStyle = '#1a2838';
    [-0.38, 0.38].forEach(dx => {
      ctx.beginPath(); ctx.ellipse(cx + dx * S, cy + S * 0.14, S * 0.07, S * 0.04, 0, 0, Math.PI * 2); ctx.fill();
    });
    ctx.fillStyle = hexToRgba(teamColor, 0.6);
    ctx.shadowColor = teamColor; ctx.shadowBlur = 12;
    const exhaustY = dir === 0 ? S * 0.19 : S * 0.08;
    [-0.38, 0.38].forEach(dx => {
      ctx.beginPath(); ctx.ellipse(cx + dx * S, cy + exhaustY, S * 0.04, S * 0.04, 0, 0, Math.PI * 2); ctx.fill();
    });
    ctx.shadowBlur = 0;
    const bg = ctx.createRadialGradient(cx, cy - S * 0.08, S * 0.04, cx, cy, S * 0.18);
    bg.addColorStop(0, '#5a6878'); bg.addColorStop(1, '#2a3848');
    ctx.fillStyle = bg; ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.04, S * 0.14, S * 0.3, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = hexToRgba(teamColor, 0.7);
    ctx.shadowColor = teamColor; ctx.shadowBlur = 8;
    const cockY = dir === 0 ? cy - S * 0.28 : cy + S * 0.22;
    ctx.beginPath(); ctx.ellipse(cx, cockY, S * 0.06, S * 0.055, 0, 0, Math.PI * 2); ctx.fill();
    ctx.shadowBlur = 0;
  } else {
    const bg = ctx.createLinearGradient(cx - S * 0.34, cy, cx + S * 0.34, cy);
    bg.addColorStop(0, '#1a2838'); bg.addColorStop(0.4, '#4a5868'); bg.addColorStop(1, '#1a2838');
    ctx.fillStyle = bg; ctx.beginPath(); ctx.ellipse(cx, cy, S * 0.34, S * 0.12, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = '#2a3848';
    ctx.beginPath();
    ctx.moveTo(cx - S * 0.04, cy - S * 0.06); ctx.lineTo(cx + S * 0.04, cy - S * 0.06);
    ctx.lineTo(cx + S * 0.06, cy - S * 0.3); ctx.lineTo(cx - S * 0.04, cy - S * 0.26); ctx.closePath(); ctx.fill();
    ctx.fillStyle = '#1a2838';
    ctx.beginPath(); ctx.ellipse(cx + S * 0.02, cy - S * 0.28, S * 0.06, S * 0.05, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = hexToRgba(teamColor, 0.65);
    ctx.shadowColor = teamColor; ctx.shadowBlur = 9;
    ctx.beginPath(); ctx.ellipse(cx + S * 0.05, cy - S * 0.31, S * 0.04, S * 0.035, 0, 0, Math.PI * 2); ctx.fill();
    ctx.shadowBlur = 0;
    ctx.fillStyle = hexToRgba(teamColor, 0.65);
    ctx.shadowColor = teamColor; ctx.shadowBlur = 6;
    ctx.beginPath(); ctx.ellipse(cx - S * 0.28, cy - S * 0.03, S * 0.055, S * 0.04, 0, 0, Math.PI * 2); ctx.fill();
    ctx.shadowBlur = 0;
  }
}

function drawBattlecruiser(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawBattlecruiser(ctx, S, 1, teamColor); ctx.restore(); return;
  }
  const cx = S / 2, cy = S / 2;

  if (dir === 0 || dir === 2) {
    const hg = ctx.createRadialGradient(cx - S * 0.08, cy - S * 0.06, S * 0.04, cx, cy, S * 0.44);
    hg.addColorStop(0, '#6a7480'); hg.addColorStop(0.5, '#3a4450'); hg.addColorStop(1, '#1a2430');
    ctx.fillStyle = hg; ctx.beginPath(); ctx.ellipse(cx, cy, S * 0.42, S * 0.32, 0, 0, Math.PI * 2); ctx.fill();
    ctx.strokeStyle = '#2a3440'; ctx.lineWidth = S * 0.015;
    [-0.22, 0, 0.22].forEach(dx => {
      ctx.beginPath(); ctx.moveTo(cx + dx * S, cy - S * 0.32); ctx.lineTo(cx + dx * S, cy + S * 0.32); ctx.stroke();
    });
    ctx.fillStyle = '#2a3440';
    [[-0.28, -0.18], [0.28, -0.18], [-0.28, 0.18], [0.28, 0.18]].forEach(([dx, dy]) => {
      ctx.beginPath(); ctx.ellipse(cx + dx * S, cy + dy * S, S * 0.06, S * 0.055, 0, 0, Math.PI * 2); ctx.fill();
      ctx.fillStyle = '#1a2430'; ctx.fillRect(cx + dx * S - S * 0.01, cy + dy * S - S * 0.1, S * 0.02, S * 0.1);
      ctx.fillStyle = '#2a3440';
    });
    if (dir === 0) {
      ctx.fillStyle = '#1a2430';
      ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.28, S * 0.05, S * 0.04, 0, 0, Math.PI * 2); ctx.fill();
      ctx.strokeStyle = '#3a4450'; ctx.lineWidth = S * 0.02;
      ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.28, S * 0.07, S * 0.06, 0, 0, Math.PI * 2); ctx.stroke();
    }
    ctx.fillStyle = hexToRgba(teamColor, 0.8);
    ctx.shadowColor = teamColor; ctx.shadowBlur = 10;
    [[-0.36, 0], [0.36, 0], [0, -0.3], [0, 0.3]].forEach(([dx, dy]) => {
      ctx.beginPath(); ctx.ellipse(cx + dx * S, cy + dy * S, S * 0.04, S * 0.035, 0, 0, Math.PI * 2); ctx.fill();
    });
    ctx.shadowBlur = 0;
    const bg = ctx.createRadialGradient(cx - S * 0.04, cy - S * 0.06, S * 0.02, cx, cy, S * 0.14);
    bg.addColorStop(0, '#8a9aaa'); bg.addColorStop(1, '#4a5a6a');
    ctx.fillStyle = bg; ctx.beginPath(); ctx.ellipse(cx, cy, S * 0.1, S * 0.09, 0, 0, Math.PI * 2); ctx.fill();
  } else {
    const hg = ctx.createLinearGradient(cx - S * 0.46, cy, cx + S * 0.46, cy);
    hg.addColorStop(0, '#1a2430'); hg.addColorStop(0.2, '#4a5460');
    hg.addColorStop(0.5, '#5a6470'); hg.addColorStop(0.8, '#4a5460'); hg.addColorStop(1, '#1a2430');
    ctx.fillStyle = hg;
    ctx.beginPath();
    ctx.moveTo(cx - S * 0.46, cy + S * 0.04);
    ctx.quadraticCurveTo(cx - S * 0.44, cy - S * 0.12, cx - S * 0.36, cy - S * 0.16);
    ctx.lineTo(cx + S * 0.2, cy - S * 0.16);
    ctx.quadraticCurveTo(cx + S * 0.44, cy - S * 0.12, cx + S * 0.46, cy + S * 0.02);
    ctx.quadraticCurveTo(cx + S * 0.44, cy + S * 0.14, cx + S * 0.36, cy + S * 0.16);
    ctx.lineTo(cx - S * 0.36, cy + S * 0.16);
    ctx.quadraticCurveTo(cx - S * 0.44, cy + S * 0.14, cx - S * 0.46, cy + S * 0.04);
    ctx.closePath(); ctx.fill();
    ctx.strokeStyle = '#2a3440'; ctx.lineWidth = S * 0.012;
    [-S * 0.16, 0, S * 0.16].forEach(dx => {
      ctx.beginPath(); ctx.moveTo(cx + dx, cy - S * 0.14); ctx.lineTo(cx + dx, cy + S * 0.14); ctx.stroke();
    });
    ctx.fillStyle = '#111';
    ctx.beginPath(); ctx.ellipse(cx - S * 0.44, cy - S * 0.04, S * 0.04, S * 0.04, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = '#2a3440';
    [-0.24, 0, 0.2].forEach(dx => {
      ctx.beginPath(); ctx.ellipse(cx + dx * S, cy - S * 0.18, S * 0.05, S * 0.04, 0, 0, Math.PI * 2); ctx.fill();
    });
    ctx.fillStyle = '#6a7a8a';
    ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.14, S * 0.1, S * 0.06, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = hexToRgba(teamColor, 0.8);
    ctx.shadowColor = teamColor; ctx.shadowBlur = 10;
    [-0.34, 0, 0.34].forEach(dx => {
      ctx.beginPath(); ctx.ellipse(cx + dx * S, cy + S * 0.14, S * 0.03, S * 0.028, 0, 0, Math.PI * 2); ctx.fill();
    });
    ctx.shadowBlur = 0;
  }
}

function drawSentry(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawSentry(ctx, S, 1, teamColor); ctx.restore(); return;
  }
  const cx = S / 2, cy = S / 2 + S * 0.06;

  // Body — small rounded dark blue-grey ellipse
  const bw = S * 0.18, bh = S * 0.15;
  const bodyGrad = ctx.createRadialGradient(cx - S * 0.04, cy - S * 0.04, S * 0.02, cx, cy, bw);
  bodyGrad.addColorStop(0, '#2a2a5a');
  bodyGrad.addColorStop(1, '#0e0e2a');
  ctx.fillStyle = bodyGrad;
  ctx.beginPath();
  ctx.ellipse(cx, cy, bw, bh, 0, 0, Math.PI * 2);
  ctx.fill();

  // Shield projector hub on top (hidden in dir 2 — behind body)
  if (dir !== 2) {
    ctx.fillStyle = '#0a0a20';
    ctx.beginPath();
    ctx.ellipse(cx, cy - bh - S * 0.04, S * 0.07, S * 0.05, 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.strokeStyle = hexToRgba(teamColor, 0.5);
    ctx.lineWidth = S * 0.015;
    ctx.beginPath();
    ctx.ellipse(cx, cy - bh - S * 0.04, S * 0.07, S * 0.05, 0, 0, Math.PI * 2);
    ctx.stroke();
  }

  // Equatorial ring — perspective ellipse
  const ringRx = S * 0.22;
  const ringRy = (dir === 1) ? S * 0.08 : S * 0.22;
  ctx.strokeStyle = hexToRgba(teamColor, 0.9);
  ctx.lineWidth = S * 0.025;
  ctx.shadowColor = teamColor;
  ctx.shadowBlur = 8;
  ctx.beginPath();
  ctx.ellipse(cx, cy, ringRx, ringRy, 0, 0, Math.PI * 2);
  ctx.stroke();
  ctx.shadowBlur = 0;

  // Three emitter dots at 0°, 120°, 240° around ring
  const angles = dir === 2
    ? [Math.PI * 0.5, Math.PI * 1.17, Math.PI * 1.83]   // shifted slightly for back view
    : [0, Math.PI * 2 / 3, Math.PI * 4 / 3];
  ctx.fillStyle = hexToRgba(teamColor, 1.0);
  ctx.shadowColor = teamColor;
  ctx.shadowBlur = 10;
  angles.forEach(a => {
    const ex = cx + Math.cos(a) * ringRx;
    const ey = cy + Math.sin(a) * ringRy;
    ctx.beginPath();
    ctx.arc(ex, ey, S * 0.03, 0, Math.PI * 2);
    ctx.fill();
  });
  ctx.shadowBlur = 0;
}

function drawAdept(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawAdept(ctx, S, 1, teamColor); ctx.restore(); return;
  }
  const cx = S / 2, cy = S / 2;

  // Lower body / leg mass — slightly wider ellipse below torso
  const legGrad = ctx.createRadialGradient(cx, cy + S * 0.12, S * 0.02, cx, cy + S * 0.12, S * 0.22);
  legGrad.addColorStop(0, '#5a5030');
  legGrad.addColorStop(1, '#2a2010');
  ctx.fillStyle = legGrad;
  ctx.beginPath();
  ctx.ellipse(cx, cy + S * 0.14, S * 0.18, S * 0.14, 0, 0, Math.PI * 2);
  ctx.fill();

  // Upper torso — upright ellipse centred at canvas centre
  const torsoGrad = ctx.createRadialGradient(cx - S * 0.04, cy - S * 0.06, S * 0.03, cx, cy, S * 0.2);
  torsoGrad.addColorStop(0, '#6a6040');
  torsoGrad.addColorStop(1, '#3a3020');
  ctx.fillStyle = torsoGrad;
  ctx.beginPath();
  ctx.ellipse(cx, cy - S * 0.04, S * 0.15, S * 0.2, 0, 0, Math.PI * 2);
  ctx.fill();

  // Helm — rounded top with a slight forward tilt
  ctx.fillStyle = '#4a4028';
  ctx.beginPath();
  ctx.ellipse(cx, cy - S * 0.22, S * 0.1, S * 0.09, 0, 0, Math.PI * 2);
  ctx.fill();

  // Eye-strip visor across helm
  ctx.fillStyle = hexToRgba(teamColor, 0.9);
  ctx.shadowColor = teamColor;
  ctx.shadowBlur = 6;
  ctx.beginPath();
  ctx.roundRect(cx - S * 0.09, cy - S * 0.25, S * 0.18, S * 0.035, S * 0.01);
  ctx.fill();
  ctx.shadowBlur = 0;

  // Psi-lance blade
  ctx.fillStyle = hexToRgba(teamColor, 0.85);
  ctx.shadowColor = teamColor;
  ctx.shadowBlur = 10;

  if (dir === 1) {
    // Side view: long diagonal blade extending upper-right from shoulder
    ctx.save();
    ctx.translate(cx + S * 0.12, cy - S * 0.05);
    ctx.rotate(-Math.PI / 5);
    ctx.beginPath();
    ctx.moveTo(0, 0);
    ctx.lineTo(S * 0.28, -S * 0.04);
    ctx.lineTo(S * 0.38, -S * 0.01);  // tip
    ctx.lineTo(S * 0.28, S * 0.04);
    ctx.closePath();
    ctx.fill();
    ctx.restore();
  } else if (dir === 0) {
    // Front view: blade tip-on — small pointed diamond at right shoulder
    const bx = cx + S * 0.16, by = cy - S * 0.08;
    ctx.beginPath();
    ctx.moveTo(bx, by - S * 0.045);
    ctx.lineTo(bx + S * 0.05, by);
    ctx.lineTo(bx, by + S * 0.045);
    ctx.lineTo(bx - S * 0.025, by);
    ctx.closePath();
    ctx.fill();
  } else if (dir === 2) {
    // Back view: no blade — draw carapace ridge down the back
    ctx.shadowBlur = 0;
    ctx.strokeStyle = hexToRgba(teamColor, 0.4);
    ctx.lineWidth = S * 0.02;
    ctx.beginPath();
    ctx.moveTo(cx, cy - S * 0.26);
    ctx.lineTo(cx, cy + S * 0.2);
    ctx.stroke();
  }
  ctx.shadowBlur = 0;
}

function drawDarkTemplar(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawDarkTemplar(ctx, S, 1, teamColor); ctx.restore(); return;
  }
  const cx = S / 2, cy = S / 2;

  // Lower body — dark sinister ellipse, near-black with slight transparency
  const legGrad = ctx.createRadialGradient(cx, cy + S * 0.12, S * 0.02, cx, cy + S * 0.12, S * 0.22);
  legGrad.addColorStop(0, hexToRgba('#1a0a2a', 0.95));
  legGrad.addColorStop(1, hexToRgba('#0a0010', 0.85));
  ctx.fillStyle = legGrad;
  ctx.beginPath();
  ctx.ellipse(cx, cy + S * 0.14, S * 0.14, S * 0.13, 0, 0, Math.PI * 2);
  ctx.fill();

  // Upper torso — narrow, upright, very dark
  const torsoGrad = ctx.createRadialGradient(cx - S * 0.03, cy - S * 0.06, S * 0.02, cx, cy, S * 0.17);
  torsoGrad.addColorStop(0, hexToRgba('#160820', 0.92));
  torsoGrad.addColorStop(1, hexToRgba('#0a0a1a', 0.85));
  ctx.fillStyle = torsoGrad;
  ctx.beginPath();
  ctx.ellipse(cx, cy - S * 0.02, S * 0.12, S * 0.19, 0, 0, Math.PI * 2);
  ctx.fill();

  // Subtle body outline in dark purple
  ctx.strokeStyle = '#1a0a2a';
  ctx.lineWidth = S * 0.015;
  ctx.beginPath();
  ctx.ellipse(cx, cy - S * 0.02, S * 0.12, S * 0.19, 0, 0, Math.PI * 2);
  ctx.stroke();

  // Head — small dark ellipse
  ctx.fillStyle = hexToRgba('#0e0618', 0.95);
  ctx.beginPath();
  ctx.ellipse(cx, cy - S * 0.22, S * 0.085, S * 0.08, 0, 0, Math.PI * 2);
  ctx.fill();

  // Eyes — two bright dots in team colour with glow
  ctx.fillStyle = hexToRgba(teamColor, 0.95);
  ctx.shadowColor = teamColor;
  ctx.shadowBlur = 12;
  const eyeY = cy - S * 0.235;
  const eyeR = S * 0.022;
  if (dir === 2) {
    // Back — no eyes visible, draw faint carapace line instead
    ctx.shadowBlur = 0;
    ctx.strokeStyle = hexToRgba(teamColor, 0.25);
    ctx.lineWidth = S * 0.018;
    ctx.beginPath();
    ctx.moveTo(cx, cy - S * 0.28);
    ctx.lineTo(cx, cy + S * 0.2);
    ctx.stroke();
  } else {
    // Front and side eyes
    ctx.beginPath();
    ctx.arc(cx - S * 0.03, eyeY, eyeR, 0, Math.PI * 2);
    ctx.fill();
    ctx.beginPath();
    ctx.arc(cx + S * 0.03, eyeY, eyeR, 0, Math.PI * 2);
    ctx.fill();
  }
  ctx.shadowBlur = 0;

  // Warp blade — dark crescent or angled slash in team colour
  ctx.fillStyle = hexToRgba(teamColor, 0.88);
  ctx.strokeStyle = hexToRgba(teamColor, 0.95);
  ctx.shadowColor = teamColor;
  ctx.shadowBlur = 14;

  if (dir === 1) {
    // Side view: full curved warp blade extending from right shoulder
    ctx.save();
    ctx.translate(cx + S * 0.1, cy - S * 0.08);
    ctx.beginPath();
    // Crescent arc blade
    ctx.moveTo(0, 0);
    ctx.bezierCurveTo(
      S * 0.12, -S * 0.18,   // control point 1: upper curve
      S * 0.35, -S * 0.1,    // control point 2: tip approach
      S * 0.38, S * 0.04     // blade tip
    );
    ctx.bezierCurveTo(
      S * 0.28, -S * 0.04,   // return curve outer edge
      S * 0.1,  -S * 0.06,   // return mid
      0, S * 0.04            // back to base
    );
    ctx.closePath();
    ctx.fill();
    ctx.lineWidth = S * 0.012;
    // Bright edge along blade
    ctx.beginPath();
    ctx.moveTo(0, 0);
    ctx.bezierCurveTo(S * 0.12, -S * 0.18, S * 0.35, -S * 0.1, S * 0.38, S * 0.04);
    ctx.stroke();
    ctx.restore();
  } else if (dir === 0) {
    // Front view: shorter angled slash at right shoulder
    const bx = cx + S * 0.14, by = cy - S * 0.1;
    ctx.save();
    ctx.translate(bx, by);
    ctx.rotate(-Math.PI / 4);
    ctx.beginPath();
    ctx.moveTo(0, -S * 0.04);
    ctx.lineTo(S * 0.14, -S * 0.02);
    ctx.lineTo(S * 0.18, S * 0.01);
    ctx.lineTo(S * 0.12, S * 0.04);
    ctx.lineTo(-S * 0.02, S * 0.02);
    ctx.closePath();
    ctx.fill();
    ctx.restore();
  }
  // dir === 2 — back: carapace line drawn in eyes section above, no blade
  ctx.shadowBlur = 0;
}

function drawHighTemplar(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawHighTemplar(ctx, S, 1, teamColor); ctx.restore(); return;
  }
  const cx = S / 2, cy = S / 2;

  // Robe — wide trapezoid: narrow shoulders, wide hem, Protoss dark blue-grey
  const robeTop    = cy - S * 0.14;
  const robeBottom = cy + S * 0.26;
  const shoulderW  = dir === 1 ? S * 0.10 : S * 0.20;
  const hemW       = dir === 1 ? S * 0.12 : S * 0.38;

  const robeGrad = ctx.createLinearGradient(cx, robeTop, cx, robeBottom);
  robeGrad.addColorStop(0, hexToRgba('#3a3a58', 0.95));
  robeGrad.addColorStop(1, hexToRgba('#1e1e30', 0.92));
  ctx.fillStyle = robeGrad;
  ctx.beginPath();
  ctx.moveTo(cx - shoulderW, robeTop);
  ctx.lineTo(cx + shoulderW, robeTop);
  ctx.lineTo(cx + hemW,      robeBottom);
  ctx.lineTo(cx - hemW,      robeBottom);
  ctx.closePath();
  ctx.fill();

  // Gold trim along hem
  ctx.strokeStyle = hexToRgba('#7a6a30', 0.85);
  ctx.lineWidth = S * 0.025;
  ctx.beginPath();
  ctx.moveTo(cx - hemW, robeBottom);
  ctx.lineTo(cx + hemW, robeBottom);
  ctx.stroke();

  // Upper torso / head — small rounded ellipse above robe
  const headCy = cy - S * 0.21;
  const torsoGrad = ctx.createRadialGradient(cx, headCy, S * 0.01, cx, headCy, S * 0.13);
  torsoGrad.addColorStop(0, hexToRgba('#4a4870', 0.95));
  torsoGrad.addColorStop(1, hexToRgba('#2a2a40', 0.90));
  ctx.fillStyle = torsoGrad;
  ctx.beginPath();
  ctx.ellipse(cx, headCy, S * 0.10, S * 0.13, 0, 0, Math.PI * 2);
  ctx.fill();

  // Staff — thin vertical rect running through body centre
  const staffX    = dir === 1 ? cx - S * 0.04 : cx;
  const staffTop  = cy - S * 0.35;
  const staffBot  = robeBottom;
  ctx.fillStyle = hexToRgba('#8a7a40', 0.90);
  ctx.fillRect(staffX - S * 0.010, staffTop, S * 0.020, staffBot - staffTop);
  // Staff orb at top
  ctx.fillStyle = hexToRgba(teamColor, 0.90);
  ctx.shadowColor = teamColor;
  ctx.shadowBlur = 12;
  ctx.beginPath();
  ctx.arc(staffX, staffTop, S * 0.030, 0, Math.PI * 2);
  ctx.fill();
  ctx.shadowBlur = 0;

  // Psionic energy arcs at hand positions (lower body sides)
  const energyPositions = dir === 1
    ? [{ x: cx + S * 0.07, y: cy + S * 0.06 }]
    : [{ x: cx - S * 0.18, y: cy + S * 0.06 }, { x: cx + S * 0.18, y: cy + S * 0.06 }];

  ctx.strokeStyle = hexToRgba(teamColor, 0.85);
  ctx.lineWidth = S * 0.018;
  ctx.shadowColor = teamColor;
  ctx.shadowBlur = 16;

  for (const ep of energyPositions) {
    for (let i = 0; i < 4; i++) {
      const startAngle = (i / 4) * Math.PI * 2 - Math.PI * 0.25;
      const endAngle   = startAngle + Math.PI * 0.45;
      ctx.beginPath();
      ctx.arc(ep.x, ep.y, S * 0.055 + i * S * 0.012, startAngle, endAngle);
      ctx.stroke();
    }
  }
  ctx.shadowBlur = 0;
}

function drawDisruptor(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawDisruptor(ctx, S, 1, teamColor); ctx.restore(); return;
  }
  const cx = S / 2, cy = S / 2 + 4;

  // Subtle floating shadow beneath the sphere
  ctx.fillStyle = hexToRgba('#000000', 0.25);
  ctx.beginPath();
  ctx.ellipse(cx, cy + S * 0.30, S * 0.20, S * 0.06, 0, 0, Math.PI * 2);
  ctx.fill();

  // Main sphere — dark base
  const baseGrad = ctx.createRadialGradient(cx - S * 0.06, cy - S * 0.06, S * 0.02,
                                            cx, cy, S * 0.28);
  baseGrad.addColorStop(0, hexToRgba('#2a0a3a', 0.95));
  baseGrad.addColorStop(0.5, hexToRgba('#120520', 0.98));
  baseGrad.addColorStop(1, hexToRgba('#050010', 1.0));
  ctx.fillStyle = baseGrad;
  ctx.beginPath();
  ctx.arc(cx, cy, S * 0.28, 0, Math.PI * 2);
  ctx.fill();

  // Energy glow layer on sphere surface
  const glowGrad = ctx.createRadialGradient(cx, cy, S * 0.04, cx, cy, S * 0.28);
  glowGrad.addColorStop(0, hexToRgba(teamColor, 0.70));
  glowGrad.addColorStop(0.45, hexToRgba(teamColor, 0.18));
  glowGrad.addColorStop(1, hexToRgba('#000000', 0.0));
  ctx.fillStyle = glowGrad;
  ctx.beginPath();
  ctx.arc(cx, cy, S * 0.28, 0, Math.PI * 2);
  ctx.fill();

  // Energy arcs across sphere surface
  ctx.strokeStyle = hexToRgba(teamColor, 0.80);
  ctx.lineWidth = S * 0.022;
  ctx.shadowColor = teamColor;
  ctx.shadowBlur = 12;
  const arcAngles = [0.1, 0.6, 1.3, 2.0, 2.7];
  for (let i = 0; i < arcAngles.length; i++) {
    const start = arcAngles[i];
    const end   = start + Math.PI * 0.42;
    const r     = S * 0.22 - i * S * 0.012;
    ctx.beginPath();
    ctx.arc(cx, cy, r, start, end);
    ctx.stroke();
  }
  ctx.shadowBlur = 0;

  // Bright central core
  ctx.fillStyle = hexToRgba(teamColor, 0.95);
  ctx.shadowColor = teamColor;
  ctx.shadowBlur = 20;
  ctx.beginPath();
  ctx.arc(cx, cy, S * 0.07, 0, Math.PI * 2);
  ctx.fill();
  ctx.shadowBlur = 0;
}

// Immortal — heavy Protoss walker: wide armoured body, two chunky legs, assault cannon arm
function drawImmortal(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawImmortal(ctx, S, 1, teamColor); ctx.restore(); return;
  }

  const cx = S / 2, cy = S / 2;

  // --- Ground shadow ---
  ctx.fillStyle = hexToRgba('#000000', 0.22);
  ctx.beginPath();
  ctx.ellipse(cx, cy + S * 0.28, S * 0.30, S * 0.06, 0, 0, Math.PI * 2);
  ctx.fill();

  // --- Legs (drawn first, behind body) ---
  // Dir 0 (front) / dir 2 (back): two legs spread wide below body
  // Dir 1 (side): two legs visible as thick rectangles in profile
  ctx.fillStyle = hexToRgba('#1e2430', 1.0);
  if (dir === 0 || dir === 2) {
    // Left leg — angled outward
    ctx.save();
    ctx.translate(cx - S * 0.18, cy + S * 0.12);
    ctx.rotate(-0.18);
    ctx.fillRect(-S * 0.09, 0, S * 0.09, S * 0.20);
    ctx.restore();
    // Right leg — angled outward
    ctx.save();
    ctx.translate(cx + S * 0.18, cy + S * 0.12);
    ctx.rotate(0.18);
    ctx.fillRect(0, 0, S * 0.09, S * 0.20);
    ctx.restore();
  } else {
    // Side view: front leg (visible) and partial rear leg
    ctx.fillRect(cx - S * 0.06, cy + S * 0.10, S * 0.10, S * 0.21);
    ctx.fillStyle = hexToRgba('#161c28', 1.0);
    ctx.fillRect(cx + S * 0.06, cy + S * 0.13, S * 0.09, S * 0.18);
  }

  // --- Shield plates (shoulders / sides) — broad armour panels ---
  const shieldColor = hexToRgba('#3a4558', 1.0);
  const shieldHighlight = hexToRgba('#4a5870', 1.0);
  if (dir === 0 || dir === 2) {
    // Two large elliptical shoulder plates on each side
    ctx.fillStyle = shieldColor;
    ctx.beginPath();
    ctx.ellipse(cx - S * 0.23, cy - S * 0.02, S * 0.14, S * 0.11, -0.3, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = shieldHighlight;
    ctx.beginPath();
    ctx.ellipse(cx - S * 0.24, cy - S * 0.04, S * 0.09, S * 0.07, -0.3, 0, Math.PI * 2);
    ctx.fill();

    ctx.fillStyle = shieldColor;
    ctx.beginPath();
    ctx.ellipse(cx + S * 0.23, cy - S * 0.02, S * 0.14, S * 0.11, 0.3, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = shieldHighlight;
    ctx.beginPath();
    ctx.ellipse(cx + S * 0.24, cy - S * 0.04, S * 0.09, S * 0.07, 0.3, 0, Math.PI * 2);
    ctx.fill();
  } else {
    // Side view: one large shield plate visible
    ctx.fillStyle = shieldColor;
    ctx.beginPath();
    ctx.ellipse(cx - S * 0.05, cy - S * 0.03, S * 0.16, S * 0.11, -0.15, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = shieldHighlight;
    ctx.beginPath();
    ctx.ellipse(cx - S * 0.07, cy - S * 0.05, S * 0.10, S * 0.07, -0.15, 0, Math.PI * 2);
    ctx.fill();
  }

  // --- Main body torso (raised above legs, covers centre) ---
  const bodyGrad = ctx.createLinearGradient(cx - S * 0.20, cy - S * 0.18, cx + S * 0.20, cy + S * 0.10);
  bodyGrad.addColorStop(0, hexToRgba('#3e4f66', 1.0));
  bodyGrad.addColorStop(0.4, hexToRgba('#2a3040', 1.0));
  bodyGrad.addColorStop(1, hexToRgba('#1a2030', 1.0));
  ctx.fillStyle = bodyGrad;
  const bx = cx - S * 0.18, by = cy - S * 0.18;
  const bw = S * 0.36, bh = S * 0.30;
  const br = S * 0.06;
  ctx.beginPath();
  ctx.moveTo(bx + br, by);
  ctx.lineTo(bx + bw - br, by);
  ctx.quadraticCurveTo(bx + bw, by, bx + bw, by + br);
  ctx.lineTo(bx + bw, by + bh - br);
  ctx.quadraticCurveTo(bx + bw, by + bh, bx + bw - br, by + bh);
  ctx.lineTo(bx + br, by + bh);
  ctx.quadraticCurveTo(bx, by + bh, bx, by + bh - br);
  ctx.lineTo(bx, by + br);
  ctx.quadraticCurveTo(bx, by, bx + br, by);
  ctx.closePath();
  ctx.fill();

  // Body armour panel edge highlight
  ctx.strokeStyle = hexToRgba('#5a6e8a', 0.7);
  ctx.lineWidth = S * 0.018;
  ctx.stroke();

  // --- Assault cannon arm ---
  if (dir === 0) {
    // Front view: cannon tip-on as small circle at right side
    ctx.fillStyle = hexToRgba('#1a2030', 1.0);
    ctx.beginPath();
    ctx.ellipse(cx + S * 0.24, cy + S * 0.02, S * 0.06, S * 0.04, 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.strokeStyle = hexToRgba('#3a4a60', 0.9);
    ctx.lineWidth = S * 0.015;
    ctx.stroke();
    // Energy cell at muzzle
    ctx.fillStyle = hexToRgba(teamColor, 0.85);
    ctx.shadowColor = teamColor;
    ctx.shadowBlur = 10;
    ctx.beginPath();
    ctx.ellipse(cx + S * 0.24, cy + S * 0.02, S * 0.03, S * 0.02, 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.shadowBlur = 0;
  } else if (dir === 1) {
    // Right side view: full cannon extending to right
    // Cannon mount
    ctx.fillStyle = hexToRgba('#1a2030', 1.0);
    ctx.fillRect(cx + S * 0.14, cy - S * 0.05, S * 0.36, S * 0.08);
    // Cannon barrel
    ctx.fillStyle = hexToRgba('#222e40', 1.0);
    ctx.fillRect(cx + S * 0.18, cy - S * 0.04, S * 0.30, S * 0.05);
    // Flared muzzle end
    ctx.fillStyle = hexToRgba('#1a2030', 1.0);
    ctx.fillRect(cx + S * 0.44, cy - S * 0.055, S * 0.04, S * 0.085);
    // Energy cell at muzzle tip
    ctx.fillStyle = hexToRgba(teamColor, 0.90);
    ctx.shadowColor = teamColor;
    ctx.shadowBlur = 10;
    ctx.beginPath();
    ctx.ellipse(cx + S * 0.46, cy - S * 0.015, S * 0.025, S * 0.025, 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.shadowBlur = 0;
  } else {
    // Back view: cannon visible on left side (mirrored from front)
    ctx.fillStyle = hexToRgba('#1a2030', 1.0);
    ctx.beginPath();
    ctx.ellipse(cx - S * 0.24, cy + S * 0.02, S * 0.06, S * 0.04, 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.strokeStyle = hexToRgba('#3a4a60', 0.9);
    ctx.lineWidth = S * 0.015;
    ctx.stroke();
    ctx.fillStyle = hexToRgba(teamColor, 0.85);
    ctx.shadowColor = teamColor;
    ctx.shadowBlur = 10;
    ctx.beginPath();
    ctx.ellipse(cx - S * 0.24, cy + S * 0.02, S * 0.03, S * 0.02, 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.shadowBlur = 0;
  }

  // --- Cockpit visor / sensor strip on body ---
  ctx.fillStyle = hexToRgba(teamColor, 0.50);
  ctx.shadowColor = teamColor;
  ctx.shadowBlur = 8;
  if (dir === 0 || dir === 2) {
    ctx.fillRect(cx - S * 0.10, cy - S * 0.14, S * 0.20, S * 0.04);
  } else {
    ctx.fillRect(cx - S * 0.10, cy - S * 0.14, S * 0.14, S * 0.04);
  }
  ctx.shadowBlur = 0;
}

// Archon — pure psionic energy being: no solid body, all team colour glow
function drawArchon(ctx, S, dir, teamColor) {
  const cx = S / 2, cy = S / 2;

  // Offset the two ring centres slightly based on direction
  const offsetX = (dir === 1) ? S * 0.06 : S * 0.04;
  const offsetY = (dir === 1) ? 0         : S * 0.02;
  const cx1 = cx - offsetX / 2, cy1 = cy - offsetY / 2;
  const cx2 = cx + offsetX / 2, cy2 = cy + offsetY / 2;

  // Outer ring strokes — both in team colour with heavy glow
  ctx.strokeStyle = hexToRgba(teamColor, 0.85);
  ctx.lineWidth   = S * 0.055;
  ctx.shadowColor = teamColor;
  ctx.shadowBlur  = 18;

  ctx.beginPath();
  ctx.arc(cx1, cy1, S * 0.26, 0, Math.PI * 2);
  ctx.stroke();

  ctx.beginPath();
  ctx.arc(cx2, cy2, S * 0.20, 0, Math.PI * 2);
  ctx.stroke();

  // Arc discharges — short partial arcs just beyond the outer ring
  ctx.strokeStyle = hexToRgba(teamColor, 0.70);
  ctx.lineWidth   = S * 0.020;
  ctx.shadowBlur  = 12;
  const dischargeAngles = [0.0, 1.1, 2.1, 3.2, 4.3, 5.3];
  for (let i = 0; i < dischargeAngles.length; i++) {
    const start = dischargeAngles[i];
    ctx.beginPath();
    ctx.arc(cx, cy, S * 0.32, start, start + 0.5);
    ctx.stroke();
  }
  ctx.shadowBlur = 0;

  // Central glowing core — solid fill so pixel (64,64) has non-zero alpha
  ctx.fillStyle   = hexToRgba(teamColor, 1.0);
  ctx.shadowColor = teamColor;
  ctx.shadowBlur  = 22;
  ctx.beginPath();
  ctx.arc(cx, cy, S * 0.08, 0, Math.PI * 2);
  ctx.fill();
  ctx.shadowBlur = 0;
}

// drawColossus — massive 4-legged Protoss walker with elevated body and thermal lances
// Protoss bronze-gold palette: hull #4a3820, legs #2a2010
// Smoke test samples (64,64). Stilts/legs pass through canvas centre for ALL 4 dirs.
function drawColossus(ctx, S, dir, teamColor) {
  const hullColor = '#4a3820';
  const legColor  = '#2a2010';
  const cx = S / 2, cy = S / 2;

  if (dir === 1 || dir === 3) {
    // Side view (dir 1/3) — tall profile: two long legs, elevated body, one lance
    // Legs span full canvas height. legX1 placed so it covers x=64 (cx).
    const legX1 = S * 0.44, legX2 = S * 0.62;  // legX1 at 56.3, width 0.16*S=20.5 → covers cx=64
    const hipY  = S * 0.38;

    ctx.fillStyle = legColor;
    // Front leg — wide enough to straddle cx=64
    ctx.fillRect(legX1 - S * 0.08, 0, S * 0.16, S);
    // Back leg
    ctx.fillRect(legX2 - S * 0.03, 0, S * 0.05, S);

    // Stilt supports from hip to body
    ctx.fillStyle = hullColor;
    ctx.fillRect(cx - S * 0.04, hipY - S * 0.12, S * 0.08, S * 0.12);

    // Body hull — elevated rectangle at top
    const bodyX = S * 0.15, bodyY = S * 0.05;
    const bodyW = S * 0.65, bodyH = S * 0.22;
    ctx.fillStyle = hullColor;
    ctx.beginPath();
    ctx.roundRect(bodyX, bodyY, bodyW, bodyH, S * 0.03);
    ctx.fill();

    // Thermal lance — extends right from body
    ctx.fillStyle = hexToRgba(hullColor, 0.9);
    ctx.fillRect(bodyX + bodyW, bodyY + bodyH * 0.3, S * 0.12, S * 0.04);

    // Lance tip glow
    ctx.fillStyle = hexToRgba(teamColor, 1.0);
    ctx.shadowColor = teamColor;
    ctx.shadowBlur  = 10;
    ctx.beginPath();
    ctx.arc(bodyX + bodyW + S * 0.13, bodyY + bodyH * 0.32 + S * 0.02, S * 0.025, 0, Math.PI * 2);
    ctx.fill();
    ctx.shadowBlur = 0;

  } else {
    // Front/back view (dir 0/2) — widest view: 4 legs spread, stilts, body at top, twin lances
    const hipY  = S * 0.52;  // hip joint Y — just below canvas centre so stilts cover (64,64)
    const footY = S;          // legs extend to bottom

    // 4 legs — angled out from hip
    ctx.fillStyle = legColor;
    const legPositions = [
      { hipX: S * 0.30, footX: S * 0.08 },  // far left
      { hipX: S * 0.42, footX: S * 0.28 },  // near left
      { hipX: S * 0.58, footX: S * 0.72 },  // near right
      { hipX: S * 0.70, footX: S * 0.92 },  // far right
    ];
    legPositions.forEach(({ hipX, footX }) => {
      ctx.beginPath();
      ctx.moveTo(hipX - S * 0.03, hipY);
      ctx.lineTo(hipX + S * 0.03, hipY);
      ctx.lineTo(footX + S * 0.025, footY);
      ctx.lineTo(footX - S * 0.025, footY);
      ctx.closePath();
      ctx.fill();
    });

    // Two stilt supports rising from hip centre up to body base
    // Left stilt ends at cx, right stilt starts at cx — together they span cx
    // ensuring pixel (64,64) has non-zero alpha (hipY > S/2, stilts reach y=64)
    ctx.fillStyle = hullColor;
    ctx.fillRect(cx - S * 0.16, S * 0.20, S * 0.16, hipY - S * 0.20);  // left: cx-20.5 → cx
    ctx.fillRect(cx,            S * 0.20, S * 0.16, hipY - S * 0.20);  // right: cx → cx+20.5

    // Body hull — large rounded rect near top; stilts connect at S*0.20
    const bodyX = S * 0.18, bodyY = S * 0.04;
    const bodyW = S * 0.64, bodyH = S * 0.18;
    ctx.fillStyle = hullColor;
    ctx.beginPath();
    ctx.roundRect(bodyX, bodyY, bodyW, bodyH, S * 0.025);
    ctx.fill();

    // Twin thermal lance arrays — two horizontal strips on top face of hull
    ctx.fillStyle = hexToRgba(hullColor, 0.85);
    ctx.fillRect(bodyX + S * 0.04, bodyY + bodyH * 0.2, S * 0.18, S * 0.035);  // left lance
    ctx.fillRect(bodyX + bodyW - S * 0.22, bodyY + bodyH * 0.2, S * 0.18, S * 0.035);  // right lance

    // Lance tip glows at outer ends
    ctx.fillStyle = hexToRgba(teamColor, 1.0);
    ctx.shadowColor = teamColor;
    ctx.shadowBlur  = 10;
    ctx.beginPath();
    ctx.arc(bodyX + S * 0.035, bodyY + bodyH * 0.22 + S * 0.017, S * 0.022, 0, Math.PI * 2);
    ctx.fill();
    ctx.beginPath();
    ctx.arc(bodyX + bodyW - S * 0.035, bodyY + bodyH * 0.22 + S * 0.017, S * 0.022, 0, Math.PI * 2);
    ctx.fill();
    ctx.shadowBlur = 0;
  }
}

// drawObserver — small Protoss cloaking drone, flattened disc silhouette
// Very dark body (~#0a0a1a) with 4 sensor protrusions and 2 team-coloured scan emitters.
// Dir 1 (side): edge-on thin ellipse. Smoke test samples (64,64) — disc centred there.
function drawObserver(ctx, S, dir, teamColor) {
  const cx = S / 2, cy = S / 2;
  const bodyColor    = '#0a0a1a';
  const sensorColor  = '#2a2a3a';
  const rx = S * 0.22;  // horizontal disc radius
  const ry = dir === 1 || dir === 3 ? S * 0.04 : S * 0.12;  // vertical — thin when side-on

  // Body disc
  ctx.fillStyle = bodyColor;
  ctx.beginPath();
  ctx.ellipse(cx, cy, rx, ry, 0, 0, Math.PI * 2);
  ctx.fill();

  // Outline
  ctx.strokeStyle = sensorColor;
  ctx.lineWidth = S * 0.02;
  ctx.beginPath();
  ctx.ellipse(cx, cy, rx, ry, 0, 0, Math.PI * 2);
  ctx.stroke();

  if (dir === 1 || dir === 3) {
    // Side (edge-on): two emitter dots at left and right tips
    ctx.fillStyle = hexToRgba(teamColor, 1.0);
    ctx.shadowColor = teamColor;
    ctx.shadowBlur  = 10;
    ctx.beginPath();
    ctx.arc(cx - rx, cy, S * 0.04, 0, Math.PI * 2);
    ctx.fill();
    ctx.beginPath();
    ctx.arc(cx + rx, cy, S * 0.04, 0, Math.PI * 2);
    ctx.fill();
    ctx.shadowBlur = 0;
  } else {
    // Front/back: 4 rectangular sensor protrusions (top, right, bottom, left)
    ctx.fillStyle = sensorColor;
    const pLen = S * 0.08, pW = S * 0.025;
    // top
    ctx.fillRect(cx - pW / 2, cy - ry - pLen, pW, pLen);
    // bottom
    ctx.fillRect(cx - pW / 2, cy + ry,        pW, pLen);
    // left
    ctx.fillRect(cx - rx - pLen, cy - pW / 2, pLen, pW);
    // right
    ctx.fillRect(cx + rx,        cy - pW / 2, pLen, pW);

    // Two scan emitters at left and right sides of disc
    ctx.fillStyle = hexToRgba(teamColor, 1.0);
    ctx.shadowColor = teamColor;
    ctx.shadowBlur  = 10;
    ctx.beginPath();
    ctx.arc(cx - rx, cy, S * 0.04, 0, Math.PI * 2);
    ctx.fill();
    ctx.beginPath();
    ctx.arc(cx + rx, cy, S * 0.04, 0, Math.PI * 2);
    ctx.fill();
    ctx.shadowBlur = 0;
  }
}

// drawVoidRay — large Protoss angular warship, dark blue-grey palette (~#1a2840).
// Diamond/arrowhead silhouette with swept-back wings. Prismatic beam emitter at bow.
// Dir-3 mirrors dir-1. Hull must cover canvas centre (64,64) for smoke test.
function drawVoidRay(ctx, S, dir, teamColor) {
  if (dir === 3) { ctx.save(); ctx.scale(-1, 1); ctx.translate(-S, 0); drawVoidRay(ctx, S, 1, teamColor); ctx.restore(); return; }
  const cx = S / 2, cy = S / 2;
  const hullColor   = '#1a2840';
  const panelColor  = '#253550';
  const edgeColor   = '#3a5070';

  if (dir === 0) {
    // Front — arrowhead pointing upward, wide swept wings left and right
    // Main diamond hull covering the centre
    ctx.fillStyle = hullColor;
    ctx.beginPath();
    ctx.moveTo(cx,             cy - S * 0.40);   // bow tip (top)
    ctx.lineTo(cx + S * 0.45, cy + S * 0.15);   // right wing tip
    ctx.lineTo(cx + S * 0.18, cy + S * 0.35);   // right wing inner trailing
    ctx.lineTo(cx,             cy + S * 0.20);   // centre rear
    ctx.lineTo(cx - S * 0.18, cy + S * 0.35);   // left wing inner trailing
    ctx.lineTo(cx - S * 0.45, cy + S * 0.15);   // left wing tip
    ctx.closePath();
    ctx.fill();

    // Hull panel lines along wing surfaces
    ctx.strokeStyle = edgeColor;
    ctx.lineWidth = S * 0.018;
    // right wing panel
    ctx.beginPath();
    ctx.moveTo(cx + S * 0.08, cy - S * 0.10);
    ctx.lineTo(cx + S * 0.38, cy + S * 0.12);
    ctx.stroke();
    // left wing panel
    ctx.beginPath();
    ctx.moveTo(cx - S * 0.08, cy - S * 0.10);
    ctx.lineTo(cx - S * 0.38, cy + S * 0.12);
    ctx.stroke();

    // Prismatic beam emitter at the bow tip — team colour glow
    ctx.fillStyle = hexToRgba(teamColor, 1.0);
    ctx.shadowColor = teamColor;
    ctx.shadowBlur  = 16;
    ctx.beginPath();
    ctx.arc(cx, cy - S * 0.38, S * 0.05, 0, Math.PI * 2);
    ctx.fill();
    ctx.shadowBlur = 0;

  } else if (dir === 2) {
    // Back — similar diamond but reversed; engine glow ports at rear (bottom)
    ctx.fillStyle = hullColor;
    ctx.beginPath();
    ctx.moveTo(cx,             cy + S * 0.40);   // rear bottom
    ctx.lineTo(cx + S * 0.45, cy - S * 0.15);   // right wing tip
    ctx.lineTo(cx + S * 0.18, cy - S * 0.35);   // right wing inner
    ctx.lineTo(cx,             cy - S * 0.20);   // bow (now at top, smaller)
    ctx.lineTo(cx - S * 0.18, cy - S * 0.35);   // left wing inner
    ctx.lineTo(cx - S * 0.45, cy - S * 0.15);   // left wing tip
    ctx.closePath();
    ctx.fill();

    // Panel lines
    ctx.strokeStyle = edgeColor;
    ctx.lineWidth = S * 0.018;
    ctx.beginPath();
    ctx.moveTo(cx + S * 0.08, cy + S * 0.10);
    ctx.lineTo(cx + S * 0.38, cy - S * 0.12);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(cx - S * 0.08, cy + S * 0.10);
    ctx.lineTo(cx - S * 0.38, cy - S * 0.12);
    ctx.stroke();

    // Engine glow ports at rear
    ctx.fillStyle = hexToRgba(teamColor, 1.0);
    ctx.shadowColor = teamColor;
    ctx.shadowBlur  = 14;
    ctx.beginPath();
    ctx.arc(cx - S * 0.10, cy + S * 0.36, S * 0.04, 0, Math.PI * 2);
    ctx.fill();
    ctx.beginPath();
    ctx.arc(cx + S * 0.10, cy + S * 0.36, S * 0.04, 0, Math.PI * 2);
    ctx.fill();
    ctx.shadowBlur = 0;

  } else {
    // Dir 1 — side profile: elongated blade, bow at left, engine at right
    // Large elongated hull covering the centre
    ctx.fillStyle = hullColor;
    ctx.beginPath();
    ctx.moveTo(cx - S * 0.46, cy);             // bow tip (left)
    ctx.lineTo(cx - S * 0.20, cy - S * 0.22); // upper front
    ctx.lineTo(cx + S * 0.20, cy - S * 0.18); // upper mid
    ctx.lineTo(cx + S * 0.46, cy - S * 0.05); // upper rear
    ctx.lineTo(cx + S * 0.46, cy + S * 0.10); // lower rear
    ctx.lineTo(cx + S * 0.10, cy + S * 0.22); // lower engine nacelle
    ctx.lineTo(cx - S * 0.20, cy + S * 0.18); // lower mid
    ctx.lineTo(cx - S * 0.40, cy + S * 0.08); // lower bow
    ctx.closePath();
    ctx.fill();

    // Panel line along the hull spine
    ctx.strokeStyle = edgeColor;
    ctx.lineWidth = S * 0.018;
    ctx.beginPath();
    ctx.moveTo(cx - S * 0.35, cy - S * 0.06);
    ctx.lineTo(cx + S * 0.35, cy - S * 0.04);
    ctx.stroke();

    // Bow tip glow (left) and engine glow (right)
    ctx.fillStyle = hexToRgba(teamColor, 1.0);
    ctx.shadowColor = teamColor;
    ctx.shadowBlur  = 14;
    ctx.beginPath();
    ctx.arc(cx - S * 0.44, cy, S * 0.04, 0, Math.PI * 2);
    ctx.fill();
    ctx.beginPath();
    ctx.arc(cx + S * 0.43, cy + S * 0.02, S * 0.04, 0, Math.PI * 2);
    ctx.fill();
    ctx.shadowBlur = 0;
  }
}

// drawCarrier — massive Protoss capital ship, dark navy palette (~#1a1a30).
// Wide elliptical hull with central hangar bay and interceptor silhouettes.
// Dir-3 mirrors dir-1. Hull ellipse centred at (S/2, S/2) covers smoke test pixel (64,64).
function drawCarrier(ctx, S, dir, teamColor) {
  if (dir === 3) { ctx.save(); ctx.scale(-1, 1); ctx.translate(-S, 0); drawCarrier(ctx, S, 1, teamColor); ctx.restore(); return; }
  const cx = S / 2, cy = S / 2;
  const hullColor   = '#1a1a30';
  const panelColor  = '#252545';
  const hangarColor = '#0f0f20';
  const interceptorColor = '#303050';

  if (dir === 0 || dir === 2) {
    // Top-down / bottom-up — wide elliptical hull centred at canvas centre
    const flip = dir === 2 ? -1 : 1;

    // Main hull ellipse — wide and flat, covering centre
    ctx.fillStyle = hullColor;
    ctx.beginPath();
    ctx.ellipse(cx, cy, S * 0.44, S * 0.30, 0, 0, Math.PI * 2);
    ctx.fill();

    // Hull panel — slightly lighter inner ellipse for depth
    ctx.fillStyle = panelColor;
    ctx.beginPath();
    ctx.ellipse(cx, cy, S * 0.36, S * 0.22, 0, 0, Math.PI * 2);
    ctx.fill();

    // Central hangar bay — darker rectangular fill in the belly
    ctx.fillStyle = hangarColor;
    ctx.fillRect(cx - S * 0.14, cy - S * 0.07, S * 0.28, S * 0.14);

    // Interceptor silhouettes inside bay — 4 tiny diamonds arranged in the bay
    ctx.fillStyle = interceptorColor;
    const iposX = [-S * 0.09, -S * 0.03, S * 0.03, S * 0.09];
    const iSize = S * 0.04;
    for (const ix of iposX) {
      ctx.beginPath();
      ctx.moveTo(cx + ix,          cy - iSize); // top
      ctx.lineTo(cx + ix + iSize,  cy);          // right
      ctx.lineTo(cx + ix,          cy + iSize); // bottom
      ctx.lineTo(cx + ix - iSize,  cy);          // left
      ctx.closePath();
      ctx.fill();
    }

    // Running lights — 4 small team colour circles at hull corners
    ctx.fillStyle = hexToRgba(teamColor, 1.0);
    ctx.shadowColor = teamColor;
    ctx.shadowBlur  = 8;
    const cornerPositions = [
      [cx - S * 0.34, cy - S * 0.18],
      [cx + S * 0.34, cy - S * 0.18],
      [cx - S * 0.34, cy + S * 0.18],
      [cx + S * 0.34, cy + S * 0.18],
    ];
    for (const [lx, ly] of cornerPositions) {
      ctx.beginPath();
      ctx.arc(lx, ly, S * 0.025, 0, Math.PI * 2);
      ctx.fill();
    }

    // Two rear engine ports — slightly larger team colour ellipses at stern
    const sternY = cy + flip * S * 0.24;
    ctx.shadowBlur = 10;
    ctx.beginPath();
    ctx.ellipse(cx - S * 0.10, sternY, S * 0.04, S * 0.028, 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.beginPath();
    ctx.ellipse(cx + S * 0.10, sternY, S * 0.04, S * 0.028, 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.shadowBlur = 0;

  } else {
    // Dir 1 — side profile: broad flat silhouette, engine at right
    // Wide horizontal hull ellipse covering canvas centre
    ctx.fillStyle = hullColor;
    ctx.beginPath();
    ctx.ellipse(cx, cy, S * 0.46, S * 0.08, 0, 0, Math.PI * 2);
    ctx.fill();

    // Hull ridge line along the middle
    ctx.strokeStyle = panelColor;
    ctx.lineWidth = S * 0.015;
    ctx.beginPath();
    ctx.moveTo(cx - S * 0.40, cy);
    ctx.lineTo(cx + S * 0.40, cy);
    ctx.stroke();

    // Rear engine glow — 3 team colour dots at the right end
    ctx.fillStyle = hexToRgba(teamColor, 1.0);
    ctx.shadowColor = teamColor;
    ctx.shadowBlur  = 10;
    const engineOffsets = [-S * 0.04, 0, S * 0.04];
    for (const ey of engineOffsets) {
      ctx.beginPath();
      ctx.arc(cx + S * 0.43, cy + ey, S * 0.022, 0, Math.PI * 2);
      ctx.fill();
    }
    ctx.shadowBlur = 0;
  }
}

// drawRavager — evolved Roach, larger and more angular, dark brownish-green Zerg palette.
// Wide body with bile cannon dome on back and bio-sacs on flanks. Dir-3 mirrors dir-1.
// Body ellipse centred at (S/2, S/2) covers smoke test pixel (64,64).
function drawRavager(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawRavager(ctx, S, 1, teamColor); ctx.restore(); return;
  }
  const cx = S / 2, cy = S / 2;
  const baseColor = '#2a1a0a';
  const midColor  = '#3a2a10';
  const plateColor = '#4a3418';

  if (dir === 0 || dir === 2) {
    // Dir 0/2: top-down view — wide body, legs on sides, cannon on top
    const flip = dir === 2 ? 1 : -1; // flip determines head/tail direction

    // Legs — 4 short angled strokes from body underside
    ctx.strokeStyle = midColor; ctx.lineWidth = 3;
    [[-0.32, 0.12], [-0.16, 0.18], [0.16, 0.18], [0.32, 0.12]].forEach(([dx, dy]) => {
      ctx.beginPath();
      ctx.moveTo(cx + dx * S * 0.7, cy + S * 0.05);
      ctx.lineTo(cx + dx * S, cy + dy * S + S * 0.06);
      ctx.stroke();
    });

    // Main body — broad rounded ellipse covering centre
    ctx.fillStyle = baseColor;
    ctx.beginPath();
    ctx.ellipse(cx, cy, S * 0.38, S * 0.26, 0, 0, Math.PI * 2);
    ctx.fill();

    // Mid carapace plate
    ctx.fillStyle = midColor;
    ctx.beginPath();
    ctx.ellipse(cx, cy - S * 0.04, S * 0.30, S * 0.18, 0, 0, Math.PI * 2);
    ctx.fill();

    // Armour ridge highlight
    ctx.fillStyle = plateColor;
    ctx.beginPath();
    ctx.ellipse(cx, cy - S * 0.06, S * 0.20, S * 0.10, 0, 0, Math.PI * 2);
    ctx.fill();

    // Head (dir 0) or tail (dir 2)
    const headY = cy + flip * S * 0.24;
    ctx.fillStyle = baseColor;
    ctx.beginPath();
    ctx.ellipse(cx, headY, S * 0.18, S * 0.13, 0, 0, Math.PI * 2);
    ctx.fill();

    if (dir === 0) {
      // Eyes
      ctx.fillStyle = '#ffe066';
      ctx.beginPath(); ctx.arc(cx - S * 0.09, headY - S * 0.04, S * 0.03, 0, Math.PI * 2); ctx.fill();
      ctx.beginPath(); ctx.arc(cx + S * 0.09, headY - S * 0.04, S * 0.03, 0, Math.PI * 2); ctx.fill();
    }

    // Bile cannon dome — raised circle on back with wide muzzle
    const cannonY = cy + flip * S * 0.04; // cannon slightly toward tail
    ctx.fillStyle = midColor;
    ctx.beginPath();
    ctx.arc(cx, cannonY, S * 0.10, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = plateColor;
    ctx.beginPath();
    ctx.arc(cx, cannonY, S * 0.06, 0, Math.PI * 2);
    ctx.fill();
    // Muzzle glow — team colour
    ctx.fillStyle = hexToRgba(teamColor, 1.0);
    ctx.shadowColor = teamColor; ctx.shadowBlur = 10;
    ctx.beginPath();
    ctx.ellipse(cx, cannonY, S * 0.04, S * 0.035, 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.shadowBlur = 0;

    // Bio-sacs — 3 small glowing ellipses on each flank
    [[-0.26, 0.0], [-0.26, 0.10], [0.26, 0.0], [0.26, 0.10], [0, 0.14]].forEach(([dx, dy]) => {
      const sx = cx + dx * S, sy = cy + dy * S;
      ctx.fillStyle = hexToRgba(teamColor, 0.85);
      ctx.shadowColor = teamColor; ctx.shadowBlur = 8;
      ctx.beginPath();
      ctx.ellipse(sx, sy, S * 0.04, S * 0.035, 0, 0, Math.PI * 2);
      ctx.fill();
    });
    ctx.shadowBlur = 0;

  } else {
    // Dir 1: right side profile — hunched body, cannon raised at back
    // Legs
    ctx.strokeStyle = midColor; ctx.lineWidth = 3;
    [[cx - S * 0.14, cy + S * 0.08, cx - S * 0.28, cy + S * 0.24],
     [cx,            cy + S * 0.10, cx,             cy + S * 0.28],
     [cx + S * 0.14, cy + S * 0.08, cx + S * 0.26, cy + S * 0.24]].forEach(([x1, y1, x2, y2]) => {
      ctx.beginPath(); ctx.moveTo(x1, y1); ctx.lineTo(x2, y2); ctx.stroke();
    });

    // Main body — large ellipse centred at canvas centre
    ctx.fillStyle = baseColor;
    ctx.beginPath();
    ctx.ellipse(cx, cy, S * 0.36, S * 0.22, 0, 0, Math.PI * 2);
    ctx.fill();

    // Carapace plate
    ctx.fillStyle = midColor;
    ctx.beginPath();
    ctx.ellipse(cx - S * 0.03, cy - S * 0.06, S * 0.28, S * 0.14, 0, 0, Math.PI * 2);
    ctx.fill();

    // Head — front-left
    ctx.fillStyle = baseColor;
    ctx.beginPath();
    ctx.ellipse(cx - S * 0.24, cy - S * 0.08, S * 0.13, S * 0.10, 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = midColor;
    ctx.beginPath();
    ctx.ellipse(cx - S * 0.24, cy - S * 0.03, S * 0.10, S * 0.055, 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = '#ffe066';
    ctx.beginPath();
    ctx.arc(cx - S * 0.30, cy - S * 0.12, S * 0.028, 0, Math.PI * 2);
    ctx.fill();

    // Bile cannon — raised dome at upper-back, angled slightly
    ctx.fillStyle = midColor;
    ctx.beginPath();
    ctx.arc(cx + S * 0.18, cy - S * 0.18, S * 0.09, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = plateColor;
    ctx.beginPath();
    ctx.arc(cx + S * 0.18, cy - S * 0.18, S * 0.055, 0, Math.PI * 2);
    ctx.fill();
    // Muzzle tip — wide ellipse with team colour glow
    ctx.fillStyle = hexToRgba(teamColor, 1.0);
    ctx.shadowColor = teamColor; ctx.shadowBlur = 10;
    ctx.beginPath();
    ctx.ellipse(cx + S * 0.18, cy - S * 0.18, S * 0.038, S * 0.032, 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.shadowBlur = 0;

    // Bio-sacs — 2 visible on flank
    [[cx - S * 0.05, cy - S * 0.08], [cx + S * 0.10, cy - S * 0.07]].forEach(([sx, sy]) => {
      ctx.fillStyle = hexToRgba(teamColor, 0.85);
      ctx.shadowColor = teamColor; ctx.shadowBlur = 8;
      ctx.beginPath();
      ctx.ellipse(sx, sy, S * 0.04, S * 0.035, 0, 0, Math.PI * 2);
      ctx.fill();
    });
    ctx.shadowBlur = 0;
  }
}

// drawInfestor — fungal Zerg caster, wide low body with tentacle protrusions.
// Dark purple-green palette. Body ellipse at (S/2, S/2+10) covers smoke-test pixel (64,64).
// Dir-3 mirrors dir-1.
function drawInfestor(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawInfestor(ctx, S, 1, teamColor); ctx.restore(); return;
  }
  const cx = S / 2, cy = S / 2 + 10;
  const bodyColor = '#1a0a1a';
  const midColor  = '#2a1a10';
  const tentacleColor = '#2a1a2a';

  if (dir === 0 || dir === 2) {
    // Top-down: wide squat body, tentacles radiating outward
    const flip = dir === 2 ? 1 : -1;

    // Tentacles — 5 wavy strokes from body perimeter
    ctx.strokeStyle = tentacleColor; ctx.lineWidth = S * 0.03;
    [
      [-0.35, -0.10, -0.50, -0.22],
      [-0.20, -0.22, -0.28, -0.38],
      [ 0.00, -0.28,  0.00, -0.44],
      [ 0.20, -0.22,  0.28, -0.38],
      [ 0.35, -0.10,  0.50, -0.22]
    ].forEach(([dx1, dy1, dx2, dy2]) => {
      const signedDy1 = dy1 * flip, signedDy2 = dy2 * flip;
      ctx.beginPath();
      ctx.moveTo(cx + dx1 * S, cy + signedDy1 * S);
      ctx.quadraticCurveTo(
        cx + (dx1 + dx2) * 0.5 * S + S * 0.06, cy + (signedDy1 + signedDy2) * 0.5 * S,
        cx + dx2 * S, cy + signedDy2 * S
      );
      ctx.stroke();
    });

    // Main body — wide filled ellipse
    ctx.fillStyle = bodyColor;
    ctx.beginPath();
    ctx.ellipse(cx, cy, S * 0.30, S * 0.20, 0, 0, Math.PI * 2);
    ctx.fill();

    // Mid carapace
    ctx.fillStyle = midColor;
    ctx.beginPath();
    ctx.ellipse(cx, cy - S * 0.03, S * 0.22, S * 0.13, 0, 0, Math.PI * 2);
    ctx.fill();

    // Head nub toward direction of travel
    const headY = cy + flip * S * 0.18;
    ctx.fillStyle = bodyColor;
    ctx.beginPath();
    ctx.ellipse(cx, headY, S * 0.12, S * 0.09, 0, 0, Math.PI * 2);
    ctx.fill();

    // Eyes
    if (dir === 0) {
      ctx.fillStyle = '#cc44aa';
      ctx.beginPath(); ctx.arc(cx - S * 0.07, headY - S * 0.03, S * 0.028, 0, Math.PI * 2); ctx.fill();
      ctx.beginPath(); ctx.arc(cx + S * 0.07, headY - S * 0.03, S * 0.028, 0, Math.PI * 2); ctx.fill();
    }

    // Infested glow — team colour radial at body centre
    ctx.fillStyle = hexToRgba(teamColor, 0.70);
    ctx.shadowColor = teamColor; ctx.shadowBlur = 12;
    ctx.beginPath();
    ctx.ellipse(cx, cy, S * 0.10, S * 0.07, 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.shadowBlur = 0;

  } else {
    // Dir 1: side profile — hunched low body, tentacles from rear/underside
    // Rear tentacles
    ctx.strokeStyle = tentacleColor; ctx.lineWidth = S * 0.03;
    [
      [cx + S * 0.10, cy + S * 0.05,  cx + S * 0.30, cy - S * 0.10,  cx + S * 0.44, cy - S * 0.04],
      [cx + S * 0.15, cy + S * 0.08,  cx + S * 0.36, cy + S * 0.12,  cx + S * 0.46, cy + S * 0.08],
      [cx,            cy + S * 0.10,  cx + S * 0.16, cy + S * 0.28,  cx + S * 0.28, cy + S * 0.24],
      [cx - S * 0.10, cy + S * 0.06,  cx - S * 0.20, cy + S * 0.24,  cx - S * 0.30, cy + S * 0.20],
      [cx - S * 0.20, cy + S * 0.04,  cx - S * 0.36, cy + S * 0.18,  cx - S * 0.44, cy + S * 0.10]
    ].forEach(([x1, y1, cpx, cpy, x2, y2]) => {
      ctx.beginPath();
      ctx.moveTo(x1, y1);
      ctx.quadraticCurveTo(cpx, cpy, x2, y2);
      ctx.stroke();
    });

    // Main body — wide low ellipse
    ctx.fillStyle = bodyColor;
    ctx.beginPath();
    ctx.ellipse(cx, cy, S * 0.30, S * 0.20, 0, 0, Math.PI * 2);
    ctx.fill();

    // Mid carapace
    ctx.fillStyle = midColor;
    ctx.beginPath();
    ctx.ellipse(cx - S * 0.02, cy - S * 0.04, S * 0.22, S * 0.12, 0, 0, Math.PI * 2);
    ctx.fill();

    // Head — front-left
    ctx.fillStyle = bodyColor;
    ctx.beginPath();
    ctx.ellipse(cx - S * 0.22, cy - S * 0.06, S * 0.11, S * 0.09, 0, 0, Math.PI * 2);
    ctx.fill();
    // Eye
    ctx.fillStyle = '#cc44aa';
    ctx.beginPath();
    ctx.arc(cx - S * 0.28, cy - S * 0.10, S * 0.026, 0, Math.PI * 2);
    ctx.fill();

    // Infested glow — team colour at body centre
    ctx.fillStyle = hexToRgba(teamColor, 0.70);
    ctx.shadowColor = teamColor; ctx.shadowBlur = 12;
    ctx.beginPath();
    ctx.ellipse(cx, cy, S * 0.10, S * 0.07, 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.shadowBlur = 0;
  }
}

// drawLurker — always burrowed; same surface mound view from all directions.
// Ground mound centred at (S/2, S/2+8). Mound top edge reaches y≈54, so
// smoke-test pixel (64,64) is inside the ellipse. Dir-3 mirrors dir-1.
function drawLurker(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawLurker(ctx, S, 1, teamColor); ctx.restore(); return;
  }
  const cx = S / 2, cy = S / 2 + 8;
  const moundColor  = '#3a2a10';
  const ridgeColor  = '#5a3f18';
  const spineColor  = '#1a1a10';

  // Ground mound — wide filled ellipse, horizontal radius 0.30×S, vertical 0.14×S
  // Top of ellipse: cy - 0.14×128 = 72 - 17.9 ≈ 54 — pixel (64,64) is inside.
  ctx.fillStyle = moundColor;
  ctx.beginPath();
  ctx.ellipse(cx, cy, S * 0.30, S * 0.14, 0, 0, Math.PI * 2);
  ctx.fill();

  // Ridge line across mound top
  ctx.strokeStyle = ridgeColor;
  ctx.lineWidth = S * 0.015;
  ctx.beginPath();
  ctx.moveTo(cx - S * 0.28, cy - S * 0.04);
  ctx.bezierCurveTo(cx - S * 0.10, cy - S * 0.10, cx + S * 0.10, cy - S * 0.10, cx + S * 0.28, cy - S * 0.04);
  ctx.stroke();

  // 5 spines erupting from the mound — thin triangles with team-colour tips
  const spines = [
    { bx: cx - S * 0.22, by: cy - S * 0.06, tx: cx - S * 0.28, ty: cy - S * 0.28 },
    { bx: cx - S * 0.10, by: cy - S * 0.10, tx: cx - S * 0.12, ty: cy - S * 0.36 },
    { bx: cx,            by: cy - S * 0.13, tx: cx,             ty: cy - S * 0.40 },
    { bx: cx + S * 0.10, by: cy - S * 0.10, tx: cx + S * 0.12, ty: cy - S * 0.36 },
    { bx: cx + S * 0.22, by: cy - S * 0.06, tx: cx + S * 0.28, ty: cy - S * 0.28 },
  ];
  const halfW = S * 0.025;
  spines.forEach(({ bx, by, tx, ty }) => {
    ctx.fillStyle = spineColor;
    ctx.beginPath();
    ctx.moveTo(tx, ty);                  // apex
    ctx.lineTo(bx - halfW, by);         // base-left
    ctx.lineTo(bx + halfW, by);         // base-right
    ctx.closePath();
    ctx.fill();

    // Team-colour glow at spine tip
    ctx.fillStyle = hexToRgba(teamColor, 0.85);
    ctx.shadowColor = teamColor; ctx.shadowBlur = 8;
    ctx.beginPath();
    ctx.arc(tx, ty, S * 0.022, 0, Math.PI * 2);
    ctx.fill();
    ctx.shadowBlur = 0;
  });
}

// drawSwarmHost — large armoured beetle, always on ground (never flies).
// Carapace centred at (S/2, S/2+4), horizontal radius S×0.36, vertical S×0.22.
// Top edge of ellipse: (S/2+4) - S×0.22 = 68 - 28.2 ≈ 40, so pixel (64,64) is inside.
// Dir-3 mirrors dir-1.
function drawSwarmHost(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawSwarmHost(ctx, S, 1, teamColor); ctx.restore(); return;
  }
  const cx = S / 2, cy = S / 2 + 4;
  const carapaceColor  = '#2a3010';
  const undersideColor = '#1a2008';
  const ridgeColor     = '#3d4a18';

  if (dir === 0 || dir === 2) {
    // Top-down / bottom-up: wide oval carapace fills most of canvas.

    // Underside shadow ring (slightly larger, darker oval)
    ctx.fillStyle = undersideColor;
    ctx.beginPath();
    ctx.ellipse(cx, cy + S * 0.04, S * 0.38, S * 0.20, 0, 0, Math.PI * 2);
    ctx.fill();

    // Main carapace — wide rounded oval
    ctx.fillStyle = carapaceColor;
    ctx.beginPath();
    ctx.ellipse(cx, cy, S * 0.36, S * 0.22, 0, 0, Math.PI * 2);
    ctx.fill();

    // Ridge line across carapace centre
    ctx.strokeStyle = ridgeColor;
    ctx.lineWidth = S * 0.018;
    ctx.beginPath();
    ctx.moveTo(cx - S * 0.30, cy);
    ctx.bezierCurveTo(cx - S * 0.12, cy - S * 0.08, cx + S * 0.12, cy - S * 0.08, cx + S * 0.30, cy);
    ctx.stroke();

    // 3 legs per side — short angled lines from underside
    const legColor = '#1a2008';
    ctx.strokeStyle = legColor;
    ctx.lineWidth = S * 0.020;
    const legOffsets = [-0.22, 0, 0.22];
    legOffsets.forEach(ox => {
      // Left legs
      ctx.beginPath();
      ctx.moveTo(cx + ox * S, cy + S * 0.10);
      ctx.lineTo(cx + ox * S - S * 0.12, cy + S * 0.20);
      ctx.stroke();
      // Right legs
      ctx.beginPath();
      ctx.moveTo(cx + ox * S, cy + S * 0.10);
      ctx.lineTo(cx + ox * S + S * 0.12, cy + S * 0.20);
      ctx.stroke();
    });

    // Spawn vents — 3 dark ellipses on carapace, team colour glow within
    const ventCentres = [cx - S * 0.16, cx, cx + S * 0.16];
    ventCentres.forEach(vx => {
      // Vent hole (dark ellipse)
      ctx.fillStyle = '#0a0e04';
      ctx.beginPath();
      ctx.ellipse(vx, cy - S * 0.04, S * 0.055, S * 0.035, 0, 0, Math.PI * 2);
      ctx.fill();
      // Team colour glow from within
      ctx.fillStyle = hexToRgba(teamColor, 0.80);
      ctx.shadowColor = teamColor; ctx.shadowBlur = 12;
      ctx.beginPath();
      ctx.arc(vx, cy - S * 0.04, S * 0.025, 0, Math.PI * 2);
      ctx.fill();
      ctx.shadowBlur = 0;
    });

  } else {
    // dir === 1: side profile — raised carapace hump, vents on top, legs below.
    const profCx = cx, profCy = cy;

    // Body underside (dark lower ellipse)
    ctx.fillStyle = undersideColor;
    ctx.beginPath();
    ctx.ellipse(profCx, profCy + S * 0.06, S * 0.36, S * 0.10, 0, 0, Math.PI * 2);
    ctx.fill();

    // Carapace hump — tall-ish ellipse, shifted upward
    ctx.fillStyle = carapaceColor;
    ctx.beginPath();
    ctx.ellipse(profCx, profCy - S * 0.04, S * 0.36, S * 0.18, 0, 0, Math.PI * 2);
    ctx.fill();

    // Ridge along top arc
    ctx.strokeStyle = ridgeColor;
    ctx.lineWidth = S * 0.018;
    ctx.beginPath();
    ctx.moveTo(profCx - S * 0.30, profCy - S * 0.04);
    ctx.bezierCurveTo(profCx - S * 0.12, profCy - S * 0.16, profCx + S * 0.12, profCy - S * 0.16, profCx + S * 0.30, profCy - S * 0.04);
    ctx.stroke();

    // 2 visible legs below body
    ctx.strokeStyle = '#1a2008';
    ctx.lineWidth = S * 0.022;
    [cx - S * 0.14, cx + S * 0.14].forEach(lx => {
      ctx.beginPath();
      ctx.moveTo(lx, profCy + S * 0.06);
      ctx.lineTo(lx - S * 0.06, profCy + S * 0.18);
      ctx.stroke();
    });

    // 2 spawn vents on carapace top surface
    const ventCentresSide = [profCx - S * 0.12, profCx + S * 0.12];
    ventCentresSide.forEach(vx => {
      ctx.fillStyle = '#0a0e04';
      ctx.beginPath();
      ctx.ellipse(vx, profCy - S * 0.10, S * 0.055, S * 0.030, 0, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = hexToRgba(teamColor, 0.80);
      ctx.shadowColor = teamColor; ctx.shadowBlur = 12;
      ctx.beginPath();
      ctx.arc(vx, profCy - S * 0.10, S * 0.022, 0, Math.PI * 2);
      ctx.fill();
      ctx.shadowBlur = 0;
    });
  }
}

// drawQueen — tall Zerg queen, always on ground (never flies).
// Upper body centred at (S/2, S/2-8); leg section ellipse centred at (S/2, S/2+12),
// vertical radius 14 — covers canvas centre (64,64).
// Dir-3 mirrors dir-1.
function drawQueen(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawQueen(ctx, S, 1, teamColor); ctx.restore(); return;
  }
  const cx = S / 2;
  const bodyColor  = '#1a0a2a';
  const darkColor  = '#120618';
  const wingColor  = '#150820';

  if (dir === 0 || dir === 2) {
    // Top-down / bottom-up view

    // Leg section — wide lower ellipse covering canvas centre
    ctx.fillStyle = darkColor;
    ctx.beginPath();
    ctx.ellipse(cx, S / 2 + 14, S * 0.20, S * 0.12, 0, 0, Math.PI * 2);
    ctx.fill();

    // Wing appendages — swept-back triangles on each side
    ctx.fillStyle = wingColor;
    ctx.beginPath();
    ctx.moveTo(cx - S * 0.08, S / 2 - 12);
    ctx.lineTo(cx - S * 0.36, S / 2 - 2);
    ctx.lineTo(cx - S * 0.28, S / 2 + 6);
    ctx.closePath();
    ctx.fill();
    ctx.beginPath();
    ctx.moveTo(cx + S * 0.08, S / 2 - 12);
    ctx.lineTo(cx + S * 0.36, S / 2 - 2);
    ctx.lineTo(cx + S * 0.28, S / 2 + 6);
    ctx.closePath();
    ctx.fill();

    // Upper body / head — narrow elongated ellipse
    ctx.fillStyle = bodyColor;
    ctx.beginPath();
    ctx.ellipse(cx, S / 2 - 8, S * 0.13, S * 0.22, 0, 0, Math.PI * 2);
    ctx.fill();

    // Tentacle arms — two wavy bezier strokes curling outward downward
    ctx.strokeStyle = darkColor;
    ctx.lineWidth = S * 0.025;
    ctx.beginPath();
    ctx.moveTo(cx - S * 0.10, S / 2 + 4);
    ctx.bezierCurveTo(cx - S * 0.22, S / 2 + 16, cx - S * 0.28, S / 2 + 22, cx - S * 0.20, S / 2 + 32);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(cx + S * 0.10, S / 2 + 4);
    ctx.bezierCurveTo(cx + S * 0.22, S / 2 + 16, cx + S * 0.28, S / 2 + 22, cx + S * 0.20, S / 2 + 32);
    ctx.stroke();

    // Bio-sacs on body flanks — team colour glow
    [cx - S * 0.12, cx + S * 0.12].forEach(bx => {
      ctx.fillStyle = darkColor;
      ctx.beginPath();
      ctx.ellipse(bx, S / 2 - 4, S * 0.055, S * 0.075, 0, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = hexToRgba(teamColor, 0.85);
      ctx.shadowColor = teamColor; ctx.shadowBlur = 10;
      ctx.beginPath();
      ctx.arc(bx, S / 2 - 4, S * 0.028, 0, Math.PI * 2);
      ctx.fill();
      ctx.shadowBlur = 0;
    });

  } else {
    // dir === 1: side profile — narrow, tall figure

    // Leg section — lower ellipse covering canvas centre
    ctx.fillStyle = darkColor;
    ctx.beginPath();
    ctx.ellipse(cx, S / 2 + 12, S * 0.16, S * 0.12, 0, 0, Math.PI * 2);
    ctx.fill();

    // Wing — single swept-back triangle trailing behind
    ctx.fillStyle = wingColor;
    ctx.beginPath();
    ctx.moveTo(cx - S * 0.06, S / 2 - 14);
    ctx.lineTo(cx - S * 0.32, S / 2 - 2);
    ctx.lineTo(cx - S * 0.22, S / 2 + 8);
    ctx.closePath();
    ctx.fill();

    // Upper body — narrow elongated ellipse
    ctx.fillStyle = bodyColor;
    ctx.beginPath();
    ctx.ellipse(cx, S / 2 - 8, S * 0.10, S * 0.22, 0, 0, Math.PI * 2);
    ctx.fill();

    // Tentacle arms trailing downward behind body
    ctx.strokeStyle = darkColor;
    ctx.lineWidth = S * 0.022;
    ctx.beginPath();
    ctx.moveTo(cx - S * 0.06, S / 2 + 6);
    ctx.bezierCurveTo(cx - S * 0.18, S / 2 + 18, cx - S * 0.22, S / 2 + 24, cx - S * 0.14, S / 2 + 32);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(cx + S * 0.06, S / 2 + 6);
    ctx.bezierCurveTo(cx + S * 0.14, S / 2 + 18, cx + S * 0.06, S / 2 + 24, cx + S * 0.08, S / 2 + 32);
    ctx.stroke();

    // Bio-sac — one visible on near flank
    ctx.fillStyle = darkColor;
    ctx.beginPath();
    ctx.ellipse(cx + S * 0.10, S / 2 - 6, S * 0.045, S * 0.065, 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = hexToRgba(teamColor, 0.85);
    ctx.shadowColor = teamColor; ctx.shadowBlur = 10;
    ctx.beginPath();
    ctx.arc(cx + S * 0.10, S / 2 - 6, S * 0.022, 0, Math.PI * 2);
    ctx.fill();
    ctx.shadowBlur = 0;
  }
}

// drawUltralisk — massive Zerg tank unit with kaiser blade scythes.
// Huge carapace body spans most of the canvas, centred at (S/2, S/2).
// Two large curved scythe blades rise from each shoulder.
// Bio-sac clusters on flanks glow with team colour.
// Dir-3 mirrors dir-1.
function drawUltralisk(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawUltralisk(ctx, S, 1, teamColor); ctx.restore(); return;
  }
  const cx = S / 2;
  const cy = S / 2;
  const bodyColor  = '#1a1008';
  const boneColor  = '#3a3020';
  const darkColor  = '#0e0b04';

  if (dir === 0 || dir === 2) {
    // Top-down view — wide oval carapace fills most of the canvas

    // Main carapace body — large rounded rect nearly filling canvas
    ctx.fillStyle = bodyColor;
    ctx.beginPath();
    ctx.ellipse(cx, cy, S * 0.40, S * 0.30, 0, 0, Math.PI * 2);
    ctx.fill();

    // Carapace ridge plates — dark segmented lines across the body
    ctx.strokeStyle = darkColor;
    ctx.lineWidth = S * 0.025;
    for (let i = -1; i <= 1; i++) {
      ctx.beginPath();
      ctx.moveTo(cx - S * 0.32, cy + i * S * 0.09);
      ctx.lineTo(cx + S * 0.32, cy + i * S * 0.09);
      ctx.stroke();
    }

    // Kaiser blade scythes — large arcs rising above each shoulder
    [[-1, -Math.PI * 0.85, -Math.PI * 0.15], [1, Math.PI + Math.PI * 0.15, Math.PI + Math.PI * 0.85]].forEach(([side, startA, endA]) => {
      const bx = cx + side * S * 0.28;
      const by = cy - S * 0.10;
      // Blade fill — bone-grey crescent
      ctx.fillStyle = boneColor;
      ctx.beginPath();
      ctx.arc(bx, by, S * 0.28, startA, endA, side < 0);
      ctx.arc(bx, by, S * 0.18, endA, startA, side > 0);
      ctx.closePath();
      ctx.fill();
      // Inner blade edge — team colour glow
      ctx.strokeStyle = hexToRgba(teamColor, 0.80);
      ctx.lineWidth = S * 0.03;
      ctx.shadowColor = teamColor; ctx.shadowBlur = 10;
      ctx.beginPath();
      ctx.arc(bx, by, S * 0.20, startA, endA, side < 0);
      ctx.stroke();
      ctx.shadowBlur = 0;
    });

    // Bio-sac clusters on body flanks — two groups per side
    [[-S * 0.28, -S * 0.06], [-S * 0.28, S * 0.06],
     [ S * 0.28, -S * 0.06], [ S * 0.28,  S * 0.06]].forEach(([dx, dy]) => {
      ctx.fillStyle = darkColor;
      ctx.beginPath();
      ctx.ellipse(cx + dx, cy + dy, S * 0.055, S * 0.045, 0, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = hexToRgba(teamColor, 0.80);
      ctx.shadowColor = teamColor; ctx.shadowBlur = 10;
      ctx.beginPath();
      ctx.arc(cx + dx, cy + dy, S * 0.024, 0, Math.PI * 2);
      ctx.fill();
      ctx.shadowBlur = 0;
    });

    // Heavy legs — 4 short thick rects below body
    ctx.fillStyle = darkColor;
    [[-S * 0.26, S * 0.22], [-S * 0.10, S * 0.28], [S * 0.10, S * 0.28], [S * 0.26, S * 0.22]].forEach(([lx, ly]) => {
      ctx.beginPath();
      ctx.roundRect(cx + lx - S * 0.04, cy + ly, S * 0.08, S * 0.10, S * 0.02);
      ctx.fill();
    });

  } else {
    // dir === 1: side profile — forward lean, massive bulk

    // Main carapace body — large ellipse, slightly forward-leaning
    ctx.fillStyle = bodyColor;
    ctx.beginPath();
    ctx.ellipse(cx + S * 0.04, cy + S * 0.04, S * 0.36, S * 0.24, -0.18, 0, Math.PI * 2);
    ctx.fill();

    // Carapace ridge plates — two horizontal lines
    ctx.strokeStyle = darkColor;
    ctx.lineWidth = S * 0.022;
    [-S * 0.07, S * 0.04].forEach(dy => {
      ctx.beginPath();
      ctx.moveTo(cx - S * 0.28, cy + dy);
      ctx.lineTo(cx + S * 0.28, cy + dy);
      ctx.stroke();
    });

    // Single dominant kaiser blade — rises dramatically above the body
    const bx = cx - S * 0.08;
    const by = cy - S * 0.08;
    ctx.fillStyle = boneColor;
    ctx.beginPath();
    ctx.arc(bx, by, S * 0.30, -Math.PI * 0.90, -Math.PI * 0.10, false);
    ctx.arc(bx, by, S * 0.18, -Math.PI * 0.10, -Math.PI * 0.90, true);
    ctx.closePath();
    ctx.fill();
    // Inner blade glow
    ctx.strokeStyle = hexToRgba(teamColor, 0.80);
    ctx.lineWidth = S * 0.03;
    ctx.shadowColor = teamColor; ctx.shadowBlur = 10;
    ctx.beginPath();
    ctx.arc(bx, by, S * 0.21, -Math.PI * 0.88, -Math.PI * 0.12, false);
    ctx.stroke();
    ctx.shadowBlur = 0;

    // Bio-sac clusters — two on near flank
    [[S * 0.16, -S * 0.06], [S * 0.20, S * 0.06]].forEach(([dx, dy]) => {
      ctx.fillStyle = darkColor;
      ctx.beginPath();
      ctx.ellipse(cx + dx, cy + dy, S * 0.055, S * 0.045, 0, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = hexToRgba(teamColor, 0.80);
      ctx.shadowColor = teamColor; ctx.shadowBlur = 10;
      ctx.beginPath();
      ctx.arc(cx + dx, cy + dy, S * 0.024, 0, Math.PI * 2);
      ctx.fill();
      ctx.shadowBlur = 0;
    });

    // Two heavy legs visible on the side
    ctx.fillStyle = darkColor;
    [[-S * 0.14, S * 0.22], [S * 0.10, S * 0.24]].forEach(([lx, ly]) => {
      ctx.beginPath();
      ctx.roundRect(cx + lx - S * 0.04, cy + ly, S * 0.09, S * 0.09, S * 0.02);
      ctx.fill();
    });
  }
}

// drawCorruptor — Zerg flying spore-ball corruption unit.
// Dark purple-grey lumpy body with bioluminescent spore patches and tentacle clusters.
// Dir-3 mirrors dir-1.
function drawCorruptor(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawCorruptor(ctx, S, 1, teamColor); ctx.restore(); return;
  }
  const cx = S / 2;
  const cy = S / 2;
  const bodyColor  = '#1a0a1a';
  const bumpColor  = '#120812';
  const darkColor  = '#0d060d';

  // Main body — large filled circle centred at (cx, cy), covers pixel (64,64)
  ctx.fillStyle = bodyColor;
  ctx.beginPath();
  ctx.arc(cx, cy, S * 0.26, 0, Math.PI * 2);
  ctx.fill();

  // Surface bumps — lumpy organic blobs around the body edge
  const bumps = (dir === 0 || dir === 2)
    ? [[0, -0.22], [0.18, -0.13], [0.22, 0.08], [-0.18, 0.10], [-0.08, 0.22], [0.06, -0.08]]
    : [[0, -0.22], [0.16, -0.14], [0.22, 0.06], [0.10, 0.20], [-0.08, 0.22], [-0.14, 0.04]];
  bumps.forEach(([bx, by]) => {
    ctx.fillStyle = bumpColor;
    ctx.beginPath();
    ctx.ellipse(cx + bx * S, cy + by * S, S * 0.085, S * 0.072, bx * 0.8, 0, Math.PI * 2);
    ctx.fill();
  });

  // Tentacle clusters — short wavy strokes hanging below the body
  ctx.lineWidth = S * 0.025;
  ctx.lineCap = 'round';
  const tentacleOffsets = (dir === 1)
    ? [[0.08, 0.20], [0.18, 0.16], [0.24, 0.08]]
    : [[-0.14, 0.20], [0, 0.22], [0.14, 0.20], [0.22, 0.10]];
  tentacleOffsets.forEach(([tx, ty]) => {
    ctx.strokeStyle = darkColor;
    ctx.beginPath();
    ctx.moveTo(cx + tx * S, cy + ty * S);
    ctx.quadraticCurveTo(
      cx + tx * S + (Math.random() - 0.5) * S * 0.08,
      cy + ty * S + S * 0.08,
      cx + tx * S + (Math.random() - 0.5) * S * 0.06,
      cy + ty * S + S * 0.14
    );
    ctx.stroke();
  });

  // Bioluminescent spore glow patches — team colour spots on body surface
  const glows = (dir === 0 || dir === 2)
    ? [[0.10, -0.10], [-0.12, 0.04], [0.02, 0.14]]
    : [[0.12, -0.08], [0.08, 0.12], [0.18, 0.02]];
  glows.forEach(([gx, gy]) => {
    ctx.fillStyle = hexToRgba(teamColor, 0.85);
    ctx.shadowColor = teamColor; ctx.shadowBlur = 12;
    ctx.beginPath();
    ctx.arc(cx + gx * S, cy + gy * S, S * 0.048, 0, Math.PI * 2);
    ctx.fill();
    ctx.shadowBlur = 0;
  });
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
  UNIT_MATS['GHOST_F']    = makeDirTextures(drawGhost,    TEAM_COLOR_FRIENDLY);
  UNIT_MATS['GHOST_E']    = makeDirTextures(drawGhost,    TEAM_COLOR_ENEMY);
  UNIT_MATS['CYCLONE_F']    = makeDirTextures(drawCyclone,    TEAM_COLOR_FRIENDLY);
  UNIT_MATS['CYCLONE_E']    = makeDirTextures(drawCyclone,    TEAM_COLOR_ENEMY);
  UNIT_MATS['WIDOW_MINE_F'] = makeDirTextures(drawWidowMine, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['WIDOW_MINE_E'] = makeDirTextures(drawWidowMine, TEAM_COLOR_ENEMY);
  UNIT_MATS['SIEGE_TANK_F'] = makeDirTextures(drawSiegeTank, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['SIEGE_TANK_E'] = makeDirTextures(drawSiegeTank, TEAM_COLOR_ENEMY);
  UNIT_MATS['THOR_F']     = makeDirTextures(drawThor,     TEAM_COLOR_FRIENDLY);
  UNIT_MATS['THOR_E']     = makeDirTextures(drawThor,     TEAM_COLOR_ENEMY);
  UNIT_MATS['VIKING_F']   = makeDirTextures(drawViking,   TEAM_COLOR_FRIENDLY);
  UNIT_MATS['VIKING_E']   = makeDirTextures(drawViking,   TEAM_COLOR_ENEMY);
  UNIT_MATS['RAVEN_F']    = makeDirTextures(drawRaven,    TEAM_COLOR_FRIENDLY);
  UNIT_MATS['RAVEN_E']    = makeDirTextures(drawRaven,    TEAM_COLOR_ENEMY);
  UNIT_MATS['BANSHEE_F']    = makeDirTextures(drawBanshee,   TEAM_COLOR_FRIENDLY);
  UNIT_MATS['BANSHEE_E']    = makeDirTextures(drawBanshee,   TEAM_COLOR_ENEMY);
  UNIT_MATS['LIBERATOR_F']  = makeDirTextures(drawLiberator, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['LIBERATOR_E']  = makeDirTextures(drawLiberator, TEAM_COLOR_ENEMY);
  UNIT_MATS['BATTLECRUISER_F'] = makeDirTextures(drawBattlecruiser, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['BATTLECRUISER_E'] = makeDirTextures(drawBattlecruiser, TEAM_COLOR_ENEMY);
  UNIT_MATS['SENTRY_F'] = makeDirTextures(drawSentry, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['SENTRY_E'] = makeDirTextures(drawSentry, TEAM_COLOR_ENEMY);
  UNIT_MATS['ADEPT_F'] = makeDirTextures(drawAdept, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['ADEPT_E'] = makeDirTextures(drawAdept, TEAM_COLOR_ENEMY);
  UNIT_MATS['DARK_TEMPLAR_F'] = makeDirTextures(drawDarkTemplar, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['DARK_TEMPLAR_E'] = makeDirTextures(drawDarkTemplar, TEAM_COLOR_ENEMY);
  UNIT_MATS['HIGH_TEMPLAR_F'] = makeDirTextures(drawHighTemplar, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['HIGH_TEMPLAR_E'] = makeDirTextures(drawHighTemplar, TEAM_COLOR_ENEMY);
  UNIT_MATS['DISRUPTOR_F'] = makeDirTextures(drawDisruptor, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['DISRUPTOR_E'] = makeDirTextures(drawDisruptor, TEAM_COLOR_ENEMY);
  UNIT_MATS['IMMORTAL_F'] = makeDirTextures(drawImmortal, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['IMMORTAL_E'] = makeDirTextures(drawImmortal, TEAM_COLOR_ENEMY);
  UNIT_MATS['ARCHON_F'] = makeDirTextures(drawArchon, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['ARCHON_E'] = makeDirTextures(drawArchon, TEAM_COLOR_ENEMY);
  UNIT_MATS['COLOSSUS_F'] = makeDirTextures(drawColossus, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['COLOSSUS_E'] = makeDirTextures(drawColossus, TEAM_COLOR_ENEMY);
  UNIT_MATS['OBSERVER_F'] = makeDirTextures(drawObserver, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['OBSERVER_E'] = makeDirTextures(drawObserver, TEAM_COLOR_ENEMY);
  UNIT_MATS['VOID_RAY_F'] = makeDirTextures(drawVoidRay, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['VOID_RAY_E'] = makeDirTextures(drawVoidRay, TEAM_COLOR_ENEMY);
  UNIT_MATS['CARRIER_F']  = makeDirTextures(drawCarrier, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['CARRIER_E']  = makeDirTextures(drawCarrier, TEAM_COLOR_ENEMY);
  UNIT_MATS['ZERGLING_F']  = makeDirTextures(drawZergling,  TEAM_COLOR_FRIENDLY);
  UNIT_MATS['ZERGLING_E']  = makeDirTextures(drawZergling,  TEAM_COLOR_ENEMY);
  UNIT_MATS['RAVAGER_F']   = makeDirTextures(drawRavager,   TEAM_COLOR_FRIENDLY);
  UNIT_MATS['RAVAGER_E']   = makeDirTextures(drawRavager,   TEAM_COLOR_ENEMY);
  UNIT_MATS['INFESTOR_F']  = makeDirTextures(drawInfestor,  TEAM_COLOR_FRIENDLY);
  UNIT_MATS['INFESTOR_E']  = makeDirTextures(drawInfestor,  TEAM_COLOR_ENEMY);
  UNIT_MATS['LURKER_F']    = makeDirTextures(drawLurker,    TEAM_COLOR_FRIENDLY);
  UNIT_MATS['LURKER_E']    = makeDirTextures(drawLurker,    TEAM_COLOR_ENEMY);
  UNIT_MATS['SWARM_HOST_F'] = makeDirTextures(drawSwarmHost, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['SWARM_HOST_E'] = makeDirTextures(drawSwarmHost, TEAM_COLOR_ENEMY);
  UNIT_MATS['QUEEN_F'] = makeDirTextures(drawQueen, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['QUEEN_E'] = makeDirTextures(drawQueen, TEAM_COLOR_ENEMY);
  UNIT_MATS['ULTRALISK_F'] = makeDirTextures(drawUltralisk, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['ULTRALISK_E'] = makeDirTextures(drawUltralisk, TEAM_COLOR_ENEMY);
  UNIT_MATS['CORRUPTOR_F'] = makeDirTextures(drawCorruptor, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['CORRUPTOR_E'] = makeDirTextures(drawCorruptor, TEAM_COLOR_ENEMY);
  UNIT_MATS['ROACH_F']      = makeDirTextures(drawRoach,      TEAM_COLOR_FRIENDLY);
  UNIT_MATS['ROACH_E']      = makeDirTextures(drawRoach,      TEAM_COLOR_ENEMY);
  UNIT_MATS['HYDRALISK_F']  = makeDirTextures(drawHydralisk,  TEAM_COLOR_FRIENDLY);
  UNIT_MATS['HYDRALISK_E']  = makeDirTextures(drawHydralisk,  TEAM_COLOR_ENEMY);
  UNIT_MATS['MUTALISK_F']   = makeDirTextures(drawMutalisk,   TEAM_COLOR_FRIENDLY);
  UNIT_MATS['MUTALISK_E']   = makeDirTextures(drawMutalisk,   TEAM_COLOR_ENEMY);
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
