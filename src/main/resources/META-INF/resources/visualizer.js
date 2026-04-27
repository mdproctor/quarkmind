// visualizer.js — QuarkMind 3D visualizer (Three.js r128)

const TILE = 0.7;
const CREEP_RADIUS = 10; // approximation: tiles within this radius of a creep-producing building
const CREEP_BUILDINGS = new Set(['HATCHERY', 'LAIR', 'HIVE']);
const TEAM_COLOR_FRIENDLY = '#4488ff';
const TEAM_COLOR_ENEMY    = '#ff4422';
const FLYING_UNITS = new Set([
  'MEDIVAC', 'MUTALISK',
  'VIKING', 'RAVEN', 'BANSHEE', 'LIBERATOR', 'LIBERATOR_AG', 'BATTLECRUISER',
  'OBSERVER', 'VOID_RAY', 'CARRIER',
  'BROOD_LORD', 'CORRUPTOR', 'VIPER',
  'PHOENIX', 'ORACLE', 'TEMPEST', 'MOTHERSHIP',
  'WARP_PRISM', 'WARP_PRISM_PHASING', 'INTERCEPTOR',
  'OVERLORD', 'OVERSEER', 'LOCUST'
]);
const RECONNECT_MS = 2000;

let GRID_W = 64, GRID_H = 64;
let HALF_W, HALF_H;
// Scales marker geometry (minerals, geysers, creep) with the map size so they
// remain visible on large real SC2 maps (160×208) vs the 64-tile mock map.
let MARKER_SCALE = 1;
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
const buildingMeshes      = new Map();
const enemyBuildingMeshes = new Map();
const geyserMeshes        = new Map();
const mineralMeshes       = new Map();
const creepMeshes         = new Map(); // key: "x:z", value: THREE.Mesh
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
let hasRealTerrain = false;
let cameraMode   = localStorage.getItem('quarkmind.cameraMode') || 'sc2';
let enemyVisible = true;

window.__test = {
  threeReady:    () => !!renderer,
  terrainReady:  () => terrainLoaded,
  wsConnected:   () => wsConnected,
  hudText:       () => document.getElementById('hud')?.textContent ?? '',
  unitCount:     () => unitSprites.size,
  enemyCount:    () => enemySprites.size,
  buildingCount:      () => buildingMeshes.size,
  enemyBuildingCount: () => enemyBuildingMeshes.size,
  stagingCount:       () => stagingSprites.size,
  geyserCount:        () => geyserMeshes.size,
  mineralCount:       () => mineralMeshes.size,
  creepTileCount:     () => creepMeshes.size,
  markerScale:        () => MARKER_SCALE,
  // Returns true if any mesh in the given internal map projects within the NDC [-1,1] cube.
  // Used by Playwright tests to verify elements are actually visible to the camera.
  _anyOnScreen: map => {
    if (!camera) return false;
    for (const mesh of map.values()) {
      const v = mesh.position.clone().project(camera);
      if (Math.abs(v.x) <= 1 && Math.abs(v.y) <= 1 && v.z < 1) return true;
    }
    return false;
  },
  anyMineralOnScreen:     () => window.__test._anyOnScreen(mineralMeshes),
  anyGeyserOnScreen:      () => window.__test._anyOnScreen(geyserMeshes),
  anyCreepOnScreen:       () => window.__test._anyOnScreen(creepMeshes),
  anyEnemyBuildingOnScreen: () => window.__test._anyOnScreen(enemyBuildingMeshes),
  hasRealTerrain: () => terrainLoaded && hasRealTerrain,
  fogOpacity:    (x, z) => {
    const p = fogPlanes.get(`${x},${z}`);
    return p ? (p.visible ? p.material.opacity : 0) : -1;
  },
  fogVisible:    (x, z) => fogPlanes.get(`${x},${z}`)?.visible ?? false,
  spriteCount: prefix => {
    if (prefix === 'unit')          return unitSprites.size;
    if (prefix === 'enemy')         return enemySprites.size;
    if (prefix === 'building')      return buildingMeshes.size;
    if (prefix === 'enemyBuilding') return enemyBuildingMeshes.size;
    if (prefix === 'geyser')        return geyserMeshes.size;
    if (prefix === 'mineral')       return mineralMeshes.size;
    if (prefix === 'staging')       return stagingSprites.size;
    return 0;
  },
  sprite: key => {
    // key format: "unit:tag", "enemy:tag", "building:tag", "geyser:tag", "staging:tag"
    const [prefix, tag] = key.split(':');
    let obj = null;
    if (prefix === 'unit')     obj = unitSprites.get(tag);
    if (prefix === 'enemy')    obj = enemySprites.get(tag);
    if (prefix === 'building')      obj = buildingMeshes.get(tag);
    if (prefix === 'enemyBuilding') obj = enemyBuildingMeshes.get(tag);
    if (prefix === 'geyser')        obj = geyserMeshes.get(tag);
    if (prefix === 'mineral')       obj = mineralMeshes.get(tag);
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
  worldToScreen: (wx, wz, wy = 0) => {
    if (!camera || !renderer) return { x: 0, y: 0 };
    const v = new THREE.Vector3(wx, wy, wz).project(camera);
    const sz = renderer.getSize(new THREE.Vector2());
    return { x: Math.round((v.x+1)/2*sz.width), y: Math.round((-v.y+1)/2*sz.height) };
  },
  // Projects a sprite's actual Three.js world position through the camera — more
  // reliable than worldToScreen() because it uses the sprite's true rendered position.
  unitScreenPos: tag => {
    const sp = unitSprites.get(tag) ?? enemySprites.get(tag);
    if (!sp || !camera || !renderer) return { x: -1, y: -1 };
    const v = sp.position.clone().project(camera);
    const sz = renderer.getSize(new THREE.Vector2());
    return { x: Math.round((v.x+1)/2*sz.width), y: Math.round((-v.y+1)/2*sz.height) };
  },
  // Fires the real Three.js raycaster at the sprite's NDC position, opens the
  // inspect panel, and awaits the full fetch+DOM pipeline before resolving.
  // page.evaluate("async () => window.__test.clickUnit(tag)") will await this,
  // so the panel is guaranteed visible when page.evaluate() returns.
  clickUnit: async (tag, isEnemy = false) => {
    const sp = isEnemy ? enemySprites.get(tag) : unitSprites.get(tag);
    if (!sp || !camera) return false;
    const ndc = sp.position.clone().project(camera);
    ndcMouse.x = ndc.x;
    ndcMouse.y = ndc.y;
    raycaster.setFromCamera(ndcMouse, camera);
    const allSprites = [...unitSprites.values(), ...enemySprites.values()];
    const hits = raycaster.intersectObjects(allSprites);
    if (hits.length === 0) return false;
    const obj = hits[0].object;
    await showUnitPanelAsync(obj.userData.unitTag, obj.userData.isEnemy);
    return true;
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
    if (typeof drawSiegeTankSieged !== 'undefined') lookup.drawSiegeTankSieged = drawSiegeTankSieged;
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
    if (typeof drawViper !== 'undefined') lookup.drawViper = drawViper;
    if (typeof drawBroodLord !== 'undefined') lookup.drawBroodLord = drawBroodLord;
    if (typeof drawSCV !== 'undefined') lookup.drawSCV = drawSCV;
    if (typeof drawReaper !== 'undefined') lookup.drawReaper = drawReaper;
    if (typeof drawHellion !== 'undefined') lookup.drawHellion = drawHellion;
    if (typeof drawHellbat !== 'undefined') lookup.drawHellbat = drawHellbat;
    if (typeof drawMULE !== 'undefined') lookup.drawMULE = drawMULE;
    if (typeof drawVikingAssault !== 'undefined') lookup.drawVikingAssault = drawVikingAssault;
    if (typeof drawLiberatorAG !== 'undefined') lookup.drawLiberatorAG = drawLiberatorAG;
    if (typeof drawPhoenix !== 'undefined') lookup.drawPhoenix = drawPhoenix;
    if (typeof drawOracle !== 'undefined') lookup.drawOracle = drawOracle;
    if (typeof drawTempest !== 'undefined') lookup.drawTempest = drawTempest;
    if (typeof drawMothership !== 'undefined') lookup.drawMothership = drawMothership;
    if (typeof drawWarpPrism !== 'undefined') lookup.drawWarpPrism = drawWarpPrism;
    if (typeof drawWarpPrismPhasing !== 'undefined') lookup.drawWarpPrismPhasing = drawWarpPrismPhasing;
    if (typeof drawInterceptor !== 'undefined') lookup.drawInterceptor = drawInterceptor;
    if (typeof drawAdeptPhaseShift !== 'undefined') lookup.drawAdeptPhaseShift = drawAdeptPhaseShift;
    if (typeof drawDrone !== 'undefined') lookup.drawDrone = drawDrone;
    if (typeof drawOverlord !== 'undefined') lookup.drawOverlord = drawOverlord;
    if (typeof drawOverseer !== 'undefined') lookup.drawOverseer = drawOverseer;
    if (typeof drawBaneling !== 'undefined') lookup.drawBaneling = drawBaneling;
    if (typeof drawLocust !== 'undefined') lookup.drawLocust = drawLocust;
    if (typeof drawBroodling !== 'undefined') lookup.drawBroodling = drawBroodling;
    if (typeof drawInfestedTerran !== 'undefined') lookup.drawInfestedTerran = drawInfestedTerran;
    if (typeof drawChangeling !== 'undefined') lookup.drawChangeling = drawChangeling;
    if (typeof drawAutoTurret !== 'undefined') lookup.drawAutoTurret = drawAutoTurret;
    if (typeof drawNexus             !== 'undefined') lookup.drawNexus             = drawNexus;
    if (typeof drawPylon             !== 'undefined') lookup.drawPylon             = drawPylon;
    if (typeof drawGateway           !== 'undefined') lookup.drawGateway           = drawGateway;
    if (typeof drawCyberneticsCore   !== 'undefined') lookup.drawCyberneticsCore   = drawCyberneticsCore;
    if (typeof drawAssimilator       !== 'undefined') lookup.drawAssimilator       = drawAssimilator;
    if (typeof drawRoboticsFacility  !== 'undefined') lookup.drawRoboticsFacility  = drawRoboticsFacility;
    if (typeof drawStargate          !== 'undefined') lookup.drawStargate          = drawStargate;
    if (typeof drawForge             !== 'undefined') lookup.drawForge             = drawForge;
    if (typeof drawTwilightCouncil   !== 'undefined') lookup.drawTwilightCouncil   = drawTwilightCouncil;
    if (typeof drawUnknownBuilding   !== 'undefined') lookup.drawUnknownBuilding   = drawUnknownBuilding;
    if (typeof drawPhotonCannon      !== 'undefined') lookup.drawPhotonCannon      = drawPhotonCannon;
    if (typeof drawShieldBattery     !== 'undefined') lookup.drawShieldBattery     = drawShieldBattery;
    if (typeof drawDarkShrine        !== 'undefined') lookup.drawDarkShrine        = drawDarkShrine;
    if (typeof drawTemplarArchives   !== 'undefined') lookup.drawTemplarArchives   = drawTemplarArchives;
    if (typeof drawFleetBeacon       !== 'undefined') lookup.drawFleetBeacon       = drawFleetBeacon;
    if (typeof drawRoboticsBay       !== 'undefined') lookup.drawRoboticsBay       = drawRoboticsBay;
    if (typeof drawCommandCenter     !== 'undefined') lookup.drawCommandCenter     = drawCommandCenter;
    if (typeof drawOrbitalCommand    !== 'undefined') lookup.drawOrbitalCommand    = drawOrbitalCommand;
    if (typeof drawPlanetaryFortress !== 'undefined') lookup.drawPlanetaryFortress = drawPlanetaryFortress;
    if (typeof drawSupplyDepot       !== 'undefined') lookup.drawSupplyDepot       = drawSupplyDepot;
    if (typeof drawBarracks          !== 'undefined') lookup.drawBarracks          = drawBarracks;
    if (typeof drawEngineeringBay    !== 'undefined') lookup.drawEngineeringBay    = drawEngineeringBay;
    if (typeof drawArmory            !== 'undefined') lookup.drawArmory            = drawArmory;
    if (typeof drawMissileTurret     !== 'undefined') lookup.drawMissileTurret     = drawMissileTurret;
    if (typeof drawBunker            !== 'undefined') lookup.drawBunker            = drawBunker;
    if (typeof drawSensorTower       !== 'undefined') lookup.drawSensorTower       = drawSensorTower;
    if (typeof drawGhostAcademy      !== 'undefined') lookup.drawGhostAcademy      = drawGhostAcademy;
    if (typeof drawFactory           !== 'undefined') lookup.drawFactory           = drawFactory;
    if (typeof drawStarport          !== 'undefined') lookup.drawStarport          = drawStarport;
    if (typeof drawFusionCore        !== 'undefined') lookup.drawFusionCore        = drawFusionCore;
    if (typeof drawRefinery          !== 'undefined') lookup.drawRefinery          = drawRefinery;
    if (typeof drawHatchery          !== 'undefined') lookup.drawHatchery          = drawHatchery;
    if (typeof drawLair              !== 'undefined') lookup.drawLair              = drawLair;
    if (typeof drawHive              !== 'undefined') lookup.drawHive              = drawHive;
    if (typeof drawSpawningPool      !== 'undefined') lookup.drawSpawningPool      = drawSpawningPool;
    if (typeof drawEvolutionChamber  !== 'undefined') lookup.drawEvolutionChamber  = drawEvolutionChamber;
    if (typeof drawRoachWarren       !== 'undefined') lookup.drawRoachWarren       = drawRoachWarren;
    if (typeof drawBanelingNest      !== 'undefined') lookup.drawBanelingNest      = drawBanelingNest;
    if (typeof drawSpineCrawler      !== 'undefined') lookup.drawSpineCrawler      = drawSpineCrawler;
    if (typeof drawSporeCrawler      !== 'undefined') lookup.drawSporeCrawler      = drawSporeCrawler;
    if (typeof drawHydraliskDen      !== 'undefined') lookup.drawHydraliskDen      = drawHydraliskDen;
    if (typeof drawLurkerDen         !== 'undefined') lookup.drawLurkerDen         = drawLurkerDen;
    if (typeof drawInfestationPit    !== 'undefined') lookup.drawInfestationPit    = drawInfestationPit;
    if (typeof drawSpire             !== 'undefined') lookup.drawSpire             = drawSpire;
    if (typeof drawGreaterSpire      !== 'undefined') lookup.drawGreaterSpire      = drawGreaterSpire;
    if (typeof drawNydusNetwork      !== 'undefined') lookup.drawNydusNetwork      = drawNydusNetwork;
    if (typeof drawNydusCanal        !== 'undefined') lookup.drawNydusCanal        = drawNydusCanal;
    if (typeof drawUltraliskCavern   !== 'undefined') lookup.drawUltraliskCavern   = drawUltraliskCavern;
    if (typeof drawExtractor         !== 'undefined') lookup.drawExtractor         = drawExtractor;
    const fn = lookup[name];
    if (!fn) return -1;
    const c = document.createElement('canvas');
    c.width = c.height = 128;
    const ctx2d = c.getContext('2d');
    fn(ctx2d, 128, dir, teamColor);
    return ctx2d.getImageData(64, 64, 1, 1).data[3]; // alpha at centre
  },

  panelVisible:       () => document.getElementById('unit-panel')?.classList.contains('visible') ?? false,
  cameraMode:         () => cameraMode,
  enemyLayerVisible:  () => enemyVisible,
};

// ── Unit inspect panel ──────────────────────────────────────────────────────
const raycaster = new THREE.Raycaster();
const ndcMouse  = new THREE.Vector2();

function setupInspectPanel() {
  const panel = document.createElement('div');
  panel.id = 'unit-panel';
  panel.innerHTML = `
    <canvas id="up-portrait" width="64" height="64"></canvas>
    <div id="up-info">
      <div id="up-name"></div>
      <div id="up-team"></div>
      <div class="up-row"><label>HP</label>
        <div class="up-bar"><div id="up-hp" class="up-fill"></div></div>
        <span id="up-hp-txt"></span></div>
      <div class="up-row sh-row"><label>SH</label>
        <div class="up-bar"><div id="up-sh" class="up-fill" style="background:#4488ff"></div></div>
        <span id="up-sh-txt"></span></div>
    </div>
  `;
  document.body.appendChild(panel);

  const css = document.createElement('style');
  css.textContent = `
    #unit-panel {
      position:fixed; bottom:56px; right:12px; width:234px;
      background:rgba(0,0,0,0.88); color:#fff; border:1px solid #444;
      border-radius:6px; padding:10px; display:flex; gap:10px;
      transform:translateX(260px); transition:transform 150ms ease;
      z-index:150; font-family:monospace; font-size:12px;
    }
    #unit-panel.visible { transform:translateX(0); }
    #up-portrait { border-radius:3px; flex-shrink:0; }
    #up-info { flex:1; min-width:0; }
    #up-name { font-weight:bold; font-size:13px; margin-bottom:3px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
    #up-team { color:#aaa; margin-bottom:6px; font-size:11px; }
    .up-row { display:flex; align-items:center; gap:5px; margin-bottom:3px; }
    .up-row label { width:16px; color:#888; font-size:10px; flex-shrink:0; }
    .up-bar { flex:1; height:7px; background:#333; border-radius:3px; overflow:hidden; }
    .up-fill { height:100%; background:#44cc44; border-radius:3px; transition:width 200ms; }
    .sh-row { display:none; }
  `;
  document.head.appendChild(css);

  // Raycasting on click (only if not a drag)
  let mouseDownX = 0, mouseDownY = 0;
  renderer.domElement.addEventListener('mousedown', e => {
    mouseDownX = e.clientX; mouseDownY = e.clientY;
  }, true); // capture phase so we see it before the camera drag handler

  renderer.domElement.addEventListener('mouseup', e => {
    const dx = e.clientX - mouseDownX, dy = e.clientY - mouseDownY;
    if (dx*dx + dy*dy > 25) return; // was a drag

    ndcMouse.x =  (e.clientX / window.innerWidth)  * 2 - 1;
    ndcMouse.y = -(e.clientY / window.innerHeight) * 2 + 1;
    raycaster.setFromCamera(ndcMouse, camera);

    const allSprites = [...unitSprites.values(), ...enemySprites.values()];
    const hits = raycaster.intersectObjects(allSprites);
    if (hits.length > 0) {
      const obj = hits[0].object;
      showUnitPanel(obj.userData.unitTag, obj.userData.isEnemy);
    } else {
      hideUnitPanel();
    }
  });

  window.addEventListener('keydown', e => {
    if (e.key === 'Escape') hideUnitPanel();
  });
}

function showUnitPanel(tag, isEnemy) {
  fetch(`/qa/unit/${encodeURIComponent(tag)}`)
    .then(r => r.ok ? r.json() : null)
    .then(data => {
      if (!data) return;
      _populateUnitPanel(data, isEnemy);
      document.getElementById('unit-panel').classList.add('visible');
    });
}

async function showUnitPanelAsync(tag, isEnemy) {
  const r    = await fetch(`/qa/unit/${encodeURIComponent(tag)}`);
  const data = r.ok ? await r.json() : null;
  if (!data) return;
  _populateUnitPanel(data, isEnemy);
  document.getElementById('unit-panel').classList.add('visible');
}

function _populateUnitPanel(data, isEnemy) {
  document.getElementById('up-name').textContent = data.type.replace(/_/g, ' ');
  document.getElementById('up-team').textContent = isEnemy ? '⚔ Enemy' : '🛡 Friendly';
  const hpPct = data.maxHealth > 0 ? (data.health / data.maxHealth * 100) : 0;
  const hpEl  = document.getElementById('up-hp');
  hpEl.style.width      = hpPct + '%';
  hpEl.style.background = hpPct > 50 ? '#44cc44' : hpPct > 25 ? '#cccc44' : '#cc4444';
  document.getElementById('up-hp-txt').textContent = `${data.health}/${data.maxHealth}`;
  const shRow = document.querySelector('.sh-row');
  if (data.maxShields > 0) {
    shRow.style.display = 'flex';
    const shPct = data.shields / data.maxShields * 100;
    document.getElementById('up-sh').style.width = shPct + '%';
    document.getElementById('up-sh-txt').textContent = `${data.shields}/${data.maxShields}`;
  } else {
    shRow.style.display = 'none';
  }
  const pCanvas = document.getElementById('up-portrait');
  const pCtx    = pCanvas.getContext('2d');
  pCtx.clearRect(0, 0, 64, 64);
  const tColor = isEnemy ? '#ff4422' : '#4488ff';
  const fnName = 'draw' + data.type.split('_').map(w => w[0] + w.slice(1).toLowerCase()).join('');
  if (typeof window[fnName] === 'function') window[fnName](pCtx, 32, 0, tColor);
}

function hideUnitPanel() {
  document.getElementById('unit-panel')?.classList.remove('visible');
}

function setupCameraToggle() {
  const wrap = document.createElement('div');
  wrap.id = 'cam-toggle';
  wrap.innerHTML =
    `<button id="cam-sc2">🎮 SC2</button>` +
    `<button id="cam-3d">🔭 3D</button>`;
  document.body.appendChild(wrap);

  const css = document.createElement('style');
  css.textContent = `
    #cam-toggle {
      position:fixed; top:12px; left:12px; z-index:200;
      display:flex; gap:4px;
    }
    #cam-toggle button {
      background:rgba(0,0,0,0.65); color:#ccc;
      border:1px solid #555; border-radius:4px;
      padding:4px 9px; cursor:pointer; font-size:12px;
    }
    #cam-toggle button:hover { background:rgba(60,60,60,0.9); }
    #cam-toggle .cam-active { background:#1a6fd4; border-color:#3a8fee; color:#fff; }
  `;
  document.head.appendChild(css);

  function refresh() {
    document.getElementById('cam-sc2').classList.toggle('cam-active', cameraMode === 'sc2');
    document.getElementById('cam-3d').classList.toggle('cam-active', cameraMode === '3d');
    if (cameraMode === 'sc2') tPhi = Math.PI / 3.5; // lock pitch for SC2 feel
  }
  refresh();

  document.getElementById('cam-sc2').onclick = () => {
    cameraMode = 'sc2';
    localStorage.setItem('quarkmind.cameraMode', 'sc2');
    refresh();
  };
  document.getElementById('cam-3d').onclick = () => {
    cameraMode = '3d';
    localStorage.setItem('quarkmind.cameraMode', '3d');
    refresh();
  };
}

function toggleEnemyLayer() {
  enemyVisible = !enemyVisible;
  enemySprites.forEach(sp => { sp.visible = enemyVisible; });
  enemy3dMeshes.forEach(m  => { m.visible  = enemyVisible; });
  enemyBuildingMeshes.forEach(m => { m.visible = enemyVisible; });
  stagingSprites.forEach(sp => { sp.visible = enemyVisible; });
  stagingMeshes.forEach(m   => { m.visible  = enemyVisible; });
  const btn = document.getElementById('btn-enemy-toggle');
  if (btn) btn.style.opacity = enemyVisible ? '1' : '0.45';
}

function setupEnemyToggle() {
  const btn = document.createElement('button');
  btn.id = 'btn-enemy-toggle';
  btn.textContent = '👁 Enemy';
  btn.title = 'Show/hide enemy units and buildings';
  const css = document.createElement('style');
  css.textContent = `
    #btn-enemy-toggle {
      position:fixed; top:12px; left:210px; z-index:200;
      background:rgba(0,0,0,0.65); color:#ccc;
      border:1px solid #555; border-radius:4px;
      padding:4px 9px; cursor:pointer; font-size:12px;
    }
    #btn-enemy-toggle:hover { background:rgba(60,60,60,0.9); }
  `;
  document.head.appendChild(css);
  btn.onclick = toggleEnemyLayer;
  document.body.appendChild(btn);
}

function setupKeyboardControls() {
  const keys = new Set();
  window.addEventListener('keydown', e => {
    // Don't capture keys when user is typing in an input
    if (e.target.tagName === 'INPUT') return;
    keys.add(e.key);
  });
  window.addEventListener('keyup', e => keys.delete(e.key));

  (function panLoop() {
    const speed = camDist * 0.008;
    const fwd = new THREE.Vector3(-Math.sin(camTheta), 0, -Math.cos(camTheta));
    const rgt = new THREE.Vector3( Math.cos(camTheta), 0, -Math.sin(camTheta));

    if (keys.has('w') || keys.has('ArrowUp'))    camTarget.addScaledVector(fwd,  speed);
    if (keys.has('s') || keys.has('ArrowDown'))  camTarget.addScaledVector(fwd, -speed);
    if (keys.has('a') || keys.has('ArrowLeft'))  camTarget.addScaledVector(rgt, -speed);
    if (keys.has('d') || keys.has('ArrowRight')) camTarget.addScaledVector(rgt,  speed);

    requestAnimationFrame(panLoop);
  })();
}

function applyLayoutCss() {
  const layoutCss = document.createElement('style');
  layoutCss.textContent = `
    * { box-sizing: border-box; }
    body { margin: 0; overflow: hidden; background: #000; }
    canvas { display: block; width: 100vw !important; height: 100vh !important; }
    #hud {
      position: fixed; top: 12px; right: 12px; z-index: 200;
      background: rgba(0,0,0,0.65); color: #e0e0e0;
      padding: 6px 12px; border-radius: 4px;
      font-family: monospace; font-size: 13px;
      pointer-events: none;
    }
  `;
  document.head.appendChild(layoutCss);
}

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
  setupCameraToggle();
  setupEnemyToggle();
  setupKeyboardControls();
  applyLayoutCss();
  setupLighting();
  await loadTerrain();
  connectWebSocket();
  initConfigPanel();
  initReplayControls();
  setupInspectPanel();
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
  let drag = false, lastX = 0, lastY = 0, panDrag = false;
  renderer.domElement.addEventListener('mousedown', e => {
    drag = true; lastX = e.clientX; lastY = e.clientY;
    // SC2 mode: left drag pans; 3D mode: left drag orbits, right drag pans
    panDrag = cameraMode === 'sc2' ? e.button === 0 : e.button === 2;
    e.preventDefault();
  });
  renderer.domElement.addEventListener('contextmenu', e => e.preventDefault());
  window.addEventListener('mousemove', e => {
    if (!drag) return;
    const dx = e.clientX - lastX, dy = e.clientY - lastY;
    lastX = e.clientX; lastY = e.clientY;
    if (panDrag) {
      const right = new THREE.Vector3();
      camera.getWorldDirection(right); right.cross(camera.up).normalize();
      const s = camDist * 0.012;
      camTarget.addScaledVector(right, -dx * s * 0.05);
      camTarget.y += dy * s * 0.05;
    } else if (cameraMode === '3d' && !panDrag) {
      tTheta -= dx * 0.012;
      tPhi = Math.max(0.08, Math.min(Math.PI / 2.05, tPhi - dy * 0.012));
    }
    // SC2 mode non-pan drag: no orbit — ignore
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
  let isEmulatedMode = false;

  // Phase 1: replay profile
  try {
    const mapMeta = await fetchJson('/qa/current-map');
    if (mapMeta) {
      GRID_W = mapMeta.mapWidth;
      GRID_H = mapMeta.mapHeight;
      const terrainResp = await fetch(`/qa/terrain?map=${encodeURIComponent(mapMeta.mapName)}`);
      if (terrainResp.ok) {
        const d = await terrainResp.json();
        walls      = d.walls      || [];
        highGround = d.highGround || [];
        ramps      = d.ramps      || [];
        hasRealTerrain = true;
      }
    }
  } catch (_) {}

  // Phase 2: emulated profile
  if (!hasRealTerrain) {
    try {
      const r = await fetch('/qa/emulated/terrain');
      if (r.ok) {
        const d = await r.json();
        GRID_W = d.width; GRID_H = d.height;
        walls      = d.walls      || [];
        highGround = d.highGround || [];
        ramps      = d.ramps      || [];
        hasRealTerrain = true;
        isEmulatedMode = true;
      }
    } catch (_) {}
  }

  HALF_W = (GRID_W * TILE) / 2;
  HALF_H = (GRID_H * TILE) / 2;
  // Markers scale with map: on 64-tile mock MARKER_SCALE=1; on 160×208 real map ≈3
  MARKER_SCALE = Math.max(1, (GRID_W + GRID_H) / 128);

  const wallSet = new Set(walls.map(([x,z])      => `${x},${z}`));
  const highSet = new Set(highGround.map(([x,z]) => `${x},${z}`));
  const rampSet = new Set(ramps.map(([x,z])      => `${x},${z}`));

  if (hasRealTerrain) {
    const groundPlane = new THREE.Mesh(
      new THREE.PlaneGeometry(GRID_W * TILE, GRID_H * TILE),
      mGround
    );
    groundPlane.rotation.x = -Math.PI / 2;
    groundPlane.position.set(0, 0.04, 0);
    scene.add(groundPlane);
  }

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
      else if (hasRealTerrain) { continue; } // LOW — covered by base plane

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
  if (isEmulatedMode) {
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

async function fetchJson(url) {
  try {
    const r = await fetch(url);
    return r.ok ? r.json() : null;
  } catch (_) { return null; }
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

const BUILDING_SCALE = {
  NEXUS:             { w: 2.8, h: 2.8 },
  PYLON:             { w: 1.2, h: 1.8 },
  GATEWAY:           { w: 2.2, h: 1.8 },
  CYBERNETICS_CORE:  { w: 1.8, h: 1.8 },
  ASSIMILATOR:       { w: 1.6, h: 1.4 },
  ROBOTICS_FACILITY: { w: 2.4, h: 1.8 },
  STARGATE:          { w: 2.6, h: 2.2 },
  FORGE:             { w: 2.0, h: 1.8 },
  TWILIGHT_COUNCIL:  { w: 1.8, h: 2.2 },
};

function syncUnits(state) {
  syncBuildings(state.myBuildings       || []);
  syncEnemyBuildings(state.enemyBuildings || []);
  syncCreep(state.enemyBuildings          || []);
  syncGeysers(state.geysers               || []);
  syncMineralPatches(state.mineralPatches || []);
  syncUnitLayer(unitSprites,   unit3dMeshes,  state.myUnits          || [], false);
  syncUnitLayer(enemySprites,  enemy3dMeshes, state.enemyUnits        || [], true);
  syncUnitLayer(stagingSprites, stagingMeshes, state.enemyStagingArea  || [], true);
}

function syncBuildings(buildings) {
  const seen = new Set();
  buildings.forEach(b => {
    seen.add(b.tag);
    if (!buildingMeshes.has(b.tag)) {
      const mat = BUILDING_MATS[b.type] ?? BUILDING_MATS['UNKNOWN'];
      const { w, h } = BUILDING_SCALE[b.type] ?? { w: 1.8, h: 1.8 };
      const sp = new THREE.Sprite(mat);
      sp.scale.set(TILE * w, TILE * h, 1);
      const wp = gw(b.position.x, b.position.y);
      sp.position.set(wp.x, TERRAIN_SURFACE_Y + TILE * h * 0.5, wp.z);
      scene.add(sp);
      buildingMeshes.set(b.tag, sp);
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
        new THREE.BoxGeometry(TILE*0.6*MARKER_SCALE, TILE*0.25*MARKER_SCALE, TILE*0.6*MARKER_SCALE),
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

function syncMineralPatches(patches) {
  const seen = new Set();
  patches.forEach(p => {
    seen.add(p.tag);
    if (!mineralMeshes.has(p.tag)) {
      const mesh = new THREE.Mesh(
        new THREE.BoxGeometry(TILE*0.5*MARKER_SCALE, TILE*0.15*MARKER_SCALE, TILE*0.3*MARKER_SCALE),
        new THREE.MeshLambertMaterial({ color: 0x44aacc, emissive: 0x001133 })
      );
      const wp = gw(p.position.x, p.position.y);
      mesh.position.set(wp.x, TILE * 0.075, wp.z);
      scene.add(mesh);
      mineralMeshes.set(p.tag, mesh);
    }
  });
  mineralMeshes.forEach((m, tag) => {
    if (!seen.has(tag)) { scene.remove(m); mineralMeshes.delete(tag); }
  });
}

function syncEnemyBuildings(buildings) {
  const seen = new Set();
  buildings.forEach(b => {
    seen.add(b.tag);
    if (!enemyBuildingMeshes.has(b.tag)) {
      const mat = BUILDING_MATS[b.type] ?? BUILDING_MATS['UNKNOWN'];
      const { w, h } = BUILDING_SCALE[b.type] ?? { w: 1.8, h: 1.8 };
      const sp = new THREE.Sprite(mat);
      sp.scale.set(TILE * w, TILE * h, 1);
      const wp = gw(b.position.x, b.position.y);
      sp.position.set(wp.x, TERRAIN_SURFACE_Y + TILE * h * 0.5, wp.z);
      // Tint enemy buildings red to distinguish from friendly
      sp.material = mat.clone();
      sp.material.color.setHex(0xff4422);
      sp.visible = enemyVisible;
      scene.add(sp);
      enemyBuildingMeshes.set(b.tag, sp);
    }
  });
  enemyBuildingMeshes.forEach((m, tag) => {
    if (!seen.has(tag)) { scene.remove(m); enemyBuildingMeshes.delete(tag); }
  });
}

function syncCreep(enemyBuildings) {
  const wantedTiles = new Set();
  enemyBuildings.forEach(b => {
    if (!CREEP_BUILDINGS.has(b.type)) return;
    const cx = Math.round(b.position.x), cz = Math.round(b.position.y);
    for (let dx = -CREEP_RADIUS; dx <= CREEP_RADIUS; dx++) {
      for (let dz = -CREEP_RADIUS; dz <= CREEP_RADIUS; dz++) {
        if (dx * dx + dz * dz <= CREEP_RADIUS * CREEP_RADIUS) {
          wantedTiles.add(`${cx + dx}:${cz + dz}`);
        }
      }
    }
  });

  // Add new tiles
  wantedTiles.forEach(key => {
    if (!creepMeshes.has(key)) {
      const [tx, tz] = key.split(':').map(Number);
      const mesh = new THREE.Mesh(
        new THREE.PlaneGeometry(TILE * MARKER_SCALE, TILE * MARKER_SCALE),
        new THREE.MeshBasicMaterial({
          color: 0x4a1a6e, transparent: true, opacity: 0.45,
          depthWrite: false, side: THREE.DoubleSide
        })
      );
      const wp = gw(tx, tz);
      mesh.rotation.x = -Math.PI / 2;
      mesh.position.set(wp.x, 0.02, wp.z); // just above ground, below geysers/minerals
      scene.add(mesh);
      creepMeshes.set(key, mesh);
    }
  });

  // Remove stale tiles
  creepMeshes.forEach((mesh, key) => {
    if (!wantedTiles.has(key)) {
      scene.remove(mesh);
      mesh.geometry.dispose();
      mesh.material.dispose();
      creepMeshes.delete(key);
    }
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
      sp.userData.unitTag  = u.tag;
      sp.userData.unitType = u.type;
      sp.userData.isEnemy  = isEnemy;
      sp.scale.set(TILE * 1.4, TILE * 1.4, 1);
      // Base both sprite and 3D model on TERRAIN_SURFACE_Y so they sit above ground
      // in both mock (TERRAIN_SURFACE_Y=0.08) and emulated (TERRAIN_SURFACE_Y=TILE) profiles.
      const groundY = TERRAIN_SURFACE_Y + TILE * 0.5;
      const flyingY = TERRAIN_SURFACE_Y + TILE * 1.1;
      const unitY   = FLYING_UNITS.has(u.type) ? flyingY : groundY;
      sp.position.set(wp.x, unitY, wp.z);
      if (isEnemy) sp.visible = enemyVisible;
      group2d.add(sp);
      spriteMap.set(u.tag, sp);

      // 3D sphere model — ground center one sphere-radius above terrain surface
      const g = make3dModel(isEnemy ? 0xcc3322 : 0x4488dd, isEnemy ? 0x330000 : 0x112244);
      g.position.set(wp.x, FLYING_UNITS.has(u.type) ? flyingY : TERRAIN_SURFACE_Y + TILE * 0.4, wp.z);
      if (isEnemy) g.visible = enemyVisible;
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

function drawSiegeTankSieged(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawSiegeTankSieged(ctx, S, 1, teamColor); ctx.restore(); return;
  }
  const cx = S / 2, cy = S / 2;

  if (dir === 1) {
    // Side view: full barrel profile extending upper-left at steep angle
    // Splayed tracks (stabiliser feet) — wider than mobile mode
    ctx.fillStyle = '#1a1a1a';
    ctx.fillRect(cx - S * 0.42, cy + S * 0.12, S * 0.84, S * 0.14);
    ctx.fillStyle = '#2a2a2a';
    ctx.fillRect(cx - S * 0.40, cy + S * 0.14, S * 0.80, S * 0.1);
    // Track wheels
    ctx.fillStyle = '#333';
    for (let i = 0; i < 6; i++) {
      ctx.beginPath(); ctx.ellipse(cx - S * 0.35 + i * S * 0.14, cy + S * 0.19, S * 0.03, S * 0.025, 0, 0, Math.PI * 2); ctx.fill();
    }
    // Raised angular hull body
    const bg = ctx.createLinearGradient(cx, cy - S * 0.16, cx, cy + S * 0.12);
    bg.addColorStop(0, '#5a6a4a'); bg.addColorStop(1, '#3a4a32');
    ctx.fillStyle = bg; ctx.fillRect(cx - S * 0.32, cy - S * 0.14, S * 0.64, S * 0.28);
    // Hull top bevel
    ctx.fillStyle = '#6a7a5a';
    ctx.fillRect(cx - S * 0.30, cy - S * 0.16, S * 0.60, S * 0.05);
    // Stabiliser struts (left side, lower hull to ground)
    ctx.strokeStyle = '#2a3a22'; ctx.lineWidth = S * 0.018;
    [[-0.30, -0.05], [-0.20, -0.02]].forEach(([dx, dy]) => {
      ctx.beginPath();
      ctx.moveTo(cx + dx * S, cy + dy * S);
      ctx.lineTo(cx + (dx - S * 0.02), cy + S * 0.12);
      ctx.stroke();
    });
    // Long barrel at ~40° angle, extending upper-left — roughly S*0.55 long
    ctx.save();
    ctx.translate(cx - S * 0.04, cy - S * 0.08);
    ctx.rotate(-Math.PI * 0.22); // ~40° up-left
    ctx.fillStyle = '#2a3a22';
    ctx.fillRect(-S * 0.04, -S * 0.035, S * 0.55, S * 0.07); // barrel shaft
    ctx.fillStyle = '#1a2a12';
    ctx.fillRect(S * 0.47, -S * 0.045, S * 0.08, S * 0.09); // muzzle brake
    ctx.restore();
    // Team-colour targeting sensor at muzzle tip
    const muzzleX = cx - S * 0.04 + Math.cos(-Math.PI * 0.22) * S * 0.51;
    const muzzleY = cy - S * 0.08 + Math.sin(-Math.PI * 0.22) * S * 0.51;
    ctx.fillStyle = hexToRgba(teamColor, 0.9);
    ctx.shadowColor = teamColor; ctx.shadowBlur = 8;
    ctx.beginPath(); ctx.ellipse(muzzleX, muzzleY, S * 0.04, S * 0.035, 0, 0, Math.PI * 2); ctx.fill();
    ctx.shadowBlur = 0;

  } else if (dir === 2) {
    // Back view: exhaust vents visible, barrel leans rearward
    // Splayed tracks
    ctx.fillStyle = '#1a1a1a'; ctx.fillRect(cx - S * 0.38, cy + S * 0.12, S * 0.76, S * 0.14);
    const bg = ctx.createLinearGradient(cx, cy - S * 0.16, cx, cy + S * 0.12);
    bg.addColorStop(0, '#4a5a3a'); bg.addColorStop(1, '#2a3a22');
    ctx.fillStyle = bg; ctx.fillRect(cx - S * 0.34, cy - S * 0.12, S * 0.68, S * 0.26);
    // Hull top
    ctx.fillStyle = '#5a6a4a';
    ctx.fillRect(cx - S * 0.32, cy - S * 0.14, S * 0.64, S * 0.05);
    // Exhaust vents (pairs)
    ctx.fillStyle = '#1a1a1a';
    [[-0.18, 0.18]].forEach(dx => {
      ctx.fillRect(cx + dx * S - S * 0.04, cy - S * 0.10, S * 0.08, S * 0.16);
    });
    ctx.fillStyle = '#0a0a0a';
    [-0.18, 0.18].forEach(dx => {
      for (let i = 0; i < 3; i++) {
        ctx.fillRect(cx + dx * S - S * 0.03, cy - S * 0.09 + i * S * 0.04, S * 0.06, S * 0.02);
      }
    });
    // Barrel foreshortened (pointing away) — ellipse at top-centre
    ctx.fillStyle = '#1a2a12';
    ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.20, S * 0.07, S * 0.05, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = '#2a3a22';
    ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.20, S * 0.055, S * 0.038, 0, 0, Math.PI * 2); ctx.fill();
    // Team-colour sensor
    ctx.fillStyle = hexToRgba(teamColor, 0.9);
    ctx.shadowColor = teamColor; ctx.shadowBlur = 8;
    ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.20, S * 0.03, S * 0.025, 0, 0, Math.PI * 2); ctx.fill();
    ctx.shadowBlur = 0;

  } else {
    // Dir 0 (front): barrel pointing up toward viewer — large foreshortened ellipse
    // Splayed tracks
    ctx.fillStyle = '#1a1a1a'; ctx.fillRect(cx - S * 0.38, cy + S * 0.12, S * 0.76, S * 0.14);
    const bg = ctx.createLinearGradient(cx, cy - S * 0.16, cx, cy + S * 0.12);
    bg.addColorStop(0, '#5a6a4a'); bg.addColorStop(1, '#3a4a32');
    ctx.fillStyle = bg; ctx.fillRect(cx - S * 0.34, cy - S * 0.12, S * 0.68, S * 0.26);
    // Hull top bevel
    ctx.fillStyle = '#6a7a5a';
    ctx.fillRect(cx - S * 0.32, cy - S * 0.14, S * 0.64, S * 0.05);
    // Stabiliser struts visible on both sides
    ctx.strokeStyle = '#2a3a22'; ctx.lineWidth = S * 0.018;
    [[-0.34, 0.34]].forEach(dx => {
      ctx.beginPath(); ctx.moveTo(cx + dx * S, cy - S * 0.02); ctx.lineTo(cx + dx * S, cy + S * 0.12); ctx.stroke();
    });
    // Barrel as large foreshortened ellipse (viewing down the barrel from front)
    ctx.fillStyle = '#1a2a12';
    ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.18, S * 0.10, S * 0.08, 0, 0, Math.PI * 2); ctx.fill();
    // Barrel rim ring
    ctx.strokeStyle = '#2a3a22'; ctx.lineWidth = S * 0.02;
    ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.18, S * 0.10, S * 0.08, 0, 0, Math.PI * 2); ctx.stroke();
    // Inner bore
    ctx.fillStyle = '#0a0a0a';
    ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.18, S * 0.055, S * 0.042, 0, 0, Math.PI * 2); ctx.fill();
    // Team-colour targeting sensor at muzzle
    ctx.fillStyle = hexToRgba(teamColor, 0.9);
    ctx.shadowColor = teamColor; ctx.shadowBlur = 8;
    ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.18, S * 0.03, S * 0.025, 0, 0, Math.PI * 2); ctx.fill();
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

// drawViper — Zerg flying serpent caster unit.
// Dark teal-purple elongated body with sinuous curves and abduct claw.
// Dir-3 mirrors dir-1.
function drawViper(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawViper(ctx, S, 1, teamColor); ctx.restore(); return;
  }

  const cx = S / 2;
  const cy = S / 2;
  const bodyColor = '#0a1a18';
  const scaleColor = '#061210';
  const midColor  = '#0d2420';

  ctx.lineCap  = 'round';
  ctx.lineJoin = 'round';

  if (dir === 0 || dir === 2) {
    // Vertical orientation — head at top (dir 0) or bottom (dir 2)
    const headY = (dir === 0) ? cy - S * 0.22 : cy + S * 0.22;
    const tailY = (dir === 0) ? cy + S * 0.38 : cy - S * 0.38;
    const midSign = (dir === 0) ? 1 : -1;

    // Thick sinuous body path passing through (cx, cy)
    // Control points create an S-curve that centres on (64,64)
    ctx.beginPath();
    ctx.moveTo(cx, headY);
    // Bezier: sweeps through centre pixel (64,64) then tapers to tail tip
    ctx.bezierCurveTo(
      cx + S * 0.18 * midSign, cy - S * 0.10 * midSign,   // cp1
      cx - S * 0.16 * midSign, cy + S * 0.06 * midSign,   // cp2
      cx,                       tailY                       // tail tip
    );
    ctx.lineWidth = S * 0.14;
    ctx.strokeStyle = bodyColor;
    ctx.stroke();

    // Thinner highlight stripe along body centre — also passes through (64,64)
    ctx.beginPath();
    ctx.moveTo(cx, headY + (dir === 0 ? S * 0.04 : -S * 0.04));
    ctx.bezierCurveTo(
      cx + S * 0.10 * midSign, cy - S * 0.06 * midSign,
      cx - S * 0.08 * midSign, cy + S * 0.04 * midSign,
      cx,                       tailY
    );
    ctx.lineWidth = S * 0.05;
    ctx.strokeStyle = midColor;
    ctx.stroke();

    // Scale dots along body
    const scaleDots = (dir === 0)
      ? [[0.06, -0.10], [-0.07, 0.04], [0.05, 0.16]]
      : [[-0.06, 0.10], [0.07, -0.04], [-0.05, -0.16]];
    scaleDots.forEach(([dx, dy]) => {
      ctx.fillStyle = scaleColor;
      ctx.beginPath();
      ctx.ellipse(cx + dx * S, cy + dy * S, S * 0.032, S * 0.022, dx * 1.2, 0, Math.PI * 2);
      ctx.fill();
    });

    // Head ellipse — slightly wider rounded bulge at head end
    ctx.fillStyle = bodyColor;
    ctx.beginPath();
    ctx.ellipse(cx, headY + (dir === 0 ? S * 0.04 : -S * 0.04), S * 0.10, S * 0.08, 0, 0, Math.PI * 2);
    ctx.fill();

    // Abduct claws flanking head — two curved hook strokes in team colour
    const clawY = headY + (dir === 0 ? -S * 0.04 : S * 0.04);
    ctx.lineWidth = S * 0.022;
    ctx.shadowColor = teamColor; ctx.shadowBlur = 12;
    ctx.strokeStyle = hexToRgba(teamColor, 0.9);
    // Left claw
    ctx.beginPath();
    ctx.moveTo(cx - S * 0.07, clawY);
    ctx.quadraticCurveTo(cx - S * 0.12, clawY - S * 0.06 * midSign, cx - S * 0.08, clawY - S * 0.13 * midSign);
    ctx.stroke();
    // Right claw
    ctx.beginPath();
    ctx.moveTo(cx + S * 0.07, clawY);
    ctx.quadraticCurveTo(cx + S * 0.12, clawY - S * 0.06 * midSign, cx + S * 0.08, clawY - S * 0.13 * midSign);
    ctx.stroke();
    ctx.shadowBlur = 0;

  } else {
    // dir === 1 — side view: body sweeps left-to-right, crossing (64,64)
    const headX = S * 0.10;
    const tailX = S * 0.92;
    const headY_side = cy;

    // Thick sinuous body crossing canvas centre x=64
    ctx.beginPath();
    ctx.moveTo(headX, headY_side);
    ctx.bezierCurveTo(
      S * 0.30, cy - S * 0.22,   // cp1 — curves up before centre
      S * 0.58, cy + S * 0.20,   // cp2 — curves down after centre
      tailX,    cy               // tail tip at right
    );
    ctx.lineWidth = S * 0.13;
    ctx.strokeStyle = bodyColor;
    ctx.stroke();

    // Thinner highlight stripe
    ctx.beginPath();
    ctx.moveTo(headX + S * 0.02, headY_side);
    ctx.bezierCurveTo(
      S * 0.30, cy - S * 0.13,
      S * 0.58, cy + S * 0.12,
      tailX - S * 0.04, cy
    );
    ctx.lineWidth = S * 0.05;
    ctx.strokeStyle = midColor;
    ctx.stroke();

    // Scale dots along body
    [[0.28, -0.12], [0.50, 0.08], [0.68, -0.04]].forEach(([dx, dy]) => {
      ctx.fillStyle = scaleColor;
      ctx.beginPath();
      ctx.ellipse(dx * S, cy + dy * S, S * 0.030, S * 0.020, 0.5, 0, Math.PI * 2);
      ctx.fill();
    });

    // Head ellipse at left end
    ctx.fillStyle = bodyColor;
    ctx.beginPath();
    ctx.ellipse(headX + S * 0.04, headY_side, S * 0.09, S * 0.075, 0, 0, Math.PI * 2);
    ctx.fill();

    // Abduct claws at head (left) — two hook strokes above/below head
    ctx.lineWidth = S * 0.022;
    ctx.shadowColor = teamColor; ctx.shadowBlur = 12;
    ctx.strokeStyle = hexToRgba(teamColor, 0.9);
    // Upper claw
    ctx.beginPath();
    ctx.moveTo(headX + S * 0.02, cy - S * 0.05);
    ctx.quadraticCurveTo(headX - S * 0.04, cy - S * 0.12, headX + S * 0.01, cy - S * 0.18);
    ctx.stroke();
    // Lower claw
    ctx.beginPath();
    ctx.moveTo(headX + S * 0.02, cy + S * 0.05);
    ctx.quadraticCurveTo(headX - S * 0.04, cy + S * 0.12, headX + S * 0.01, cy + S * 0.18);
    ctx.stroke();
    ctx.shadowBlur = 0;
  }
}

// drawBroodLord — Zerg heavy flying siege unit.
// Dark charcoal-purple body with large swept-back wings and broodling sacs.
// Dir-3 mirrors dir-1.
function drawBroodLord(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawBroodLord(ctx, S, 1, teamColor); ctx.restore(); return;
  }

  const cx = S / 2;
  const cy = S / 2;
  const bodyColor  = '#1a0a0a';
  const wingColor  = '#120606';
  const sacColor   = '#0a1205';

  ctx.lineCap  = 'round';
  ctx.lineJoin = 'round';

  if (dir === 0 || dir === 2) {
    // Top-down view — wings swept back left and right, body oval at centre
    const wingAngle = (dir === 0) ? 0 : Math.PI;

    // Left wing — large swept-back crescent extending from centre-left
    ctx.fillStyle = wingColor;
    ctx.beginPath();
    ctx.moveTo(cx, cy);
    ctx.bezierCurveTo(
      cx - S * 0.12, cy - S * 0.08,
      cx - S * 0.44, cy - S * 0.18,
      cx - S * 0.44, cy + S * 0.06
    );
    ctx.bezierCurveTo(
      cx - S * 0.38, cy + S * 0.22,
      cx - S * 0.18, cy + S * 0.16,
      cx, cy + S * 0.08
    );
    ctx.closePath();
    ctx.fill();

    // Right wing — mirror of left wing
    ctx.beginPath();
    ctx.moveTo(cx, cy);
    ctx.bezierCurveTo(
      cx + S * 0.12, cy - S * 0.08,
      cx + S * 0.44, cy - S * 0.18,
      cx + S * 0.44, cy + S * 0.06
    );
    ctx.bezierCurveTo(
      cx + S * 0.38, cy + S * 0.22,
      cx + S * 0.18, cy + S * 0.16,
      cx, cy + S * 0.08
    );
    ctx.closePath();
    ctx.fill();

    // Central body oval — covers canvas centre
    ctx.fillStyle = bodyColor;
    ctx.beginPath();
    ctx.ellipse(cx, cy, S * 0.18, S * 0.26, 0, 0, Math.PI * 2);
    ctx.fill();

    // Broodling sacs hanging below body — 3 teardrop ellipses
    const sacOffsets = [[-S * 0.10, S * 0.28], [0, S * 0.36], [S * 0.10, S * 0.28]];
    sacOffsets.forEach(([dx, dy]) => {
      const sacY = cy + dy;
      const sacX = cx + dx;
      // Outer sac fill (dark greenish-black)
      ctx.fillStyle = sacColor;
      ctx.beginPath();
      ctx.ellipse(sacX, sacY, S * 0.07, S * 0.09, 0, 0, Math.PI * 2);
      ctx.fill();
      // Team colour glow inside sac
      ctx.fillStyle = hexToRgba(teamColor, 0.7);
      ctx.shadowColor = teamColor;
      ctx.shadowBlur = 10;
      ctx.beginPath();
      ctx.ellipse(sacX, sacY, S * 0.03, S * 0.04, 0, 0, Math.PI * 2);
      ctx.fill();
      ctx.shadowBlur = 0;
    });

    // Body detail — armour ridge line down centre
    ctx.strokeStyle = '#2a0f0f';
    ctx.lineWidth = S * 0.018;
    ctx.beginPath();
    ctx.moveTo(cx, cy - S * 0.22);
    ctx.lineTo(cx, cy + S * 0.22);
    ctx.stroke();

  } else {
    // dir === 1 — side view: wings visible behind body, sacs below, body in foreground

    // Wings behind body — two swept-back triangular fills
    ctx.fillStyle = wingColor;
    // Upper wing
    ctx.beginPath();
    ctx.moveTo(cx - S * 0.08, cy - S * 0.04);
    ctx.bezierCurveTo(
      cx - S * 0.20, cy - S * 0.30,
      cx + S * 0.28, cy - S * 0.36,
      cx + S * 0.42, cy - S * 0.14
    );
    ctx.bezierCurveTo(
      cx + S * 0.28, cy - S * 0.08,
      cx + S * 0.08, cy - S * 0.02,
      cx - S * 0.08, cy - S * 0.04
    );
    ctx.closePath();
    ctx.fill();

    // Lower wing
    ctx.beginPath();
    ctx.moveTo(cx - S * 0.08, cy + S * 0.04);
    ctx.bezierCurveTo(
      cx - S * 0.20, cy + S * 0.22,
      cx + S * 0.28, cy + S * 0.28,
      cx + S * 0.42, cy + S * 0.10
    );
    ctx.bezierCurveTo(
      cx + S * 0.28, cy + S * 0.04,
      cx + S * 0.08, cy + S * 0.02,
      cx - S * 0.08, cy + S * 0.04
    );
    ctx.closePath();
    ctx.fill();

    // Body — rounded oval in foreground covering canvas centre
    ctx.fillStyle = bodyColor;
    ctx.beginPath();
    ctx.ellipse(cx, cy, S * 0.22, S * 0.16, 0, 0, Math.PI * 2);
    ctx.fill();

    // Broodling sacs below body — 2 sacs hanging down
    const sideSacs = [[-S * 0.08, S * 0.24], [S * 0.08, S * 0.26]];
    sideSacs.forEach(([dx, dy]) => {
      ctx.fillStyle = sacColor;
      ctx.beginPath();
      ctx.ellipse(cx + dx, cy + dy, S * 0.06, S * 0.08, 0, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = hexToRgba(teamColor, 0.7);
      ctx.shadowColor = teamColor;
      ctx.shadowBlur = 10;
      ctx.beginPath();
      ctx.ellipse(cx + dx, cy + dy, S * 0.025, S * 0.035, 0, 0, Math.PI * 2);
      ctx.fill();
      ctx.shadowBlur = 0;
    });

    // Body detail — armour line
    ctx.strokeStyle = '#2a0f0f';
    ctx.lineWidth = S * 0.016;
    ctx.beginPath();
    ctx.moveTo(cx - S * 0.18, cy);
    ctx.lineTo(cx + S * 0.18, cy);
    ctx.stroke();
  }
}

function drawSCV(ctx, S, dir, teamColor) {
  const cx = S / 2, cy = S / 2 + 2;

  if (dir === 3) {
    // dir 3 = mirror of dir 1 (left-side view)
    ctx.save();
    ctx.translate(S, 0);
    ctx.scale(-1, 1);
    drawSCV(ctx, S, 1, teamColor);
    ctx.restore();
    return;
  }

  if (dir === 2) {
    // BACK — no tool arm, backpack visible
    // Shadow glow
    const grd = ctx.createRadialGradient(cx, cy, S * 0.04, cx, cy, S * 0.42);
    grd.addColorStop(0, 'rgba(74,74,74,0.35)'); grd.addColorStop(1, 'rgba(0,0,0,0)');
    ctx.fillStyle = grd; ctx.beginPath(); ctx.ellipse(cx, cy, S * 0.42, S * 0.42, 0, 0, Math.PI * 2); ctx.fill();

    // Torso
    ctx.fillStyle = '#4a4a4a';
    const torsoX = cx - S * 0.18, torsoY = cy - S * 0.14, torsoW = S * 0.36, torsoH = S * 0.3;
    ctx.beginPath();
    ctx.roundRect(torsoX, torsoY, torsoW, torsoH, S * 0.04);
    ctx.fill();
    ctx.strokeStyle = '#333'; ctx.lineWidth = S * 0.014;
    ctx.stroke();

    // Helmet dome
    ctx.fillStyle = '#555';
    ctx.beginPath();
    ctx.arc(cx, cy - S * 0.18, S * 0.15, Math.PI, 0, false);
    ctx.closePath();
    ctx.fill();
    ctx.strokeStyle = '#333'; ctx.lineWidth = S * 0.012;
    ctx.stroke();
    // Visor slit (back — narrow back strip)
    ctx.fillStyle = hexToRgba(teamColor, 0.5);
    ctx.fillRect(cx - S * 0.06, cy - S * 0.21, S * 0.12, S * 0.045);

    // Backpack box on back (clearly visible from behind)
    ctx.fillStyle = '#383838';
    ctx.beginPath();
    ctx.roundRect(cx - S * 0.1, cy - S * 0.1, S * 0.2, S * 0.16, S * 0.025);
    ctx.fill();
    ctx.strokeStyle = '#222'; ctx.lineWidth = S * 0.012;
    ctx.stroke();
    // Backpack accent
    ctx.fillStyle = hexToRgba(teamColor, 0.6);
    ctx.fillRect(cx - S * 0.04, cy - S * 0.08, S * 0.08, S * 0.04);

    // Legs
    ctx.fillStyle = '#333';
    ctx.beginPath(); ctx.roundRect(cx - S * 0.14, cy + S * 0.16, S * 0.1, S * 0.14, S * 0.02); ctx.fill();
    ctx.beginPath(); ctx.roundRect(cx + S * 0.04, cy + S * 0.16, S * 0.1, S * 0.14, S * 0.02); ctx.fill();
    // Boot tips
    ctx.fillStyle = '#222';
    ctx.beginPath(); ctx.ellipse(cx - S * 0.09, cy + S * 0.31, S * 0.09, S * 0.04, 0, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.ellipse(cx + S * 0.09, cy + S * 0.31, S * 0.09, S * 0.04, 0, 0, Math.PI * 2); ctx.fill();
    return;
  }

  if (dir === 1) {
    // RIGHT SIDE — welding arm extends right with spark tip
    // Shadow glow
    const grd = ctx.createRadialGradient(cx, cy, S * 0.04, cx, cy, S * 0.42);
    grd.addColorStop(0, 'rgba(74,74,74,0.35)'); grd.addColorStop(1, 'rgba(0,0,0,0)');
    ctx.fillStyle = grd; ctx.beginPath(); ctx.ellipse(cx, cy, S * 0.42, S * 0.42, 0, 0, Math.PI * 2); ctx.fill();

    // Torso (side — slightly narrower)
    ctx.fillStyle = '#4a4a4a';
    ctx.beginPath();
    ctx.roundRect(cx - S * 0.13, cy - S * 0.14, S * 0.26, S * 0.3, S * 0.04);
    ctx.fill();
    ctx.strokeStyle = '#333'; ctx.lineWidth = S * 0.014;
    ctx.stroke();

    // Helmet dome (side profile)
    ctx.fillStyle = '#555';
    ctx.beginPath();
    ctx.arc(cx, cy - S * 0.18, S * 0.14, Math.PI, 0, false);
    ctx.closePath();
    ctx.fill();
    ctx.strokeStyle = '#333'; ctx.lineWidth = S * 0.012;
    ctx.stroke();
    // Visor slit (side)
    ctx.fillStyle = hexToRgba(teamColor, 0.85);
    ctx.shadowColor = teamColor; ctx.shadowBlur = 6;
    ctx.fillRect(cx + S * 0.04, cy - S * 0.22, S * 0.1, S * 0.04);
    ctx.shadowBlur = 0;

    // Small backpack protrusion (visible on left side in dir 1)
    ctx.fillStyle = '#383838';
    ctx.beginPath();
    ctx.roundRect(cx - S * 0.18, cy - S * 0.08, S * 0.08, S * 0.12, S * 0.02);
    ctx.fill();
    ctx.strokeStyle = '#222'; ctx.lineWidth = S * 0.01;
    ctx.stroke();

    // Welding arm extends right
    ctx.fillStyle = '#3a3a3a';
    ctx.fillRect(cx + S * 0.12, cy - S * 0.04, S * 0.24, S * 0.07);
    // Welder tool tip — small circle with bright spark in team colour
    ctx.fillStyle = '#555';
    ctx.beginPath(); ctx.arc(cx + S * 0.38, cy - S * 0.005, S * 0.04, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = hexToRgba(teamColor, 1.0);
    ctx.shadowColor = teamColor; ctx.shadowBlur = 8;
    ctx.beginPath(); ctx.arc(cx + S * 0.38, cy - S * 0.005, S * 0.025, 0, Math.PI * 2); ctx.fill();
    ctx.shadowBlur = 0;

    // Leg (single profile)
    ctx.fillStyle = '#333';
    ctx.beginPath(); ctx.roundRect(cx - S * 0.08, cy + S * 0.16, S * 0.16, S * 0.14, S * 0.02); ctx.fill();
    // Boot
    ctx.fillStyle = '#222';
    ctx.beginPath(); ctx.ellipse(cx + S * 0.04, cy + S * 0.31, S * 0.12, S * 0.04, 0, 0, Math.PI * 2); ctx.fill();
    return;
  }

  // FRONT (dir 0)
  // Shadow glow
  const grd = ctx.createRadialGradient(cx, cy, S * 0.04, cx, cy, S * 0.44);
  grd.addColorStop(0, 'rgba(74,74,74,0.35)'); grd.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.fillStyle = grd; ctx.beginPath(); ctx.ellipse(cx, cy, S * 0.44, S * 0.44, 0, 0, Math.PI * 2); ctx.fill();

  // Torso — rounded rect centred at (cx, cy)
  ctx.fillStyle = '#4a4a4a';
  const tX = cx - S * 0.19, tY = cy - S * 0.14, tW = S * 0.38, tH = S * 0.3;
  ctx.beginPath();
  ctx.roundRect(tX, tY, tW, tH, S * 0.05);
  ctx.fill();
  ctx.strokeStyle = '#333'; ctx.lineWidth = S * 0.015;
  ctx.stroke();

  // Chest panel detail (team colour strip)
  ctx.fillStyle = hexToRgba(teamColor, 0.55);
  ctx.fillRect(cx - S * 0.08, cy - S * 0.06, S * 0.16, S * 0.1);

  // Helmet dome
  ctx.fillStyle = '#555';
  ctx.beginPath();
  ctx.arc(cx, cy - S * 0.18, S * 0.16, Math.PI, 0, false);
  ctx.closePath();
  ctx.fill();
  ctx.strokeStyle = '#333'; ctx.lineWidth = S * 0.014;
  ctx.stroke();

  // Visor slit in team colour with shadowBlur ~6
  ctx.fillStyle = hexToRgba(teamColor, 0.9);
  ctx.shadowColor = teamColor; ctx.shadowBlur = 6;
  ctx.fillRect(cx - S * 0.1, cy - S * 0.22, S * 0.2, S * 0.05);
  ctx.shadowBlur = 0;

  // Tool arm — right side, arm at shoulder with small tool circle tip
  ctx.fillStyle = '#3a3a3a';
  ctx.fillRect(cx + S * 0.19, cy - S * 0.09, S * 0.12, S * 0.07);
  // Tool tip circle
  ctx.fillStyle = '#555';
  ctx.beginPath(); ctx.arc(cx + S * 0.33, cy - S * 0.055, S * 0.04, 0, Math.PI * 2); ctx.fill();
  ctx.fillStyle = hexToRgba(teamColor, 0.7);
  ctx.beginPath(); ctx.arc(cx + S * 0.33, cy - S * 0.055, S * 0.022, 0, Math.PI * 2); ctx.fill();

  // Left arm (plain)
  ctx.fillStyle = '#3a3a3a';
  ctx.fillRect(cx - S * 0.31, cy - S * 0.09, S * 0.12, S * 0.07);

  // Legs
  ctx.fillStyle = '#333';
  ctx.beginPath(); ctx.roundRect(cx - S * 0.14, cy + S * 0.16, S * 0.1, S * 0.14, S * 0.02); ctx.fill();
  ctx.beginPath(); ctx.roundRect(cx + S * 0.04, cy + S * 0.16, S * 0.1, S * 0.14, S * 0.02); ctx.fill();
  // Boot tips
  ctx.fillStyle = '#222';
  ctx.beginPath(); ctx.ellipse(cx - S * 0.09, cy + S * 0.31, S * 0.09, S * 0.04, 0, 0, Math.PI * 2); ctx.fill();
  ctx.beginPath(); ctx.ellipse(cx + S * 0.09, cy + S * 0.31, S * 0.09, S * 0.04, 0, 0, Math.PI * 2); ctx.fill();
}

function drawReaper(ctx, S, dir, teamColor) {
  const cx = S / 2, cy = S / 2;

  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawReaper(ctx, S, 1, teamColor); ctx.restore(); return;
  }

  // Body (dark grey, covers centre at 64,64)
  ctx.fillStyle = '#2a2a2a';
  ctx.beginPath();
  ctx.roundRect(cx - S * 0.14, cy - S * 0.18, S * 0.28, S * 0.32, S * 0.04);
  ctx.fill();
  ctx.strokeStyle = '#1a1a1a'; ctx.lineWidth = S * 0.015; ctx.stroke();

  // Legs
  ctx.fillStyle = '#333';
  ctx.fillRect(cx - S * 0.12, cy + S * 0.14, S * 0.09, S * 0.18);
  ctx.fillRect(cx + S * 0.03, cy + S * 0.14, S * 0.09, S * 0.18);

  if (dir === 0 || dir === 2) {
    // Helmet dome
    ctx.fillStyle = '#3a3a3a';
    ctx.beginPath(); ctx.arc(cx, cy - S * 0.2, S * 0.13, 0, Math.PI * 2); ctx.fill();
    // Dark visor (no team colour)
    ctx.fillStyle = '#111';
    ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.2, S * 0.09, S * 0.055, 0, 0, Math.PI * 2); ctx.fill();
    // Jet pack pods (two on sides of back)
    ctx.fillStyle = '#222';
    ctx.fillRect(cx - S * 0.22, cy - S * 0.14, S * 0.07, S * 0.18);
    ctx.fillRect(cx + S * 0.15, cy - S * 0.14, S * 0.07, S * 0.18);
    // Thruster glow (team colour, dir 0 = front so nozzles at top of pods)
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 10;
    ctx.fillStyle = hexToRgba(teamColor, 0.85);
    ctx.beginPath(); ctx.ellipse(cx - S * 0.185, cy - S * 0.16, S * 0.035, S * 0.025, 0, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.ellipse(cx + S * 0.185, cy - S * 0.16, S * 0.035, S * 0.025, 0, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
    // Dual pistols as small dark circles at sides
    ctx.fillStyle = '#1a1a1a';
    ctx.beginPath(); ctx.arc(cx - S * 0.17, cy + S * 0.05, S * 0.035, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.arc(cx + S * 0.17, cy + S * 0.05, S * 0.035, 0, Math.PI * 2); ctx.fill();
  } else {
    // dir === 1 side view
    // Helmet dome
    ctx.fillStyle = '#3a3a3a';
    ctx.beginPath(); ctx.arc(cx - S * 0.02, cy - S * 0.2, S * 0.13, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = '#111';
    ctx.beginPath(); ctx.ellipse(cx + S * 0.05, cy - S * 0.2, S * 0.07, S * 0.045, 0, 0, Math.PI * 2); ctx.fill();
    // Jet pack: two nozzle pods extending rearward-upward
    ctx.fillStyle = '#222';
    ctx.fillRect(cx - S * 0.28, cy - S * 0.16, S * 0.14, S * 0.09);
    ctx.fillRect(cx - S * 0.28, cy - S * 0.06, S * 0.14, S * 0.09);
    // Thruster glow at nozzle exits
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 10;
    ctx.fillStyle = hexToRgba(teamColor, 0.85);
    ctx.beginPath(); ctx.arc(cx - S * 0.28, cy - S * 0.115, S * 0.03, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.arc(cx - S * 0.28, cy - S * 0.015, S * 0.03, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
    // Dual pistol barrels extending forward
    ctx.fillStyle = '#1a1a1a';
    ctx.fillRect(cx + S * 0.13, cy - S * 0.04, S * 0.12, S * 0.035);
    ctx.fillRect(cx + S * 0.13, cy + S * 0.01, S * 0.12, S * 0.035);
  }
}

function drawHellion(ctx, S, dir, teamColor) {
  const cx = S / 2, cy = S / 2;

  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawHellion(ctx, S, 1, teamColor); ctx.restore(); return;
  }

  if (dir === 1) {
    // Side: very long low chassis
    // Chassis body (covers cx,cy)
    ctx.fillStyle = '#3a3a3a';
    ctx.beginPath();
    ctx.roundRect(cx - S * 0.42, cy - S * 0.1, S * 0.84, S * 0.22, S * 0.035);
    ctx.fill();
    ctx.strokeStyle = '#282828'; ctx.lineWidth = S * 0.012; ctx.stroke();
    // Wheels (4 circles below chassis)
    ctx.fillStyle = '#222';
    const wy = cy + S * 0.13;
    for (const wx of [cx - S * 0.3, cx - S * 0.1, cx + S * 0.1, cx + S * 0.28]) {
      ctx.beginPath(); ctx.arc(wx, wy, S * 0.075, 0, Math.PI * 2); ctx.fill();
      ctx.strokeStyle = '#444'; ctx.lineWidth = S * 0.01; ctx.stroke();
    }
    // Cockpit (driver) at rear left
    ctx.fillStyle = '#444';
    ctx.beginPath(); ctx.roundRect(cx - S * 0.38, cy - S * 0.18, S * 0.18, S * 0.12, S * 0.02); ctx.fill();
    // Flamethrower barrel extending right
    ctx.fillStyle = '#2a2a2a';
    ctx.fillRect(cx + S * 0.34, cy - S * 0.05, S * 0.16, S * 0.08);
    // Muzzle/flame glow
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 12;
    ctx.fillStyle = hexToRgba(teamColor, 0.9);
    ctx.beginPath(); ctx.arc(cx + S * 0.47, cy - S * 0.01, S * 0.04, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
  } else {
    // dir 0/2: front/back — wide shallow profile
    // Chassis
    ctx.fillStyle = '#3a3a3a';
    ctx.beginPath();
    ctx.roundRect(cx - S * 0.38, cy - S * 0.1, S * 0.76, S * 0.2, S * 0.03);
    ctx.fill();
    ctx.strokeStyle = '#282828'; ctx.lineWidth = S * 0.012; ctx.stroke();
    // Wheels as ellipses at four corners
    ctx.fillStyle = '#222';
    for (const [wx, wy] of [[cx - S * 0.34, cy + S * 0.1], [cx + S * 0.34, cy + S * 0.1],
                             [cx - S * 0.34, cy - S * 0.1], [cx + S * 0.34, cy - S * 0.1]]) {
      ctx.beginPath(); ctx.ellipse(wx, wy, S * 0.055, S * 0.08, 0, 0, Math.PI * 2); ctx.fill();
    }
    // Barrel tip-on as circular muzzle at centre
    ctx.fillStyle = '#2a2a2a';
    ctx.beginPath(); ctx.arc(cx, cy - S * 0.02, S * 0.06, 0, Math.PI * 2); ctx.fill();
    // Flame glow at muzzle
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 12;
    ctx.fillStyle = hexToRgba(teamColor, 0.85);
    ctx.beginPath(); ctx.arc(cx, cy - S * 0.02, S * 0.035, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
  }
}

function drawHellbat(ctx, S, dir, teamColor) {
  const cx = S / 2, cy = S / 2;

  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawHellbat(ctx, S, 1, teamColor); ctx.restore(); return;
  }

  // Wide squat torso covering centre
  ctx.fillStyle = '#3a3a3a';
  ctx.beginPath();
  ctx.roundRect(cx - S * 0.22, cy - S * 0.18, S * 0.44, S * 0.32, S * 0.04);
  ctx.fill();
  ctx.strokeStyle = '#282828'; ctx.lineWidth = S * 0.015; ctx.stroke();

  // Armoured chest plate (darker panel)
  ctx.fillStyle = '#2a2a2a';
  ctx.beginPath();
  ctx.roundRect(cx - S * 0.14, cy - S * 0.12, S * 0.28, S * 0.2, S * 0.025);
  ctx.fill();

  // Legs: two thick rects below torso, splayed
  ctx.fillStyle = '#333';
  ctx.fillRect(cx - S * 0.2, cy + S * 0.14, S * 0.12, S * 0.18);
  ctx.fillRect(cx + S * 0.08, cy + S * 0.14, S * 0.12, S * 0.18);

  // Head/helmet
  ctx.fillStyle = '#444';
  ctx.beginPath(); ctx.arc(cx, cy - S * 0.22, S * 0.12, 0, Math.PI * 2); ctx.fill();

  if (dir === 0 || dir === 2) {
    // Flamethrower arms: left and right extending outward
    ctx.fillStyle = '#2f2f2f';
    // Left arm
    ctx.fillRect(cx - S * 0.36, cy - S * 0.08, S * 0.16, S * 0.1);
    // Right arm
    ctx.fillRect(cx + S * 0.2, cy - S * 0.08, S * 0.16, S * 0.1);
    // Nozzle glow left and right
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 12;
    ctx.fillStyle = hexToRgba(teamColor, 0.88);
    ctx.beginPath(); ctx.arc(cx - S * 0.38, cy - S * 0.03, S * 0.04, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.arc(cx + S * 0.38, cy - S * 0.03, S * 0.04, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
  } else {
    // dir === 1 side: one arm+barrel extending forward
    ctx.fillStyle = '#2f2f2f';
    ctx.fillRect(cx + S * 0.2, cy - S * 0.06, S * 0.18, S * 0.1);
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 12;
    ctx.fillStyle = hexToRgba(teamColor, 0.88);
    ctx.beginPath(); ctx.arc(cx + S * 0.38, cy - S * 0.01, S * 0.04, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
  }
}

function drawMULE(ctx, S, dir, teamColor) {
  const cx = S / 2, cy = S / 2;

  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawMULE(ctx, S, 1, teamColor); ctx.restore(); return;
  }

  // Large square torso covering centre
  ctx.fillStyle = '#3a3a4a';
  ctx.beginPath();
  ctx.roundRect(cx - S * 0.24, cy - S * 0.2, S * 0.48, S * 0.38, S * 0.04);
  ctx.fill();
  ctx.strokeStyle = '#272733'; ctx.lineWidth = S * 0.015; ctx.stroke();

  // Chest panel detail
  ctx.fillStyle = '#2f2f3f';
  ctx.beginPath(); ctx.roundRect(cx - S * 0.14, cy - S * 0.1, S * 0.28, S * 0.18, S * 0.025); ctx.fill();

  // Four corner legs
  ctx.fillStyle = '#303040';
  const legPairs = [
    [cx - S * 0.3, cy + S * 0.18], [cx + S * 0.16, cy + S * 0.18],
    [cx - S * 0.3, cy - S * 0.04], [cx + S * 0.16, cy - S * 0.04]
  ];
  for (const [lx, ly] of legPairs) {
    ctx.fillRect(lx, ly, S * 0.14, S * 0.14);
  }

  if (dir === 1) {
    // Mining arm: extends upward-right with drill tip
    ctx.fillStyle = '#3a3a4a';
    ctx.fillRect(cx + S * 0.1, cy - S * 0.38, S * 0.1, S * 0.2);
    ctx.fillRect(cx + S * 0.18, cy - S * 0.38, S * 0.14, S * 0.1);
    // Drill tip (darker cone)
    ctx.fillStyle = '#222';
    ctx.beginPath(); ctx.arc(cx + S * 0.3, cy - S * 0.38, S * 0.05, 0, Math.PI * 2); ctx.fill();
  } else {
    // dir 0/2: L-shape arm on top
    ctx.fillStyle = '#3a3a4a';
    ctx.fillRect(cx - S * 0.06, cy - S * 0.38, S * 0.12, S * 0.2);
    ctx.fillRect(cx + S * 0.06, cy - S * 0.34, S * 0.14, S * 0.1);
    ctx.fillStyle = '#222';
    ctx.beginPath(); ctx.arc(cx + S * 0.19, cy - S * 0.29, S * 0.05, 0, Math.PI * 2); ctx.fill();
  }

  // Sensor dome on top with team colour glow
  ctx.save();
  ctx.shadowColor = teamColor; ctx.shadowBlur = 8;
  ctx.fillStyle = hexToRgba(teamColor, 0.8);
  ctx.beginPath(); ctx.arc(cx, cy - S * 0.22, S * 0.065, 0, Math.PI * 2); ctx.fill();
  ctx.restore();
  ctx.fillStyle = '#3a3a4a';
  ctx.beginPath(); ctx.arc(cx, cy - S * 0.22, S * 0.05, 0, Math.PI * 2); ctx.fill();
}

function drawVikingAssault(ctx, S, dir, teamColor) {
  const cx = S / 2, cy = S / 2;

  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawVikingAssault(ctx, S, 1, teamColor); ctx.restore(); return;
  }

  // Body torso (Terran blue-grey, covers centre)
  ctx.fillStyle = '#4a5060';
  ctx.beginPath();
  ctx.roundRect(cx - S * 0.18, cy - S * 0.14, S * 0.36, S * 0.28, S * 0.04);
  ctx.fill();
  ctx.strokeStyle = '#353a44'; ctx.lineWidth = S * 0.015; ctx.stroke();

  // Cockpit dome at top
  ctx.fillStyle = '#5a6070';
  ctx.beginPath(); ctx.arc(cx, cy - S * 0.18, S * 0.12, 0, Math.PI * 2); ctx.fill();
  // Visor glow (team colour)
  ctx.save();
  ctx.shadowColor = teamColor; ctx.shadowBlur = 6;
  ctx.fillStyle = hexToRgba(teamColor, 0.8);
  ctx.beginPath(); ctx.ellipse(cx, cy - S * 0.18, S * 0.07, S * 0.04, 0, 0, Math.PI * 2); ctx.fill();
  ctx.restore();

  // Mechanical legs
  ctx.fillStyle = '#404550';
  if (dir === 0 || dir === 2) {
    // Wider stance
    ctx.fillRect(cx - S * 0.22, cy + S * 0.14, S * 0.14, S * 0.22);
    ctx.fillRect(cx + S * 0.08, cy + S * 0.14, S * 0.14, S * 0.22);
    // Folded wings at sides (tips)
    ctx.fillStyle = '#3a404e';
    ctx.beginPath();
    ctx.moveTo(cx - S * 0.18, cy - S * 0.08); ctx.lineTo(cx - S * 0.44, cy + S * 0.04); ctx.lineTo(cx - S * 0.18, cy + S * 0.08); ctx.closePath(); ctx.fill();
    ctx.beginPath();
    ctx.moveTo(cx + S * 0.18, cy - S * 0.08); ctx.lineTo(cx + S * 0.44, cy + S * 0.04); ctx.lineTo(cx + S * 0.18, cy + S * 0.08); ctx.closePath(); ctx.fill();
    // Autocannon barrel tips (circles)
    ctx.fillStyle = '#2a2a2a';
    ctx.beginPath(); ctx.arc(cx - S * 0.12, cy - S * 0.04, S * 0.035, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.arc(cx + S * 0.12, cy - S * 0.04, S * 0.035, 0, Math.PI * 2); ctx.fill();
  } else {
    // dir 1: side profile
    ctx.fillRect(cx - S * 0.06, cy + S * 0.14, S * 0.12, S * 0.22);
    // Swept-back wings behind fuselage
    ctx.fillStyle = '#3a404e';
    ctx.beginPath();
    ctx.moveTo(cx - S * 0.18, cy - S * 0.05); ctx.lineTo(cx - S * 0.44, cy + S * 0.08); ctx.lineTo(cx - S * 0.18, cy + S * 0.1); ctx.closePath(); ctx.fill();
    // Autocannon barrel extending forward-right
    ctx.fillStyle = '#2a2a2a';
    ctx.fillRect(cx + S * 0.18, cy - S * 0.06, S * 0.14, S * 0.04);
    ctx.fillRect(cx + S * 0.18, cy, S * 0.14, S * 0.04);
  }
}

function drawLiberatorAG(ctx, S, dir, teamColor) {
  const cx = S / 2, cy = S / 2;

  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawLiberatorAG(ctx, S, 1, teamColor); ctx.restore(); return;
  }

  if (dir === 1) {
    // Side: fuselage profile with downward-angled weapon barrels
    // Fuselage body
    ctx.fillStyle = '#3a4050';
    ctx.beginPath();
    ctx.roundRect(cx - S * 0.32, cy - S * 0.1, S * 0.64, S * 0.2, S * 0.04);
    ctx.fill();
    ctx.strokeStyle = '#2a2f3a'; ctx.lineWidth = S * 0.012; ctx.stroke();
    // Cockpit
    ctx.fillStyle = '#4a5060';
    ctx.beginPath(); ctx.ellipse(cx - S * 0.18, cy - S * 0.06, S * 0.1, S * 0.08, 0, 0, Math.PI * 2); ctx.fill();
    // Engine glow
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 10;
    ctx.fillStyle = hexToRgba(teamColor, 0.8);
    ctx.beginPath(); ctx.arc(cx + S * 0.33, cy, S * 0.04, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
    // Two downward-angled weapon barrels
    ctx.fillStyle = '#222';
    ctx.save();
    ctx.translate(cx, cy + S * 0.06);
    ctx.rotate(Math.PI * 0.2);
    ctx.fillRect(-S * 0.04, 0, S * 0.08, S * 0.2);
    ctx.restore();
    ctx.save();
    ctx.translate(cx + S * 0.12, cy + S * 0.06);
    ctx.rotate(Math.PI * 0.2);
    ctx.fillRect(-S * 0.04, 0, S * 0.08, S * 0.2);
    ctx.restore();
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 8;
    ctx.fillStyle = hexToRgba(teamColor, 0.7);
    ctx.beginPath(); ctx.arc(cx + S * 0.04, cy + S * 0.28, S * 0.04, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.arc(cx + S * 0.18, cy + S * 0.28, S * 0.04, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
  } else {
    // dir 0/2: top-down — wide wings, targeting circle, weapon pods
    // Wide wings (wider than standard Liberator)
    ctx.fillStyle = '#3a4050';
    ctx.beginPath();
    ctx.roundRect(cx - S * 0.48, cy - S * 0.12, S * 0.96, S * 0.24, S * 0.05);
    ctx.fill();
    ctx.strokeStyle = '#2a2f3a'; ctx.lineWidth = S * 0.012; ctx.stroke();
    // Central hull
    ctx.fillStyle = '#4a5060';
    ctx.beginPath();
    ctx.roundRect(cx - S * 0.14, cy - S * 0.2, S * 0.28, S * 0.4, S * 0.05);
    ctx.fill();
    // Targeting circle ring (defining visual — team colour ring below hull)
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 10;
    ctx.strokeStyle = hexToRgba(teamColor, 0.9);
    ctx.lineWidth = S * 0.025;
    ctx.beginPath(); ctx.arc(cx, cy, S * 0.22, 0, Math.PI * 2); ctx.stroke();
    ctx.restore();
    // Engine glow rear
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 10;
    ctx.fillStyle = hexToRgba(teamColor, 0.8);
    ctx.beginPath(); ctx.arc(cx, cy + S * 0.22, S * 0.045, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
    // Weapon pods pointing downward (as small circles at wing tips area)
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 8;
    ctx.fillStyle = hexToRgba(teamColor, 0.75);
    ctx.beginPath(); ctx.arc(cx - S * 0.3, cy, S * 0.04, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.arc(cx + S * 0.3, cy, S * 0.04, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
  }
}

function drawPhoenix(ctx, S, dir, teamColor) {
  if (dir === 3) { ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1); drawPhoenix(ctx, S, 1, teamColor); ctx.restore(); return; }
  const cx = S / 2, cy = S / 2;
  const hullColor = '#1e1e40';
  const wingColor = '#252550';
  const goldAccent = '#c8a840';

  if (dir === 0 || dir === 2) {
    const flip = dir === 2 ? -1 : 1;
    // Swept-forward wings — two triangular shapes angling from body centre forward-outward
    ctx.fillStyle = wingColor;
    ctx.beginPath();
    ctx.moveTo(cx, cy);
    ctx.lineTo(cx - S * 0.42, cy + flip * S * 0.22);
    ctx.lineTo(cx - S * 0.18, cy - flip * S * 0.10);
    ctx.closePath();
    ctx.fill();
    ctx.beginPath();
    ctx.moveTo(cx, cy);
    ctx.lineTo(cx + S * 0.42, cy + flip * S * 0.22);
    ctx.lineTo(cx + S * 0.18, cy - flip * S * 0.10);
    ctx.closePath();
    ctx.fill();
    // Narrow central spine / fuselage
    ctx.fillStyle = hullColor;
    ctx.beginPath();
    ctx.moveTo(cx, cy - flip * S * 0.30); // nose tip
    ctx.lineTo(cx - S * 0.06, cy);
    ctx.lineTo(cx - S * 0.05, cy + flip * S * 0.18);
    ctx.lineTo(cx + S * 0.05, cy + flip * S * 0.18);
    ctx.lineTo(cx + S * 0.06, cy);
    ctx.closePath();
    ctx.fill();
    // Gold accent stripes on wings
    ctx.strokeStyle = goldAccent; ctx.lineWidth = S * 0.018;
    ctx.beginPath(); ctx.moveTo(cx - S * 0.08, cy - flip * S * 0.04); ctx.lineTo(cx - S * 0.36, cy + flip * S * 0.18); ctx.stroke();
    ctx.beginPath(); ctx.moveTo(cx + S * 0.08, cy - flip * S * 0.04); ctx.lineTo(cx + S * 0.36, cy + flip * S * 0.18); ctx.stroke();
    // Ion cannon at nose tip
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 14;
    ctx.fillStyle = hexToRgba(teamColor, 1.0);
    ctx.beginPath(); ctx.arc(cx, cy - flip * S * 0.30, S * 0.03, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
    // Engine glow at rear
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 10;
    ctx.fillStyle = hexToRgba(teamColor, 0.75);
    ctx.beginPath(); ctx.arc(cx - S * 0.04, cy + flip * S * 0.18, S * 0.03, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.arc(cx + S * 0.04, cy + flip * S * 0.18, S * 0.03, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
  } else {
    // Dir 1 — side profile: sleek pointed shape
    ctx.fillStyle = hullColor;
    ctx.beginPath();
    ctx.moveTo(cx + S * 0.32, cy); // pointed nose (front = right)
    ctx.lineTo(cx, cy - S * 0.06);
    ctx.lineTo(cx - S * 0.32, cy - S * 0.04);
    ctx.lineTo(cx - S * 0.32, cy + S * 0.04);
    ctx.lineTo(cx, cy + S * 0.08);
    ctx.closePath();
    ctx.fill();
    // Forward-swept wing visible above
    ctx.fillStyle = wingColor;
    ctx.beginPath();
    ctx.moveTo(cx - S * 0.05, cy - S * 0.04);
    ctx.lineTo(cx + S * 0.22, cy - S * 0.04);
    ctx.lineTo(cx - S * 0.05, cy - S * 0.22);
    ctx.closePath();
    ctx.fill();
    // Gold stripe
    ctx.strokeStyle = goldAccent; ctx.lineWidth = S * 0.018;
    ctx.beginPath(); ctx.moveTo(cx - S * 0.28, cy); ctx.lineTo(cx + S * 0.28, cy); ctx.stroke();
    // Ion cannon glow at nose
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 14;
    ctx.fillStyle = hexToRgba(teamColor, 1.0);
    ctx.beginPath(); ctx.arc(cx + S * 0.32, cy, S * 0.025, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
    // Engine glow at rear
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 10;
    ctx.fillStyle = hexToRgba(teamColor, 0.75);
    ctx.beginPath(); ctx.arc(cx - S * 0.32, cy, S * 0.03, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
  }
}

function drawOracle(ctx, S, dir, teamColor) {
  if (dir === 3) { ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1); drawOracle(ctx, S, 1, teamColor); ctx.restore(); return; }
  const cx = S / 2, cy = S / 2;
  const hullColor = '#1a1a38';
  const panelColor = '#252548';
  const goldAccent = '#b89830';

  if (dir === 0 || dir === 2) {
    const flip = dir === 2 ? -1 : 1;
    // Elongated oval/lens hull
    ctx.fillStyle = hullColor;
    ctx.beginPath();
    ctx.ellipse(cx, cy, S * 0.28, S * 0.18, 0, 0, Math.PI * 2);
    ctx.fill();
    // Inner panel
    ctx.fillStyle = panelColor;
    ctx.beginPath();
    ctx.ellipse(cx, cy, S * 0.20, S * 0.12, 0, 0, Math.PI * 2);
    ctx.fill();
    // Two small swept-back fins
    ctx.fillStyle = goldAccent;
    ctx.beginPath();
    ctx.moveTo(cx - S * 0.20, cy);
    ctx.lineTo(cx - S * 0.38, cy + flip * S * 0.14);
    ctx.lineTo(cx - S * 0.20, cy + flip * S * 0.06);
    ctx.closePath();
    ctx.fill();
    ctx.beginPath();
    ctx.moveTo(cx + S * 0.20, cy);
    ctx.lineTo(cx + S * 0.38, cy + flip * S * 0.14);
    ctx.lineTo(cx + S * 0.20, cy + flip * S * 0.06);
    ctx.closePath();
    ctx.fill();
    // Psionic cannon projector — long barrel extending forward from hull centre
    ctx.fillStyle = panelColor;
    ctx.fillRect(cx - S * 0.025, cy - flip * S * 0.38, S * 0.05, S * 0.22);
    // Cannon tip glow
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 14;
    ctx.fillStyle = hexToRgba(teamColor, 1.0);
    ctx.beginPath(); ctx.arc(cx, cy - flip * S * 0.38, S * 0.035, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
    // Engine glow at rear
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 8;
    ctx.fillStyle = hexToRgba(teamColor, 0.7);
    ctx.beginPath(); ctx.arc(cx, cy + flip * S * 0.18, S * 0.03, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
  } else {
    // Dir 1 — thin blade-like side profile
    ctx.fillStyle = hullColor;
    ctx.beginPath();
    ctx.moveTo(cx + S * 0.20, cy - S * 0.05);
    ctx.lineTo(cx + S * 0.20, cy + S * 0.05);
    ctx.lineTo(cx - S * 0.28, cy + S * 0.07);
    ctx.lineTo(cx - S * 0.28, cy - S * 0.07);
    ctx.closePath();
    ctx.fill();
    // Cannon barrel extending forward
    ctx.fillStyle = panelColor;
    ctx.fillRect(cx + S * 0.18, cy - S * 0.02, S * 0.24, S * 0.04);
    // Cannon tip glow
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 14;
    ctx.fillStyle = hexToRgba(teamColor, 1.0);
    ctx.beginPath(); ctx.arc(cx + S * 0.42, cy, S * 0.03, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
    // Engine glow at rear
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 8;
    ctx.fillStyle = hexToRgba(teamColor, 0.7);
    ctx.beginPath(); ctx.arc(cx - S * 0.30, cy, S * 0.03, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
  }
}

function drawTempest(ctx, S, dir, teamColor) {
  if (dir === 3) { ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1); drawTempest(ctx, S, 1, teamColor); ctx.restore(); return; }
  const cx = S / 2, cy = S / 2;
  const hullColor = '#10102a';
  const wingColor = '#1e1e3e';
  const goldAccent = '#c0a030';

  if (dir === 0 || dir === 2) {
    const flip = dir === 2 ? -1 : 1;
    // Wide arrowhead/chevron hull — broad at rear tapering to nose point
    ctx.fillStyle = wingColor;
    ctx.beginPath();
    ctx.moveTo(cx, cy - flip * S * 0.24); // nose point
    ctx.lineTo(cx - S * 0.46, cy + flip * S * 0.28);
    ctx.lineTo(cx - S * 0.46, cy + flip * S * 0.10);
    ctx.lineTo(cx, cy + flip * S * 0.02);
    ctx.lineTo(cx + S * 0.46, cy + flip * S * 0.10);
    ctx.lineTo(cx + S * 0.46, cy + flip * S * 0.28);
    ctx.closePath();
    ctx.fill();
    // Central hull darker body
    ctx.fillStyle = hullColor;
    ctx.beginPath();
    ctx.moveTo(cx, cy - flip * S * 0.24);
    ctx.lineTo(cx - S * 0.12, cy + flip * S * 0.12);
    ctx.lineTo(cx + S * 0.12, cy + flip * S * 0.12);
    ctx.closePath();
    ctx.fill();
    // Massive long-range cannon extending forward from hull centre
    ctx.fillStyle = '#0a0a1e';
    ctx.fillRect(cx - S * 0.028, cy - flip * S * 0.46, S * 0.056, flip * S * 0.22);
    // Gold accent wing edges
    ctx.strokeStyle = goldAccent; ctx.lineWidth = S * 0.016;
    ctx.beginPath(); ctx.moveTo(cx - S * 0.04, cy - flip * S * 0.12); ctx.lineTo(cx - S * 0.42, cy + flip * S * 0.22); ctx.stroke();
    ctx.beginPath(); ctx.moveTo(cx + S * 0.04, cy - flip * S * 0.12); ctx.lineTo(cx + S * 0.42, cy + flip * S * 0.22); ctx.stroke();
    // Cannon tip glow
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 14;
    ctx.fillStyle = hexToRgba(teamColor, 1.0);
    ctx.beginPath(); ctx.arc(cx, cy - flip * S * 0.46, S * 0.038, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
    // Running lights
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 8;
    ctx.fillStyle = hexToRgba(teamColor, 0.75);
    ctx.beginPath(); ctx.arc(cx - S * 0.42, cy + flip * S * 0.22, S * 0.025, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.arc(cx + S * 0.42, cy + flip * S * 0.22, S * 0.025, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
  } else {
    // Dir 1 — long elongated side profile
    ctx.fillStyle = hullColor;
    ctx.beginPath();
    ctx.moveTo(cx + S * 0.10, cy - S * 0.08); // pointed front-top
    ctx.lineTo(cx + S * 0.18, cy);
    ctx.lineTo(cx + S * 0.10, cy + S * 0.08);
    ctx.lineTo(cx - S * 0.36, cy + S * 0.10);
    ctx.lineTo(cx - S * 0.36, cy - S * 0.10);
    ctx.closePath();
    ctx.fill();
    // Wing visible above
    ctx.fillStyle = wingColor;
    ctx.beginPath();
    ctx.moveTo(cx - S * 0.36, cy - S * 0.08);
    ctx.lineTo(cx - S * 0.36, cy - S * 0.28);
    ctx.lineTo(cx + S * 0.14, cy - S * 0.08);
    ctx.closePath();
    ctx.fill();
    // Long cannon extending forward
    ctx.fillStyle = '#0a0a1e';
    ctx.fillRect(cx + S * 0.18, cy - S * 0.025, S * 0.26, S * 0.05);
    // Cannon tip glow
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 14;
    ctx.fillStyle = hexToRgba(teamColor, 1.0);
    ctx.beginPath(); ctx.arc(cx + S * 0.44, cy, S * 0.03, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
    // Engine glow at rear
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 8;
    ctx.fillStyle = hexToRgba(teamColor, 0.75);
    ctx.beginPath(); ctx.arc(cx - S * 0.38, cy, S * 0.04, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
  }
}

function drawMothership(ctx, S, dir, teamColor) {
  if (dir === 3) { ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1); drawMothership(ctx, S, 1, teamColor); ctx.restore(); return; }
  const cx = S / 2, cy = S / 2;
  const hullColor = '#12122a';
  const panelColor = '#1e1e3c';

  if (dir === 0 || dir === 2) {
    // Large filled circle hull
    ctx.fillStyle = hullColor;
    ctx.beginPath();
    ctx.arc(cx, cy, S * 0.42, 0, Math.PI * 2);
    ctx.fill();
    // Inner panel ring
    ctx.fillStyle = panelColor;
    ctx.beginPath();
    ctx.arc(cx, cy, S * 0.34, 0, Math.PI * 2);
    ctx.fill();
    // Outer ornamental spires (small protrusions at 8 compass points)
    ctx.fillStyle = '#2a2a4a';
    for (let i = 0; i < 8; i++) {
      const angle = (i / 8) * Math.PI * 2;
      const sx = cx + Math.cos(angle) * S * 0.40;
      const sy = cy + Math.sin(angle) * S * 0.40;
      ctx.beginPath(); ctx.arc(sx, sy, S * 0.025, 0, Math.PI * 2); ctx.fill();
    }
    // Three engine nacelle pods at 120° spacing
    ctx.fillStyle = '#222240';
    const nacAngles = [Math.PI * 0.5, Math.PI * 1.17, Math.PI * 1.83];
    for (const ang of nacAngles) {
      const nx = cx + Math.cos(ang) * S * 0.32;
      const ny = cy + Math.sin(ang) * S * 0.32;
      ctx.save();
      ctx.translate(nx, ny);
      ctx.rotate(ang);
      ctx.beginPath();
      ctx.ellipse(0, 0, S * 0.07, S * 0.04, 0, 0, Math.PI * 2);
      ctx.fill();
      ctx.restore();
    }
    // Vortex/spiral energy in centre — team colour arcs spiralling inward
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 16;
    ctx.strokeStyle = hexToRgba(teamColor, 0.9);
    for (let i = 0; i < 3; i++) {
      const startAngle = (i / 3) * Math.PI * 2;
      ctx.lineWidth = S * 0.018;
      ctx.beginPath();
      ctx.arc(cx, cy, S * 0.18, startAngle, startAngle + Math.PI * 1.2);
      ctx.stroke();
      ctx.lineWidth = S * 0.012;
      ctx.beginPath();
      ctx.arc(cx, cy, S * 0.10, startAngle + Math.PI * 0.6, startAngle + Math.PI * 1.6);
      ctx.stroke();
    }
    // Bright vortex core
    ctx.fillStyle = hexToRgba(teamColor, 0.95);
    ctx.shadowBlur = 20;
    ctx.beginPath(); ctx.arc(cx, cy, S * 0.045, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
    // Engine nacelle glows
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 10;
    ctx.fillStyle = hexToRgba(teamColor, 0.6);
    for (const ang of nacAngles) {
      const nx = cx + Math.cos(ang) * S * 0.32;
      const ny = cy + Math.sin(ang) * S * 0.32;
      ctx.beginPath(); ctx.arc(nx, ny, S * 0.025, 0, Math.PI * 2); ctx.fill();
    }
    ctx.restore();
  } else {
    // Dir 1 — saucer/disc side profile, wide flat oval
    ctx.fillStyle = hullColor;
    ctx.beginPath();
    ctx.ellipse(cx, cy, S * 0.44, S * 0.12, 0, 0, Math.PI * 2);
    ctx.fill();
    // Upper dome
    ctx.fillStyle = panelColor;
    ctx.beginPath();
    ctx.ellipse(cx, cy - S * 0.06, S * 0.22, S * 0.10, 0, 0, Math.PI * 2);
    ctx.fill();
    // Engine pods at sides
    ctx.fillStyle = '#222240';
    ctx.beginPath(); ctx.ellipse(cx - S * 0.38, cy + S * 0.04, S * 0.06, S * 0.04, 0, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.ellipse(cx + S * 0.38, cy + S * 0.04, S * 0.06, S * 0.04, 0, 0, Math.PI * 2); ctx.fill();
    // Vortex glow at centre
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 16;
    ctx.fillStyle = hexToRgba(teamColor, 0.9);
    ctx.beginPath(); ctx.arc(cx, cy - S * 0.04, S * 0.05, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
    // Engine glows
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 10;
    ctx.fillStyle = hexToRgba(teamColor, 0.6);
    ctx.beginPath(); ctx.arc(cx - S * 0.38, cy + S * 0.04, S * 0.025, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.arc(cx + S * 0.38, cy + S * 0.04, S * 0.025, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
  }
}

function drawWarpPrism(ctx, S, dir, teamColor) {
  if (dir === 3) { ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1); drawWarpPrism(ctx, S, 1, teamColor); ctx.restore(); return; }
  const cx = S / 2, cy = S / 2;
  const hullColor = '#181830';
  const edgeColor = '#2a2a50';
  const goldAccent = '#b89828';

  // Helper to draw an octagon centred at (cx,cy)
  function drawOctagon(radiusX, radiusY) {
    const sides = 8;
    ctx.beginPath();
    for (let i = 0; i < sides; i++) {
      const angle = (i / sides) * Math.PI * 2 - Math.PI / 8;
      const px = cx + Math.cos(angle) * radiusX;
      const py = cy + Math.sin(angle) * radiusY;
      i === 0 ? ctx.moveTo(px, py) : ctx.lineTo(px, py);
    }
    ctx.closePath();
  }

  if (dir === 0 || dir === 2) {
    // Octagonal body — hard-edged
    ctx.fillStyle = hullColor;
    drawOctagon(S * 0.36, S * 0.36);
    ctx.fill();
    // Edge facets
    ctx.strokeStyle = edgeColor; ctx.lineWidth = S * 0.022;
    drawOctagon(S * 0.36, S * 0.36);
    ctx.stroke();
    ctx.strokeStyle = goldAccent; ctx.lineWidth = S * 0.012;
    drawOctagon(S * 0.28, S * 0.28);
    ctx.stroke();
    // Thruster pods at lower corners
    ctx.fillStyle = '#222244';
    ctx.beginPath(); ctx.arc(cx - S * 0.26, cy + S * 0.26, S * 0.05, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.arc(cx + S * 0.26, cy + S * 0.26, S * 0.05, 0, Math.PI * 2); ctx.fill();
    // Glowing warp crystal core
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 14;
    ctx.fillStyle = hexToRgba(teamColor, 0.95);
    ctx.beginPath(); ctx.arc(cx, cy, S * 0.07, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
  } else {
    // Dir 1 — faceted diamond profile, taller than wide
    ctx.fillStyle = hullColor;
    ctx.beginPath();
    ctx.moveTo(cx, cy - S * 0.36); // top
    ctx.lineTo(cx + S * 0.22, cy - S * 0.12);
    ctx.lineTo(cx + S * 0.22, cy + S * 0.12);
    ctx.lineTo(cx, cy + S * 0.36); // bottom
    ctx.lineTo(cx - S * 0.22, cy + S * 0.12);
    ctx.lineTo(cx - S * 0.22, cy - S * 0.12);
    ctx.closePath();
    ctx.fill();
    // Facet edge
    ctx.strokeStyle = edgeColor; ctx.lineWidth = S * 0.022;
    ctx.stroke();
    ctx.strokeStyle = goldAccent; ctx.lineWidth = S * 0.012;
    ctx.beginPath();
    ctx.moveTo(cx, cy - S * 0.26);
    ctx.lineTo(cx + S * 0.15, cy - S * 0.08);
    ctx.lineTo(cx + S * 0.15, cy + S * 0.08);
    ctx.lineTo(cx, cy + S * 0.26);
    ctx.lineTo(cx - S * 0.15, cy + S * 0.08);
    ctx.lineTo(cx - S * 0.15, cy - S * 0.08);
    ctx.closePath();
    ctx.stroke();
    // Crystal core glow at centre
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 14;
    ctx.fillStyle = hexToRgba(teamColor, 0.95);
    ctx.beginPath(); ctx.ellipse(cx, cy, S * 0.055, S * 0.09, 0, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
    // Thruster at bottom
    ctx.fillStyle = '#222244';
    ctx.beginPath(); ctx.ellipse(cx, cy + S * 0.32, S * 0.05, S * 0.03, 0, 0, Math.PI * 2); ctx.fill();
  }
}

function drawWarpPrismPhasing(ctx, S, dir, teamColor) {
  if (dir === 3) { ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1); drawWarpPrismPhasing(ctx, S, 1, teamColor); ctx.restore(); return; }
  const cx = S / 2, cy = S / 2;
  const hullColor = '#181830';
  const edgeColor = '#3030608';
  const goldAccent = '#b89828';

  function drawOctagon(radiusX, radiusY) {
    const sides = 8;
    ctx.beginPath();
    for (let i = 0; i < sides; i++) {
      const angle = (i / sides) * Math.PI * 2 - Math.PI / 8;
      const px = cx + Math.cos(angle) * radiusX;
      const py = cy + Math.sin(angle) * radiusY;
      i === 0 ? ctx.moveTo(px, py) : ctx.lineTo(px, py);
    }
    ctx.closePath();
  }

  if (dir === 0 || dir === 2) {
    // Semi-transparent body
    ctx.fillStyle = hexToRgba(hullColor, 0.7);
    drawOctagon(S * 0.38, S * 0.38);
    ctx.fill();
    // Edge facets
    ctx.strokeStyle = hexToRgba('#3a3a60', 0.8); ctx.lineWidth = S * 0.022;
    drawOctagon(S * 0.38, S * 0.38);
    ctx.stroke();
    // Warp field beacon — outer ring, defining feature
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 18;
    ctx.strokeStyle = hexToRgba(teamColor, 0.9);
    ctx.lineWidth = S * 0.030;
    ctx.beginPath(); ctx.arc(cx, cy, S * 0.35, 0, Math.PI * 2); ctx.stroke();
    ctx.restore();
    // Landing struts
    ctx.strokeStyle = hexToRgba(goldAccent, 0.7); ctx.lineWidth = S * 0.014;
    ctx.beginPath(); ctx.moveTo(cx - S * 0.20, cy + S * 0.20); ctx.lineTo(cx - S * 0.32, cy + S * 0.36); ctx.stroke();
    ctx.beginPath(); ctx.moveTo(cx + S * 0.20, cy + S * 0.20); ctx.lineTo(cx + S * 0.32, cy + S * 0.36); ctx.stroke();
    ctx.beginPath(); ctx.moveTo(cx, cy + S * 0.26); ctx.lineTo(cx, cy + S * 0.40); ctx.stroke();
    // Bright crystal core glow
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 20;
    ctx.fillStyle = hexToRgba(teamColor, 0.98);
    ctx.beginPath(); ctx.arc(cx, cy, S * 0.075, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
  } else {
    // Dir 1 — same diamond profile, semi-transparent, with beacon ring visible
    ctx.fillStyle = hexToRgba(hullColor, 0.7);
    ctx.beginPath();
    ctx.moveTo(cx, cy - S * 0.36);
    ctx.lineTo(cx + S * 0.22, cy - S * 0.12);
    ctx.lineTo(cx + S * 0.22, cy + S * 0.12);
    ctx.lineTo(cx, cy + S * 0.36);
    ctx.lineTo(cx - S * 0.22, cy + S * 0.12);
    ctx.lineTo(cx - S * 0.22, cy - S * 0.12);
    ctx.closePath();
    ctx.fill();
    // Edge
    ctx.strokeStyle = hexToRgba('#3a3a60', 0.8); ctx.lineWidth = S * 0.022;
    ctx.stroke();
    // Warp field beacon ring (elliptical in side view)
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 18;
    ctx.strokeStyle = hexToRgba(teamColor, 0.9);
    ctx.lineWidth = S * 0.028;
    ctx.beginPath(); ctx.ellipse(cx, cy, S * 0.35, S * 0.20, 0, 0, Math.PI * 2); ctx.stroke();
    ctx.restore();
    // Landing struts
    ctx.strokeStyle = hexToRgba(goldAccent, 0.7); ctx.lineWidth = S * 0.014;
    ctx.beginPath(); ctx.moveTo(cx - S * 0.18, cy + S * 0.12); ctx.lineTo(cx - S * 0.28, cy + S * 0.30); ctx.stroke();
    ctx.beginPath(); ctx.moveTo(cx + S * 0.18, cy + S * 0.12); ctx.lineTo(cx + S * 0.28, cy + S * 0.30); ctx.stroke();
    // Crystal core
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 20;
    ctx.fillStyle = hexToRgba(teamColor, 0.98);
    ctx.beginPath(); ctx.ellipse(cx, cy, S * 0.055, S * 0.09, 0, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
  }
}

function drawInterceptor(ctx, S, dir, teamColor) {
  if (dir === 3) { ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1); drawInterceptor(ctx, S, 1, teamColor); ctx.restore(); return; }
  const cx = S / 2, cy = S / 2;
  const hullColor = '#1e1e40';
  const wingColor = '#252550';

  if (dir === 0 || dir === 2) {
    const flip = dir === 2 ? -1 : 1;
    // Small swept-wing diamond — ~60% scale of Phoenix
    ctx.fillStyle = wingColor;
    ctx.beginPath();
    ctx.moveTo(cx, cy);
    ctx.lineTo(cx - S * 0.26, cy + flip * S * 0.14);
    ctx.lineTo(cx - S * 0.10, cy - flip * S * 0.06);
    ctx.closePath();
    ctx.fill();
    ctx.beginPath();
    ctx.moveTo(cx, cy);
    ctx.lineTo(cx + S * 0.26, cy + flip * S * 0.14);
    ctx.lineTo(cx + S * 0.10, cy - flip * S * 0.06);
    ctx.closePath();
    ctx.fill();
    // Narrow fuselage spine
    ctx.fillStyle = hullColor;
    ctx.beginPath();
    ctx.moveTo(cx, cy - flip * S * 0.18); // nose
    ctx.lineTo(cx - S * 0.04, cy);
    ctx.lineTo(cx - S * 0.03, cy + flip * S * 0.11);
    ctx.lineTo(cx + S * 0.03, cy + flip * S * 0.11);
    ctx.lineTo(cx + S * 0.04, cy);
    ctx.closePath();
    ctx.fill();
    // Ion cannon tip glow
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 8;
    ctx.fillStyle = hexToRgba(teamColor, 1.0);
    ctx.beginPath(); ctx.arc(cx, cy - flip * S * 0.18, S * 0.022, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
    // Engine glow at rear
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 6;
    ctx.fillStyle = hexToRgba(teamColor, 0.70);
    ctx.beginPath(); ctx.arc(cx, cy + flip * S * 0.11, S * 0.02, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
  } else {
    // Dir 1 — tiny blade side profile
    ctx.fillStyle = hullColor;
    ctx.beginPath();
    ctx.moveTo(cx + S * 0.20, cy);
    ctx.lineTo(cx, cy - S * 0.04);
    ctx.lineTo(cx - S * 0.20, cy - S * 0.025);
    ctx.lineTo(cx - S * 0.20, cy + S * 0.025);
    ctx.lineTo(cx, cy + S * 0.05);
    ctx.closePath();
    ctx.fill();
    // Small wing above
    ctx.fillStyle = wingColor;
    ctx.beginPath();
    ctx.moveTo(cx - S * 0.04, cy - S * 0.025);
    ctx.lineTo(cx + S * 0.14, cy - S * 0.025);
    ctx.lineTo(cx - S * 0.04, cy - S * 0.14);
    ctx.closePath();
    ctx.fill();
    // Ion cannon tip
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 8;
    ctx.fillStyle = hexToRgba(teamColor, 1.0);
    ctx.beginPath(); ctx.arc(cx + S * 0.20, cy, S * 0.018, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
    // Engine glow
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 6;
    ctx.fillStyle = hexToRgba(teamColor, 0.70);
    ctx.beginPath(); ctx.arc(cx - S * 0.20, cy, S * 0.02, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
  }
}

function drawAdeptPhaseShift(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawAdeptPhaseShift(ctx, S, 1, teamColor); ctx.restore(); return;
  }
  const cx = S / 2, cy = S / 2;

  // Lower body — very translucent
  ctx.fillStyle = hexToRgba('#5a5030', 0.35);
  ctx.beginPath();
  ctx.ellipse(cx, cy + S * 0.14, S * 0.18, S * 0.14, 0, 0, Math.PI * 2);
  ctx.fill();

  // Upper torso — low alpha ghost body
  ctx.fillStyle = hexToRgba(teamColor, 0.22);
  ctx.beginPath();
  ctx.ellipse(cx, cy - S * 0.04, S * 0.15, S * 0.2, 0, 0, Math.PI * 2);
  ctx.fill();
  // Torso outline — slightly more visible
  ctx.strokeStyle = hexToRgba(teamColor, 0.55);
  ctx.lineWidth = S * 0.02;
  ctx.beginPath();
  ctx.ellipse(cx, cy - S * 0.04, S * 0.15, S * 0.2, 0, 0, Math.PI * 2);
  ctx.stroke();

  // Helm — semi-transparent
  ctx.fillStyle = hexToRgba('#4a4028', 0.40);
  ctx.beginPath();
  ctx.ellipse(cx, cy - S * 0.22, S * 0.10, S * 0.09, 0, 0, Math.PI * 2);
  ctx.fill();

  // Eye-strip visor — glowing
  ctx.fillStyle = hexToRgba(teamColor, 0.80);
  ctx.shadowColor = teamColor;
  ctx.shadowBlur = 8;
  ctx.beginPath();
  ctx.roundRect(cx - S * 0.09, cy - S * 0.25, S * 0.18, S * 0.035, S * 0.01);
  ctx.fill();
  ctx.shadowBlur = 0;

  // Psi-lance blade — most solid part, ensures alpha > 0 at centre
  ctx.fillStyle = hexToRgba(teamColor, 0.65);
  ctx.shadowColor = teamColor;
  ctx.shadowBlur = 10;

  if (dir === 1) {
    ctx.save();
    ctx.translate(cx + S * 0.12, cy - S * 0.05);
    ctx.rotate(-Math.PI / 5);
    ctx.beginPath();
    ctx.moveTo(0, 0);
    ctx.lineTo(S * 0.28, -S * 0.04);
    ctx.lineTo(S * 0.38, -S * 0.01);
    ctx.lineTo(S * 0.28, S * 0.04);
    ctx.closePath();
    ctx.fill();
    ctx.restore();
  } else if (dir === 0) {
    const bx = cx + S * 0.16, by = cy - S * 0.08;
    ctx.beginPath();
    ctx.moveTo(bx, by - S * 0.045);
    ctx.lineTo(bx + S * 0.05, by);
    ctx.lineTo(bx, by + S * 0.045);
    ctx.lineTo(bx - S * 0.025, by);
    ctx.closePath();
    ctx.fill();
  } else if (dir === 2) {
    ctx.shadowBlur = 0;
    ctx.strokeStyle = hexToRgba(teamColor, 0.50);
    ctx.lineWidth = S * 0.02;
    ctx.beginPath();
    ctx.moveTo(cx, cy - S * 0.26);
    ctx.lineTo(cx, cy + S * 0.20);
    ctx.stroke();
  }
  ctx.shadowBlur = 0;

  // Two eye dots at canvas centre row — ensures (64,64) is non-transparent
  ctx.save();
  ctx.shadowColor = teamColor; ctx.shadowBlur = 8;
  ctx.fillStyle = hexToRgba(teamColor, 0.80);
  ctx.beginPath(); ctx.arc(cx - S * 0.04, cy, S * 0.025, 0, Math.PI * 2); ctx.fill();
  ctx.beginPath(); ctx.arc(cx + S * 0.04, cy, S * 0.025, 0, Math.PI * 2); ctx.fill();
  ctx.restore();
}

// drawDrone — Zerg worker unit. Small insectoid, rounder and smaller than Zergling.
// Dir-3 mirrors dir-1.
function drawDrone(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawDrone(ctx, S, 1, teamColor); ctx.restore(); return;
  }
  const cx = S / 2, cy = S / 2;
  ctx.lineCap = 'round'; ctx.lineJoin = 'round';

  // Body — rounded ellipse covers (64,64)
  ctx.fillStyle = '#2a1a08';
  ctx.beginPath();
  ctx.ellipse(cx, cy, S * 0.22, S * 0.18, 0, 0, Math.PI * 2);
  ctx.fill();

  // Carapace highlight
  ctx.fillStyle = '#3a2510';
  ctx.beginPath();
  ctx.ellipse(cx - S * 0.04, cy - S * 0.04, S * 0.12, S * 0.08, -0.3, 0, Math.PI * 2);
  ctx.fill();

  // Wing buds — two small swept-back stubs above body
  ctx.fillStyle = '#1a0f04';
  ctx.beginPath();
  ctx.ellipse(cx - S * 0.10, cy - S * 0.14, S * 0.06, S * 0.03, -0.6, 0, Math.PI * 2);
  ctx.fill();
  ctx.beginPath();
  ctx.ellipse(cx + S * 0.10, cy - S * 0.14, S * 0.06, S * 0.03, 0.6, 0, Math.PI * 2);
  ctx.fill();

  // Claws — two small pointed paths at front
  ctx.fillStyle = '#3a2a10';
  if (dir === 0) {
    // Front claws extend upward
    ctx.beginPath(); ctx.moveTo(cx - S * 0.08, cy - S * 0.16); ctx.lineTo(cx - S * 0.14, cy - S * 0.26); ctx.lineTo(cx - S * 0.04, cy - S * 0.20); ctx.closePath(); ctx.fill();
    ctx.beginPath(); ctx.moveTo(cx + S * 0.08, cy - S * 0.16); ctx.lineTo(cx + S * 0.14, cy - S * 0.26); ctx.lineTo(cx + S * 0.04, cy - S * 0.20); ctx.closePath(); ctx.fill();
  } else if (dir === 1) {
    // Side claws extend right
    ctx.beginPath(); ctx.moveTo(cx + S * 0.18, cy - S * 0.06); ctx.lineTo(cx + S * 0.30, cy - S * 0.10); ctx.lineTo(cx + S * 0.26, cy + S * 0.00); ctx.closePath(); ctx.fill();
  } else {
    // dir === 2 — back, small claw stubs
    ctx.beginPath(); ctx.moveTo(cx - S * 0.08, cy + S * 0.16); ctx.lineTo(cx - S * 0.14, cy + S * 0.26); ctx.lineTo(cx - S * 0.04, cy + S * 0.20); ctx.closePath(); ctx.fill();
    ctx.beginPath(); ctx.moveTo(cx + S * 0.08, cy + S * 0.16); ctx.lineTo(cx + S * 0.14, cy + S * 0.26); ctx.lineTo(cx + S * 0.04, cy + S * 0.20); ctx.closePath(); ctx.fill();
  }

  // Legs — short strokes below body
  ctx.strokeStyle = '#1a0f04'; ctx.lineWidth = S * 0.025;
  const legCount = (dir === 1) ? 2 : 3;
  for (let i = 0; i < legCount; i++) {
    const angle = (dir === 1)
      ? (Math.PI * 0.55 + i * Math.PI * 0.3)
      : (Math.PI * 0.35 + i * Math.PI * 0.28);
    ctx.beginPath();
    ctx.moveTo(cx + Math.cos(angle) * S * 0.15, cy + Math.sin(angle) * S * 0.12);
    ctx.lineTo(cx + Math.cos(angle) * S * 0.30, cy + Math.sin(angle) * S * 0.24);
    ctx.stroke();
  }

  // Eyes — two tiny bright spots in team colour at centre of body
  ctx.save();
  ctx.shadowColor = teamColor; ctx.shadowBlur = 6;
  ctx.fillStyle = hexToRgba(teamColor, 0.90);
  ctx.beginPath(); ctx.arc(cx - S * 0.05, cy - S * 0.02, S * 0.022, 0, Math.PI * 2); ctx.fill();
  ctx.beginPath(); ctx.arc(cx + S * 0.05, cy - S * 0.02, S * 0.022, 0, Math.PI * 2); ctx.fill();
  ctx.restore();
}

// drawOverlord — Zerg large flying transport/supply unit. Bloated jellyfish-like organism.
// Dir-3 mirrors dir-1.
function drawOverlord(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawOverlord(ctx, S, 1, teamColor); ctx.restore(); return;
  }
  const cx = S / 2, cy = S / 2;
  ctx.lineCap = 'round'; ctx.lineJoin = 'round';

  // Tentacles — drawn first (behind body)
  ctx.strokeStyle = '#100618'; ctx.lineWidth = S * 0.028;
  const tentacleCount = (dir === 0 || dir === 2) ? 7 : 5;
  for (let i = 0; i < tentacleCount; i++) {
    const spread = (dir === 0 || dir === 2) ? 0.38 : 0.22;
    const tx = cx + (i / (tentacleCount - 1) - 0.5) * 2 * S * spread;
    const startY = cy + S * 0.22;
    const endY = cy + S * 0.42 + (i % 2 === 0 ? S * 0.06 : 0);
    const cpX = tx + (i % 2 === 0 ? S * 0.06 : -S * 0.06);
    ctx.beginPath();
    ctx.moveTo(tx, startY);
    ctx.quadraticCurveTo(cpX, startY + (endY - startY) * 0.5, tx, endY);
    ctx.stroke();
  }

  // Body — large rounded blob covering (64,64)
  ctx.fillStyle = '#1a0a1a';
  ctx.beginPath();
  if (dir === 0 || dir === 2) {
    ctx.ellipse(cx, cy, S * 0.38, S * 0.30, 0, 0, Math.PI * 2);
  } else {
    ctx.ellipse(cx, cy, S * 0.32, S * 0.26, 0, 0, Math.PI * 2);
  }
  ctx.fill();

  // Bio-sacs — 2–3 rounded bumps on body surface
  ctx.fillStyle = '#2a102a';
  const sacData = (dir === 0 || dir === 2)
    ? [[-S * 0.18, -S * 0.06], [0, -S * 0.14], [S * 0.18, -S * 0.06]]
    : [[-S * 0.10, -S * 0.08], [S * 0.08, -S * 0.04]];
  sacData.forEach(([dx, dy]) => {
    ctx.beginPath();
    ctx.ellipse(cx + dx, cy + dy, S * 0.08, S * 0.06, 0, 0, Math.PI * 2);
    ctx.fill();
  });

  // Eyes — two small team colour glowing dots near front/top
  ctx.save();
  ctx.shadowColor = teamColor; ctx.shadowBlur = 8;
  ctx.fillStyle = hexToRgba(teamColor, 0.85);
  ctx.beginPath(); ctx.arc(cx - S * 0.08, cy - S * 0.08, S * 0.028, 0, Math.PI * 2); ctx.fill();
  ctx.beginPath(); ctx.arc(cx + S * 0.08, cy - S * 0.08, S * 0.028, 0, Math.PI * 2); ctx.fill();
  ctx.restore();
}

// drawOverseer — Evolved Overlord with detection. Similar blob but more angular/evolved.
// Dir-3 mirrors dir-1.
function drawOverseer(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawOverseer(ctx, S, 1, teamColor); ctx.restore(); return;
  }
  const cx = S / 2, cy = S / 2;
  ctx.lineCap = 'round'; ctx.lineJoin = 'round';

  // Tentacles — fewer and shorter than Overlord
  ctx.strokeStyle = '#100618'; ctx.lineWidth = S * 0.026;
  const tentacleCount = (dir === 0 || dir === 2) ? 4 : 3;
  for (let i = 0; i < tentacleCount; i++) {
    const spread = (dir === 0 || dir === 2) ? 0.28 : 0.18;
    const tx = cx + (i / (tentacleCount - 1) - 0.5) * 2 * S * spread;
    const startY = cy + S * 0.18;
    const endY = cy + S * 0.34 + (i % 2 === 0 ? S * 0.04 : 0);
    ctx.beginPath();
    ctx.moveTo(tx, startY);
    ctx.quadraticCurveTo(tx + (i % 2 === 0 ? S * 0.04 : -S * 0.04), startY + (endY - startY) * 0.5, tx, endY);
    ctx.stroke();
  }

  // Body — slightly smaller than Overlord, more elongated
  ctx.fillStyle = '#1a0a1a';
  ctx.beginPath();
  if (dir === 0 || dir === 2) {
    ctx.ellipse(cx, cy, S * 0.32, S * 0.24, 0, 0, Math.PI * 2);
  } else {
    ctx.ellipse(cx, cy, S * 0.28, S * 0.20, 0, 0, Math.PI * 2);
  }
  ctx.fill();

  // Detection eye cluster — 3–4 large compound eye protrusions (KEY visual difference)
  const eyePositions = (dir === 0 || dir === 2)
    ? [[-S * 0.14, -S * 0.14], [0, -S * 0.20], [S * 0.14, -S * 0.14]]
    : [[-S * 0.06, -S * 0.14], [S * 0.08, -S * 0.10], [S * 0.18, -S * 0.04]];

  eyePositions.forEach(([dx, dy], idx) => {
    // Eye protrusion body — rounded ellipse extending from body
    ctx.fillStyle = '#280828';
    ctx.beginPath();
    ctx.ellipse(cx + dx, cy + dy, S * 0.07, S * 0.055, 0, 0, Math.PI * 2);
    ctx.fill();
    // Eye glow — team colour with strong shadowBlur
    ctx.save();
    ctx.shadowColor = teamColor; ctx.shadowBlur = 10;
    ctx.fillStyle = hexToRgba(teamColor, idx === 1 ? 0.95 : 0.75);
    ctx.beginPath();
    ctx.ellipse(cx + dx, cy + dy, S * 0.035, S * 0.028, 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();
  });
}

// drawBaneling — Zerg rolling acid bomb. Nearly spherical, short legs.
// Dir-3 mirrors dir-1.
function drawBaneling(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawBaneling(ctx, S, 1, teamColor); ctx.restore(); return;
  }
  const cx = S / 2, cy = S / 2 + 4;
  ctx.lineCap = 'round'; ctx.lineJoin = 'round';

  // Body — large central circle covers (64,64)
  ctx.fillStyle = '#1a2a08';
  ctx.beginPath();
  ctx.arc(cx, cy, S * 0.24, 0, Math.PI * 2);
  ctx.fill();

  // Acid sac — bulging brighter rounded ellipse on body top/front
  ctx.fillStyle = '#5a8a10';
  ctx.beginPath();
  ctx.ellipse(cx - S * 0.04, cy - S * 0.10, S * 0.12, S * 0.09, -0.2, 0, Math.PI * 2);
  ctx.fill();
  // Acid sac highlight
  ctx.fillStyle = '#7aaa20';
  ctx.beginPath();
  ctx.ellipse(cx - S * 0.06, cy - S * 0.12, S * 0.06, S * 0.04, -0.2, 0, Math.PI * 2);
  ctx.fill();

  // Legs — 4 very short stubby strokes below body
  ctx.strokeStyle = '#0f1a04'; ctx.lineWidth = S * 0.03;
  const legAngles = [Math.PI * 0.55, Math.PI * 0.70, Math.PI * 0.85, Math.PI * 1.00];
  legAngles.forEach(a => {
    ctx.beginPath();
    ctx.moveTo(cx + Math.cos(a) * S * 0.20, cy + Math.sin(a) * S * 0.20);
    ctx.lineTo(cx + Math.cos(a) * S * 0.30, cy + Math.sin(a) * S * 0.30);
    ctx.stroke();
  });

  // Eyes — two small bright spots in team colour
  ctx.save();
  ctx.shadowColor = teamColor; ctx.shadowBlur = 8;
  ctx.fillStyle = hexToRgba(teamColor, 0.90);
  ctx.beginPath(); ctx.arc(cx - S * 0.07, cy - S * 0.04, S * 0.025, 0, Math.PI * 2); ctx.fill();
  ctx.beginPath(); ctx.arc(cx + S * 0.07, cy - S * 0.04, S * 0.025, 0, Math.PI * 2); ctx.fill();
  ctx.restore();

  // Acid drip — small drop at bottom
  ctx.fillStyle = hexToRgba(teamColor, 0.50);
  ctx.beginPath();
  ctx.ellipse(cx, cy + S * 0.26, S * 0.025, S * 0.035, 0, 0, Math.PI * 2);
  ctx.fill();
}

// drawLocust — Zerg flying insectoid spawned by Swarm Host. Angular and predatory.
// Dir-3 mirrors dir-1.
function drawLocust(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawLocust(ctx, S, 1, teamColor); ctx.restore(); return;
  }
  const cx = S / 2, cy = S / 2;
  ctx.lineCap = 'round'; ctx.lineJoin = 'round';

  // Wings — two pairs of angular insect wings giving dragonfly silhouette
  ctx.fillStyle = '#1a1a08';
  if (dir === 0 || dir === 2) {
    // Top-down: 4 wings extend out from centre
    // Upper-left wing
    ctx.beginPath();
    ctx.moveTo(cx, cy - S * 0.04);
    ctx.lineTo(cx - S * 0.38, cy - S * 0.28);
    ctx.lineTo(cx - S * 0.28, cy - S * 0.10);
    ctx.closePath(); ctx.fill();
    // Upper-right wing
    ctx.beginPath();
    ctx.moveTo(cx, cy - S * 0.04);
    ctx.lineTo(cx + S * 0.38, cy - S * 0.28);
    ctx.lineTo(cx + S * 0.28, cy - S * 0.10);
    ctx.closePath(); ctx.fill();
    // Lower-left wing
    ctx.beginPath();
    ctx.moveTo(cx, cy + S * 0.04);
    ctx.lineTo(cx - S * 0.32, cy + S * 0.22);
    ctx.lineTo(cx - S * 0.18, cy + S * 0.08);
    ctx.closePath(); ctx.fill();
    // Lower-right wing
    ctx.beginPath();
    ctx.moveTo(cx, cy + S * 0.04);
    ctx.lineTo(cx + S * 0.32, cy + S * 0.22);
    ctx.lineTo(cx + S * 0.18, cy + S * 0.08);
    ctx.closePath(); ctx.fill();
  } else {
    // dir === 1 — side: wings angled up
    ctx.beginPath();
    ctx.moveTo(cx - S * 0.06, cy - S * 0.02);
    ctx.lineTo(cx - S * 0.36, cy - S * 0.26);
    ctx.lineTo(cx - S * 0.14, cy - S * 0.04);
    ctx.closePath(); ctx.fill();
    ctx.beginPath();
    ctx.moveTo(cx + S * 0.06, cy - S * 0.02);
    ctx.lineTo(cx + S * 0.36, cy - S * 0.22);
    ctx.lineTo(cx + S * 0.16, cy - S * 0.02);
    ctx.closePath(); ctx.fill();
    ctx.beginPath();
    ctx.moveTo(cx - S * 0.06, cy + S * 0.04);
    ctx.lineTo(cx - S * 0.28, cy + S * 0.24);
    ctx.lineTo(cx - S * 0.08, cy + S * 0.08);
    ctx.closePath(); ctx.fill();
    ctx.beginPath();
    ctx.moveTo(cx + S * 0.06, cy + S * 0.04);
    ctx.lineTo(cx + S * 0.28, cy + S * 0.20);
    ctx.lineTo(cx + S * 0.10, cy + S * 0.06);
    ctx.closePath(); ctx.fill();
  }

  // Body — narrow elongated ellipse covers (64,64)
  ctx.fillStyle = '#282808';
  ctx.beginPath();
  if (dir === 0 || dir === 2) {
    ctx.ellipse(cx, cy, S * 0.10, S * 0.18, 0, 0, Math.PI * 2);
  } else {
    ctx.ellipse(cx, cy, S * 0.18, S * 0.08, 0, 0, Math.PI * 2);
  }
  ctx.fill();

  // Mandibles — two short pointed paths at front in team colour
  ctx.save();
  ctx.shadowColor = teamColor; ctx.shadowBlur = 6;
  ctx.fillStyle = hexToRgba(teamColor, 0.80);
  if (dir === 0) {
    ctx.beginPath(); ctx.moveTo(cx - S * 0.04, cy - S * 0.16); ctx.lineTo(cx - S * 0.08, cy - S * 0.24); ctx.lineTo(cx, cy - S * 0.18); ctx.closePath(); ctx.fill();
    ctx.beginPath(); ctx.moveTo(cx + S * 0.04, cy - S * 0.16); ctx.lineTo(cx + S * 0.08, cy - S * 0.24); ctx.lineTo(cx, cy - S * 0.18); ctx.closePath(); ctx.fill();
  } else if (dir === 1) {
    ctx.beginPath(); ctx.moveTo(cx + S * 0.14, cy - S * 0.04); ctx.lineTo(cx + S * 0.24, cy - S * 0.08); ctx.lineTo(cx + S * 0.18, cy + S * 0.02); ctx.closePath(); ctx.fill();
  } else {
    ctx.beginPath(); ctx.moveTo(cx - S * 0.04, cy + S * 0.14); ctx.lineTo(cx - S * 0.08, cy + S * 0.22); ctx.lineTo(cx, cy + S * 0.16); ctx.closePath(); ctx.fill();
    ctx.beginPath(); ctx.moveTo(cx + S * 0.04, cy + S * 0.14); ctx.lineTo(cx + S * 0.08, cy + S * 0.22); ctx.lineTo(cx, cy + S * 0.16); ctx.closePath(); ctx.fill();
  }
  ctx.restore();
}

// drawBroodling — Tiny spider-like creature, smallest Zerg unit. 6 radiating legs.
// Dir-3 mirrors dir-1.
function drawBroodling(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawBroodling(ctx, S, 1, teamColor); ctx.restore(); return;
  }
  const cx = S / 2, cy = S / 2;
  ctx.lineCap = 'round'; ctx.lineJoin = 'round';

  // Legs — 6 thin line strokes radiating at 60° apart from body
  ctx.strokeStyle = '#222222'; ctx.lineWidth = S * 0.018;
  for (let i = 0; i < 6; i++) {
    const angle = (i * Math.PI / 3) + (dir === 1 ? Math.PI * 0.08 : 0);
    ctx.beginPath();
    ctx.moveTo(cx + Math.cos(angle) * S * 0.10, cy + Math.sin(angle) * S * 0.10);
    ctx.lineTo(cx + Math.cos(angle) * S * 0.30, cy + Math.sin(angle) * S * 0.28);
    ctx.stroke();
  }

  // Body — small rounded ellipse covers (64,64)
  ctx.fillStyle = '#0a0a0a';
  ctx.beginPath();
  ctx.ellipse(cx, cy, S * 0.14, S * 0.12, 0, 0, Math.PI * 2);
  ctx.fill();
  // Body highlight
  ctx.fillStyle = '#181818';
  ctx.beginPath();
  ctx.ellipse(cx - S * 0.03, cy - S * 0.03, S * 0.07, S * 0.05, -0.3, 0, Math.PI * 2);
  ctx.fill();

  // Mandibles — 2 short curved paths at front
  ctx.strokeStyle = '#2a2a2a'; ctx.lineWidth = S * 0.02;
  if (dir === 0) {
    ctx.beginPath(); ctx.moveTo(cx - S * 0.06, cy - S * 0.10); ctx.quadraticCurveTo(cx - S * 0.10, cy - S * 0.16, cx - S * 0.04, cy - S * 0.14); ctx.stroke();
    ctx.beginPath(); ctx.moveTo(cx + S * 0.06, cy - S * 0.10); ctx.quadraticCurveTo(cx + S * 0.10, cy - S * 0.16, cx + S * 0.04, cy - S * 0.14); ctx.stroke();
  } else if (dir === 1) {
    ctx.beginPath(); ctx.moveTo(cx + S * 0.10, cy - S * 0.04); ctx.quadraticCurveTo(cx + S * 0.16, cy - S * 0.08, cx + S * 0.14, cy + S * 0.02); ctx.stroke();
  } else {
    ctx.beginPath(); ctx.moveTo(cx - S * 0.06, cy + S * 0.10); ctx.quadraticCurveTo(cx - S * 0.10, cy + S * 0.16, cx - S * 0.04, cy + S * 0.14); ctx.stroke();
    ctx.beginPath(); ctx.moveTo(cx + S * 0.06, cy + S * 0.10); ctx.quadraticCurveTo(cx + S * 0.10, cy + S * 0.16, cx + S * 0.04, cy + S * 0.14); ctx.stroke();
  }

  // Eyes — 2 tiny team colour dots
  ctx.save();
  ctx.shadowColor = teamColor; ctx.shadowBlur = 4;
  ctx.fillStyle = hexToRgba(teamColor, 0.85);
  ctx.beginPath(); ctx.arc(cx - S * 0.04, cy - S * 0.02, S * 0.018, 0, Math.PI * 2); ctx.fill();
  ctx.beginPath(); ctx.arc(cx + S * 0.04, cy - S * 0.02, S * 0.018, 0, Math.PI * 2); ctx.fill();
  ctx.restore();
}

// drawInfestedTerran — Zombie Marine consumed by Zerg infestation. Humanoid but corrupted.
// Dir-3 mirrors dir-1.
function drawInfestedTerran(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawInfestedTerran(ctx, S, 1, teamColor); ctx.restore(); return;
  }
  const cx = S / 2, cy = S / 2;
  ctx.lineCap = 'round'; ctx.lineJoin = 'round';

  // Infestation tendrils — wavy green growths from torso (drawn behind body)
  ctx.strokeStyle = '#3a6a08'; ctx.lineWidth = S * 0.03;
  const tendrilData = [
    [-S * 0.16, -S * 0.02, -S * 0.28, S * 0.10, -S * 0.22, S * 0.18],
    [-S * 0.14, S * 0.06, -S * 0.30, S * 0.16, -S * 0.26, S * 0.26],
    [S * 0.16, -S * 0.02, S * 0.30, S * 0.08, S * 0.24, S * 0.18],
    [S * 0.12, S * 0.08, S * 0.26, S * 0.20, S * 0.18, S * 0.30],
  ];
  const drawCount = (dir === 0 || dir === 2) ? 4 : 2;
  for (let i = 0; i < drawCount; i++) {
    const [dx1, dy1, dx2, dy2, dx3, dy3] = tendrilData[i];
    ctx.beginPath();
    ctx.moveTo(cx + dx1, cy + dy1);
    ctx.quadraticCurveTo(cx + dx2, cy + dy2, cx + dx3, cy + dy3);
    ctx.stroke();
  }

  // Torso — degraded armour covers (64,64)
  ctx.fillStyle = '#1a2a1a';
  ctx.beginPath();
  ctx.roundRect(cx - S * 0.16, cy - S * 0.10, S * 0.32, S * 0.28, S * 0.04);
  ctx.fill();
  ctx.strokeStyle = '#0f1f0f'; ctx.lineWidth = S * 0.014;
  ctx.stroke();

  // Helmet — cracked/damaged version of Marine helmet
  ctx.fillStyle = '#1e2e1e';
  ctx.beginPath();
  ctx.arc(cx, cy - S * 0.18, S * 0.14, Math.PI, 0, false);
  ctx.closePath();
  ctx.fill();
  ctx.strokeStyle = '#0f1f0f'; ctx.lineWidth = S * 0.012;
  ctx.stroke();

  // Helmet crack
  ctx.strokeStyle = '#0a100a'; ctx.lineWidth = S * 0.018;
  ctx.beginPath();
  ctx.moveTo(cx - S * 0.04, cy - S * 0.28);
  ctx.lineTo(cx + S * 0.02, cy - S * 0.20);
  ctx.lineTo(cx - S * 0.01, cy - S * 0.14);
  ctx.stroke();

  // Zerg eye glow through visor in team colour
  ctx.save();
  ctx.shadowColor = teamColor; ctx.shadowBlur = 8;
  ctx.fillStyle = hexToRgba(teamColor, 0.90);
  if (dir === 0 || dir === 2) {
    ctx.fillRect(cx - S * 0.08, cy - S * 0.22, S * 0.16, S * 0.04);
  } else {
    ctx.fillRect(cx - S * 0.02, cy - S * 0.22, S * 0.12, S * 0.04);
  }
  ctx.restore();

  // One arm with grenade stub
  if (dir === 0 || dir === 1) {
    ctx.fillStyle = '#1a2a1a';
    ctx.beginPath();
    ctx.roundRect(
      dir === 1 ? cx + S * 0.12 : cx + S * 0.16,
      cy - S * 0.02, S * 0.10, S * 0.12, S * 0.02
    );
    ctx.fill();
    // Grenade
    ctx.fillStyle = '#3a6a08';
    ctx.beginPath();
    ctx.arc(
      dir === 1 ? cx + S * 0.26 : cx + S * 0.22,
      cy + S * 0.06, S * 0.04, 0, Math.PI * 2
    );
    ctx.fill();
  }

  // Legs — short stubs below torso
  ctx.fillStyle = '#111a11';
  ctx.beginPath(); ctx.roundRect(cx - S * 0.12, cy + S * 0.18, S * 0.09, S * 0.14, S * 0.02); ctx.fill();
  ctx.beginPath(); ctx.roundRect(cx + S * 0.03, cy + S * 0.18, S * 0.09, S * 0.14, S * 0.02); ctx.fill();
}

// drawChangeling — Zerg shapeshifting spy. Amorphous blob with shimmer.
// Dir-3 mirrors dir-1.
function drawChangeling(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawChangeling(ctx, S, 1, teamColor); ctx.restore(); return;
  }
  const cx = S / 2, cy = S / 2;
  ctx.lineCap = 'round'; ctx.lineJoin = 'round';

  // Solid dark base — ensures non-zero alpha at (64,64) in all cases
  ctx.fillStyle = '#0e0a12';
  ctx.beginPath();
  ctx.ellipse(cx, cy, S * 0.28, S * 0.22, 0, 0, Math.PI * 2);
  ctx.fill();

  // Amoeba-like irregular blob outline (bezier blob)
  ctx.fillStyle = '#16101e';
  ctx.beginPath();
  // Irregular blob roughly centred at (cx, cy)
  ctx.moveTo(cx, cy - S * 0.22);
  ctx.bezierCurveTo(cx + S * 0.18, cy - S * 0.26, cx + S * 0.32, cy - S * 0.08, cx + S * 0.28, cy + S * 0.06);
  ctx.bezierCurveTo(cx + S * 0.24, cy + S * 0.20, cx + S * 0.10, cy + S * 0.28, cx, cy + S * 0.24);
  ctx.bezierCurveTo(cx - S * 0.14, cy + S * 0.28, cx - S * 0.30, cy + S * 0.14, cx - S * 0.28, cy);
  ctx.bezierCurveTo(cx - S * 0.30, cy - S * 0.14, cx - S * 0.16, cy - S * 0.24, cx, cy - S * 0.22);
  ctx.closePath();
  ctx.fill();

  // Pseudopods — 3–4 rounded protrusions
  ctx.fillStyle = '#1e1428';
  const pseudoPods = [
    [cx + S * 0.24, cy - S * 0.18, S * 0.06, S * 0.05],
    [cx - S * 0.22, cy + S * 0.12, S * 0.05, S * 0.07],
    [cx + S * 0.10, cy + S * 0.24, S * 0.07, S * 0.05],
    [cx - S * 0.08, cy - S * 0.26, S * 0.05, S * 0.04],
  ];
  pseudoPods.forEach(([px, py, rx, ry]) => {
    ctx.beginPath();
    ctx.ellipse(px, py, rx, ry, 0, 0, Math.PI * 2);
    ctx.fill();
  });

  // Shimmer — radial gradient from team colour at centre to transparent
  const grd = ctx.createRadialGradient(cx, cy, S * 0.02, cx, cy, S * 0.26);
  grd.addColorStop(0, hexToRgba(teamColor, 0.35));
  grd.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.fillStyle = grd;
  ctx.beginPath();
  ctx.ellipse(cx, cy, S * 0.26, S * 0.22, 0, 0, Math.PI * 2);
  ctx.fill();

  // Eyes — two small team colour dots slightly off-centre
  ctx.save();
  ctx.shadowColor = teamColor; ctx.shadowBlur = 8;
  ctx.fillStyle = hexToRgba(teamColor, 0.90);
  ctx.beginPath(); ctx.arc(cx - S * 0.06, cy - S * 0.04, S * 0.022, 0, Math.PI * 2); ctx.fill();
  ctx.beginPath(); ctx.arc(cx + S * 0.06, cy - S * 0.04, S * 0.022, 0, Math.PI * 2); ctx.fill();
  ctx.restore();
}

// drawAutoTurret — Deployed Raven gun turret. Military dark grey, stationary.
// Dir-3 mirrors dir-1.
function drawAutoTurret(ctx, S, dir, teamColor) {
  if (dir === 3) {
    ctx.save(); ctx.translate(S, 0); ctx.scale(-1, 1);
    drawAutoTurret(ctx, S, 1, teamColor); ctx.restore(); return;
  }
  const cx = S / 2, cy = S / 2 + 4;
  ctx.lineCap = 'round'; ctx.lineJoin = 'round';

  // Mounting plate — flat octagonal base at canvas bottom-centre
  ctx.fillStyle = '#2a2a2a';
  const baseY = cy + S * 0.16;
  ctx.beginPath();
  ctx.moveTo(cx - S * 0.22, baseY - S * 0.03);
  ctx.lineTo(cx - S * 0.26, baseY + S * 0.01);
  ctx.lineTo(cx - S * 0.26, baseY + S * 0.05);
  ctx.lineTo(cx - S * 0.22, baseY + S * 0.09);
  ctx.lineTo(cx + S * 0.22, baseY + S * 0.09);
  ctx.lineTo(cx + S * 0.26, baseY + S * 0.05);
  ctx.lineTo(cx + S * 0.26, baseY + S * 0.01);
  ctx.lineTo(cx + S * 0.22, baseY - S * 0.03);
  ctx.closePath();
  ctx.fill();
  ctx.strokeStyle = '#1a1a1a'; ctx.lineWidth = S * 0.014;
  ctx.stroke();

  // Turret body — raised rounded box covers (64,64)
  ctx.fillStyle = '#3a3a3a';
  ctx.beginPath();
  ctx.roundRect(cx - S * 0.20, cy - S * 0.16, S * 0.40, S * 0.28, S * 0.04);
  ctx.fill();
  ctx.strokeStyle = '#252525'; ctx.lineWidth = S * 0.014;
  ctx.stroke();

  // Armour panel lines
  ctx.strokeStyle = '#282828'; ctx.lineWidth = S * 0.012;
  ctx.beginPath(); ctx.moveTo(cx - S * 0.20, cy); ctx.lineTo(cx + S * 0.20, cy); ctx.stroke();

  // Twin autocannon barrels
  ctx.fillStyle = '#222222';
  if (dir === 0) {
    // Barrels extend upward
    ctx.fillRect(cx - S * 0.08, cy - S * 0.38, S * 0.05, S * 0.24);
    ctx.fillRect(cx + S * 0.03, cy - S * 0.38, S * 0.05, S * 0.24);
    // Barrel tips — slightly wider
    ctx.fillStyle = '#1a1a1a';
    ctx.fillRect(cx - S * 0.09, cy - S * 0.40, S * 0.07, S * 0.04);
    ctx.fillRect(cx + S * 0.02, cy - S * 0.40, S * 0.07, S * 0.04);
  } else if (dir === 2) {
    // Barrels extend downward
    ctx.fillRect(cx - S * 0.08, cy + S * 0.14, S * 0.05, S * 0.22);
    ctx.fillRect(cx + S * 0.03, cy + S * 0.14, S * 0.05, S * 0.22);
    ctx.fillStyle = '#1a1a1a';
    ctx.fillRect(cx - S * 0.09, cy + S * 0.32, S * 0.07, S * 0.04);
    ctx.fillRect(cx + S * 0.02, cy + S * 0.32, S * 0.07, S * 0.04);
  } else {
    // dir === 1 — barrels extend to right
    ctx.fillRect(cx + S * 0.18, cy - S * 0.05, S * 0.24, S * 0.04);
    ctx.fillRect(cx + S * 0.18, cy + S * 0.02, S * 0.24, S * 0.04);
    ctx.fillStyle = '#1a1a1a';
    ctx.fillRect(cx + S * 0.38, cy - S * 0.06, S * 0.04, S * 0.06);
    ctx.fillRect(cx + S * 0.38, cy + S * 0.01, S * 0.04, S * 0.06);
  }

  // Sensor dome on turret top
  ctx.fillStyle = '#444444';
  ctx.beginPath();
  ctx.arc(cx, cy - S * 0.14, S * 0.08, Math.PI, 0, false);
  ctx.closePath();
  ctx.fill();
  ctx.strokeStyle = '#333'; ctx.lineWidth = S * 0.01;
  ctx.stroke();

  // Sensor dot — team colour
  ctx.save();
  ctx.shadowColor = teamColor; ctx.shadowBlur = 8;
  ctx.fillStyle = hexToRgba(teamColor, 0.95);
  ctx.beginPath();
  ctx.arc(cx, cy - S * 0.17, S * 0.028, 0, Math.PI * 2);
  ctx.fill();
  ctx.restore();
}

// ── Building draw functions ───────────────────────────────────────────────────

function drawNexus(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  const r = S * 0.37;
  ctx.beginPath();
  for (let i = 0; i < 8; i++) {
    const a = (i / 8) * Math.PI * 2 - Math.PI / 8;
    i === 0 ? ctx.moveTo(cx + r * Math.cos(a), cy + r * Math.sin(a))
            : ctx.lineTo(cx + r * Math.cos(a), cy + r * Math.sin(a));
  }
  ctx.closePath();
  const bg = ctx.createRadialGradient(cx, cy - S*0.05, 0, cx, cy, r);
  bg.addColorStop(0, '#3a6ab8');
  bg.addColorStop(0.65, '#1a3a6e');
  bg.addColorStop(1, '#0d1f3c');
  ctx.fillStyle = bg;
  ctx.fill();
  ctx.strokeStyle = '#dda020';
  ctx.lineWidth = S * 0.038;
  ctx.stroke();
  ctx.strokeStyle = '#ffcc44';
  ctx.lineWidth = S * 0.018;
  for (let i = 0; i < 4; i++) {
    const a = (i / 4) * Math.PI * 2;
    ctx.beginPath();
    ctx.moveTo(cx + r*0.28 * Math.cos(a), cy + r*0.28 * Math.sin(a));
    ctx.lineTo(cx + r*0.72 * Math.cos(a), cy + r*0.72 * Math.sin(a));
    ctx.stroke();
  }
  const dr = S * 0.13;
  ctx.beginPath();
  ctx.moveTo(cx, cy - dr);
  ctx.lineTo(cx + dr * 0.65, cy);
  ctx.lineTo(cx, cy + dr);
  ctx.lineTo(cx - dr * 0.65, cy);
  ctx.closePath();
  const cg = ctx.createRadialGradient(cx, cy, 0, cx, cy, dr);
  cg.addColorStop(0, '#ffffff');
  cg.addColorStop(0.4, '#88ccff');
  cg.addColorStop(1, '#2266cc');
  ctx.fillStyle = cg;
  ctx.fill();
  const hg = ctx.createRadialGradient(cx, cy, r*0.8, cx, cy, r*1.1);
  hg.addColorStop(0, 'rgba(80,140,255,0.2)');
  hg.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.beginPath();
  ctx.arc(cx, cy, r * 1.1, 0, Math.PI * 2);
  ctx.fillStyle = hg;
  ctx.fill();
}

function drawPylon(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  ctx.beginPath();
  ctx.arc(cx, cy, S*0.44, 0, Math.PI*2);
  ctx.strokeStyle = 'rgba(140,70,255,0.18)';
  ctx.lineWidth = S*0.035;
  ctx.stroke();
  ctx.beginPath();
  ctx.arc(cx, cy, S*0.35, 0, Math.PI*2);
  ctx.strokeStyle = 'rgba(140,70,255,0.28)';
  ctx.lineWidth = S*0.025;
  ctx.stroke();
  ctx.beginPath();
  const br = S * 0.22;
  for (let i = 0; i < 6; i++) {
    const a = (i / 6) * Math.PI * 2 - Math.PI/6;
    i === 0 ? ctx.moveTo(cx + br * Math.cos(a), cy + S*0.1 + br * 0.5 * Math.sin(a))
            : ctx.lineTo(cx + br * Math.cos(a), cy + S*0.1 + br * 0.5 * Math.sin(a));
  }
  ctx.closePath();
  ctx.fillStyle = '#2a1a4e';
  ctx.fill();
  ctx.strokeStyle = '#7744cc';
  ctx.lineWidth = S*0.022;
  ctx.stroke();
  ctx.beginPath();
  ctx.moveTo(cx, cy - S*0.4);
  ctx.lineTo(cx + S*0.07, cy - S*0.08);
  ctx.lineTo(cx + S*0.05, cy + S*0.12);
  ctx.lineTo(cx - S*0.05, cy + S*0.12);
  ctx.lineTo(cx - S*0.07, cy - S*0.08);
  ctx.closePath();
  const sg = ctx.createLinearGradient(cx - S*0.07, 0, cx + S*0.07, 0);
  sg.addColorStop(0, '#5533aa');
  sg.addColorStop(0.5, '#9966dd');
  sg.addColorStop(1, '#5533aa');
  ctx.fillStyle = sg;
  ctx.fill();
  ctx.strokeStyle = '#aa77ff';
  ctx.lineWidth = S*0.015;
  ctx.stroke();
  ctx.beginPath();
  ctx.arc(cx, cy - S*0.36, S*0.07, 0, Math.PI*2);
  const og = ctx.createRadialGradient(cx, cy - S*0.36, 0, cx, cy - S*0.36, S*0.07);
  og.addColorStop(0, '#ffffff');
  og.addColorStop(0.45, '#dd99ff');
  og.addColorStop(1, 'rgba(180,100,255,0)');
  ctx.fillStyle = og;
  ctx.fill();
}

function drawGateway(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Main body between pillars
  ctx.fillStyle = '#122840';
  ctx.fillRect(cx - S*0.18, S*0.06, S*0.36, S*0.88);
  // Rune lines on body
  ctx.strokeStyle = '#1e4a70';
  ctx.lineWidth = S*0.013;
  [S*0.2, S*0.36, S*0.5].forEach(ry => {
    ctx.beginPath();
    ctx.moveTo(cx - S*0.14, ry);
    ctx.lineTo(cx + S*0.14, ry);
    ctx.stroke();
  });
  // Arch opening in lower half (center at cy+S*0.22 keeps (cx,cy) safely outside)
  ctx.beginPath();
  ctx.arc(cx, cy + S*0.22, S*0.14, Math.PI, 0);
  ctx.lineTo(cx + S*0.14, S*0.94);
  ctx.lineTo(cx - S*0.14, S*0.94);
  ctx.closePath();
  ctx.fillStyle = '#040810';
  ctx.fill();
  const ag = ctx.createRadialGradient(cx, cy + S*0.22, 0, cx, cy + S*0.22, S*0.15);
  ag.addColorStop(0, 'rgba(80,180,255,0.35)');
  ag.addColorStop(1, 'rgba(0,40,100,0)');
  ctx.beginPath();
  ctx.arc(cx, cy + S*0.22, S*0.15, 0, Math.PI*2);
  ctx.fillStyle = ag;
  ctx.fill();
  // Two flanking pillars on top of body
  [cx - S*0.3, cx + S*0.18].forEach(px => {
    const pg = ctx.createLinearGradient(px, 0, px + S*0.12, 0);
    pg.addColorStop(0, '#2a5580');
    pg.addColorStop(0.5, '#3a6a99');
    pg.addColorStop(1, '#2a5580');
    ctx.fillStyle = pg;
    ctx.fillRect(px, S*0.06, S*0.12, S*0.88);
    ctx.strokeStyle = '#4488bb';
    ctx.lineWidth = S*0.02;
    ctx.strokeRect(px, S*0.06, S*0.12, S*0.88);
    ctx.beginPath();
    ctx.arc(px + S*0.06, S*0.16, S*0.04, 0, Math.PI*2);
    ctx.fillStyle = '#88ddff';
    ctx.fill();
  });
  // Gold trim at top
  ctx.strokeStyle = '#dda020';
  ctx.lineWidth = S*0.025;
  ctx.beginPath();
  ctx.moveTo(cx - S*0.3, S*0.2);
  ctx.lineTo(cx + S*0.3, S*0.2);
  ctx.stroke();
}

function drawCyberneticsCore(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  const m = S*0.1;
  ctx.fillStyle = '#0e2040';
  ctx.fillRect(m, m, S - m*2, S - m*2);
  ctx.strokeStyle = '#336699';
  ctx.lineWidth = S*0.028;
  ctx.strokeRect(m, m, S - m*2, S - m*2);
  ctx.strokeStyle = '#2a5580';
  ctx.lineWidth = S*0.013;
  [0.32, 0.5, 0.68].forEach(t => {
    ctx.beginPath();
    ctx.moveTo(m + S*0.04, m + (S - m*2)*t);
    ctx.lineTo(S - m - S*0.04, m + (S - m*2)*t);
    ctx.stroke();
  });
  [0.32, 0.68].forEach(t => {
    ctx.beginPath();
    ctx.moveTo(m + (S - m*2)*t, m + S*0.04);
    ctx.lineTo(m + (S - m*2)*t, S - m - S*0.04);
    ctx.stroke();
  });
  const inset = m + S*0.05;
  [[inset, inset],[S - inset, inset],[inset, S - inset],[S - inset, S - inset]].forEach(([nx, ny]) => {
    ctx.beginPath();
    ctx.arc(nx, ny, S*0.038, 0, Math.PI*2);
    ctx.fillStyle = '#4499cc';
    ctx.fill();
    ctx.strokeStyle = '#dda020';
    ctx.lineWidth = S*0.02;
    ctx.stroke();
  });
  ctx.beginPath();
  ctx.arc(cx, cy, S*0.11, 0, Math.PI*2);
  const eg = ctx.createRadialGradient(cx, cy, 0, cx, cy, S*0.11);
  eg.addColorStop(0, '#ffffff');
  eg.addColorStop(0.5, '#66ccff');
  eg.addColorStop(1, 'rgba(40,120,200,0)');
  ctx.fillStyle = eg;
  ctx.fill();
  ctx.beginPath();
  ctx.arc(cx, cy, S*0.16, 0, Math.PI*2);
  ctx.strokeStyle = 'rgba(100,200,255,0.4)';
  ctx.lineWidth = S*0.02;
  ctx.stroke();
}

function drawAssimilator(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S*0.58;
  ctx.fillStyle = '#1a3322';
  ctx.fillRect(S*0.12, cy + S*0.1, S*0.76, S*0.15);
  ctx.strokeStyle = '#2a5533';
  ctx.lineWidth = S*0.015;
  ctx.strokeRect(S*0.12, cy + S*0.1, S*0.76, S*0.15);
  ctx.beginPath();
  ctx.ellipse(cx, cy, S*0.34, S*0.26, 0, Math.PI, 0);
  ctx.lineTo(cx + S*0.34, cy);
  ctx.lineTo(cx - S*0.34, cy);
  ctx.closePath();
  const dg = ctx.createRadialGradient(cx, cy - S*0.06, 0, cx, cy, S*0.34);
  dg.addColorStop(0, '#3a6650');
  dg.addColorStop(0.6, '#1a4433');
  dg.addColorStop(1, '#0d2219');
  ctx.fillStyle = dg;
  ctx.fill();
  ctx.strokeStyle = '#44aa77';
  ctx.lineWidth = S*0.025;
  ctx.beginPath();
  ctx.ellipse(cx, cy, S*0.34, S*0.26, 0, Math.PI, 0);
  ctx.stroke();
  [-S*0.14, 0, S*0.14].forEach(ox => {
    ctx.fillStyle = '#2a5540';
    ctx.fillRect(cx + ox - S*0.028, cy - S*0.38, S*0.056, S*0.14);
    ctx.beginPath();
    ctx.arc(cx + ox, cy - S*0.34, S*0.038, 0, Math.PI*2);
    ctx.fillStyle = 'rgba(100,220,140,0.55)';
    ctx.fill();
  });
  ctx.beginPath();
  ctx.arc(cx, cy - S*0.1, S*0.09, 0, Math.PI*2);
  const wg = ctx.createRadialGradient(cx, cy - S*0.1, 0, cx, cy - S*0.1, S*0.09);
  wg.addColorStop(0, 'rgba(210,255,230,0.85)');
  wg.addColorStop(0.7, 'rgba(60,180,100,0.4)');
  wg.addColorStop(1, 'rgba(20,80,40,0.1)');
  ctx.fillStyle = wg;
  ctx.fill();
}

function drawRoboticsFacility(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  const bg = ctx.createLinearGradient(S*0.06, S*0.22, S*0.06, S*0.78);
  bg.addColorStop(0, '#1e3a55');
  bg.addColorStop(1, '#0c1e2e');
  ctx.fillStyle = bg;
  ctx.fillRect(S*0.06, S*0.22, S*0.88, S*0.52);
  ctx.strokeStyle = '#2a5577';
  ctx.lineWidth = S*0.026;
  ctx.strokeRect(S*0.06, S*0.22, S*0.88, S*0.52);
  ctx.fillStyle = '#264d6e';
  ctx.fillRect(S*0.06, S*0.22, S*0.88, S*0.08);
  ctx.strokeStyle = '#dda020';
  ctx.lineWidth = S*0.02;
  ctx.beginPath();
  ctx.moveTo(S*0.06, S*0.3);
  ctx.lineTo(S*0.94, S*0.3);
  ctx.stroke();
  ctx.strokeStyle = '#4499bb';
  ctx.lineWidth = S*0.038;
  ctx.lineCap = 'round';
  ctx.beginPath();
  ctx.moveTo(cx - S*0.05, S*0.3);
  ctx.lineTo(cx + S*0.18, S*0.42);
  ctx.lineTo(cx + S*0.26, S*0.55);
  ctx.stroke();
  ctx.lineCap = 'butt';
  ctx.beginPath();
  ctx.arc(cx + S*0.26, S*0.55, S*0.048, 0, Math.PI*2);
  ctx.fillStyle = '#66bbdd';
  ctx.fill();
  ctx.strokeStyle = '#88ccee';
  ctx.lineWidth = S*0.015;
  ctx.stroke();
  [0.15, 0.32, 0.52, 0.70].forEach(t => {
    ctx.fillStyle = '#0a1520';
    ctx.fillRect(S*0.06 + S*0.88*t, S*0.57, S*0.048, S*0.12);
  });
  [S*0.16, S*0.5, S*0.82].forEach(px => {
    ctx.beginPath();
    ctx.arc(px, S*0.27, S*0.022, 0, Math.PI*2);
    ctx.fillStyle = '#44ffaa';
    ctx.fill();
  });
}

function drawStargate(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  ctx.beginPath();
  ctx.arc(cx, cy, S*0.46, 0, Math.PI*2);
  ctx.fillStyle = 'rgba(180,130,30,0.12)';
  ctx.fill();
  ctx.beginPath();
  ctx.arc(cx, cy, S*0.39, 0, Math.PI*2);
  ctx.strokeStyle = '#cc8822';
  ctx.lineWidth = S*0.072;
  ctx.stroke();
  ctx.beginPath();
  ctx.arc(cx, cy, S*0.39, 0, Math.PI*2);
  ctx.strokeStyle = 'rgba(255,200,80,0.35)';
  ctx.lineWidth = S*0.025;
  ctx.stroke();
  ctx.beginPath();
  ctx.arc(cx, cy, S*0.21, 0, Math.PI*2);
  ctx.strokeStyle = '#ffcc44';
  ctx.lineWidth = S*0.028;
  ctx.stroke();
  for (let i = 0; i < 4; i++) {
    const a = (i / 4) * Math.PI * 2;
    ctx.beginPath();
    ctx.moveTo(cx + Math.cos(a)*S*0.21, cy + Math.sin(a)*S*0.21);
    ctx.lineTo(cx + Math.cos(a)*S*0.35, cy + Math.sin(a)*S*0.35);
    ctx.strokeStyle = '#dda020';
    ctx.lineWidth = S*0.042;
    ctx.stroke();
  }
  for (let i = 0; i < 4; i++) {
    const a = (i / 4) * Math.PI * 2 + Math.PI/4;
    const nx = cx + Math.cos(a)*S*0.39, ny = cy + Math.sin(a)*S*0.39;
    ctx.beginPath();
    ctx.arc(nx, ny, S*0.055, 0, Math.PI*2);
    ctx.fillStyle = '#1a0d22';
    ctx.fill();
    ctx.strokeStyle = '#bb8800';
    ctx.lineWidth = S*0.018;
    ctx.stroke();
  }
  ctx.beginPath();
  ctx.arc(cx, cy, S*0.185, 0, Math.PI*2);
  const pg = ctx.createRadialGradient(cx, cy, 0, cx, cy, S*0.185);
  pg.addColorStop(0, 'rgba(255,255,255,0.92)');
  pg.addColorStop(0.35, 'rgba(190,225,255,0.75)');
  pg.addColorStop(0.7, 'rgba(100,180,255,0.4)');
  pg.addColorStop(1, 'rgba(50,100,200,0.08)');
  ctx.fillStyle = pg;
  ctx.fill();
}

function drawForge(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  const bg = ctx.createLinearGradient(S*0.1, S*0.28, S*0.1, S*0.8);
  bg.addColorStop(0, '#1a2a3e');
  bg.addColorStop(1, '#0c1520');
  ctx.fillStyle = bg;
  ctx.fillRect(S*0.1, S*0.28, S*0.8, S*0.5);
  ctx.strokeStyle = '#2a4466';
  ctx.lineWidth = S*0.03;
  ctx.strokeRect(S*0.1, S*0.28, S*0.8, S*0.5);
  ctx.fillStyle = '#1e2e44';
  ctx.fillRect(cx - S*0.12, S*0.1, S*0.24, S*0.22);
  ctx.strokeStyle = '#335577';
  ctx.lineWidth = S*0.02;
  ctx.strokeRect(cx - S*0.12, S*0.1, S*0.24, S*0.22);
  ctx.fillStyle = '#253d55';
  ctx.fillRect(cx - S*0.15, S*0.08, S*0.3, S*0.05);
  ctx.beginPath();
  ctx.arc(cx, S*0.78, S*0.28, 0, Math.PI*2);
  const hg = ctx.createRadialGradient(cx, S*0.78, 0, cx, S*0.78, S*0.28);
  hg.addColorStop(0, 'rgba(255,160,30,0.75)');
  hg.addColorStop(0.4, 'rgba(210,70,10,0.35)');
  hg.addColorStop(1, 'rgba(120,20,0,0)');
  ctx.fillStyle = hg;
  ctx.fill();
  ctx.beginPath();
  ctx.arc(cx, S*0.14, S*0.042, 0, Math.PI*2);
  ctx.fillStyle = 'rgba(255,150,30,0.65)';
  ctx.fill();
  ctx.strokeStyle = '#dda020';
  ctx.lineWidth = S*0.022;
  ctx.beginPath();
  ctx.moveTo(S*0.1, S*0.38);
  ctx.lineTo(S*0.9, S*0.38);
  ctx.stroke();
  [S*0.16, S*0.42, S*0.64].forEach(px => {
    ctx.fillStyle = '#0a1218';
    ctx.fillRect(px, S*0.42, S*0.14, S*0.24);
    ctx.strokeStyle = '#2255aa';
    ctx.lineWidth = S*0.012;
    ctx.strokeRect(px, S*0.42, S*0.14, S*0.24);
  });
}

function drawTwilightCouncil(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  [cx - S*0.3, cx + S*0.18].forEach(tx => {
    ctx.fillStyle = '#1a0d33';
    ctx.fillRect(tx, S*0.3, S*0.12, S*0.58);
    ctx.strokeStyle = '#553399';
    ctx.lineWidth = S*0.015;
    ctx.strokeRect(tx, S*0.3, S*0.12, S*0.58);
    ctx.beginPath();
    ctx.moveTo(tx + S*0.06, S*0.22);
    ctx.lineTo(tx + S*0.12, S*0.3);
    ctx.lineTo(tx, S*0.3);
    ctx.closePath();
    ctx.fillStyle = '#6633aa';
    ctx.fill();
    ctx.beginPath();
    ctx.arc(tx + S*0.06, S*0.48, S*0.03, 0, Math.PI*2);
    ctx.fillStyle = 'rgba(200,100,255,0.6)';
    ctx.fill();
  });
  const sg = ctx.createLinearGradient(cx - S*0.2, 0, cx + S*0.2, 0);
  sg.addColorStop(0, '#2a1550');
  sg.addColorStop(0.5, '#1a0d38');
  sg.addColorStop(1, '#2a1550');
  ctx.beginPath();
  ctx.moveTo(cx, S*0.04);
  ctx.lineTo(cx + S*0.18, S*0.28);
  ctx.lineTo(cx + S*0.2, S*0.88);
  ctx.lineTo(cx - S*0.2, S*0.88);
  ctx.lineTo(cx - S*0.18, S*0.28);
  ctx.closePath();
  ctx.fillStyle = sg;
  ctx.fill();
  ctx.strokeStyle = '#7733cc';
  ctx.lineWidth = S*0.028;
  ctx.stroke();
  ctx.strokeStyle = 'rgba(210,110,255,0.55)';
  ctx.lineWidth = S*0.011;
  [S*0.4, S*0.54, S*0.68].forEach(ry => {
    ctx.beginPath();
    ctx.moveTo(cx - S*0.13, ry);
    ctx.lineTo(cx + S*0.13, ry);
    ctx.stroke();
  });
  ctx.beginPath();
  ctx.arc(cx, S*0.09, S*0.075, 0, Math.PI*2);
  const mg = ctx.createRadialGradient(cx, S*0.09, 0, cx, S*0.09, S*0.075);
  mg.addColorStop(0, '#ffffff');
  mg.addColorStop(0.4, '#dd99ff');
  mg.addColorStop(1, 'rgba(160,60,255,0)');
  ctx.fillStyle = mg;
  ctx.fill();
  ctx.beginPath();
  ctx.arc(cx, S*0.09, S*0.13, 0, Math.PI*2);
  ctx.strokeStyle = 'rgba(180,70,255,0.28)';
  ctx.lineWidth = S*0.02;
  ctx.stroke();
}

// ── New Protoss building draw functions ──────────────────────────────────────

function drawPhotonCannon(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Base platform
  ctx.fillStyle = '#1a2a40';
  ctx.beginPath();
  ctx.ellipse(cx, cy + S*0.28, S*0.28, S*0.1, 0, 0, Math.PI*2);
  ctx.fill();
  // Cannon barrel
  ctx.fillStyle = '#2a4466';
  ctx.fillRect(cx - S*0.06, S*0.12, S*0.12, S*0.52);
  ctx.strokeStyle = '#4488bb';
  ctx.lineWidth = S*0.02;
  ctx.strokeRect(cx - S*0.06, S*0.12, S*0.12, S*0.52);
  // Barrel tip
  ctx.fillStyle = '#1a3355';
  ctx.fillRect(cx - S*0.09, S*0.08, S*0.18, S*0.1);
  // Energy crystal
  const cg = ctx.createRadialGradient(cx, cy - S*0.05, 0, cx, cy - S*0.05, S*0.11);
  cg.addColorStop(0, '#ffffff');
  cg.addColorStop(0.4, '#88ccff');
  cg.addColorStop(1, 'rgba(50,150,255,0)');
  ctx.beginPath();
  ctx.arc(cx, cy - S*0.05, S*0.11, 0, Math.PI*2);
  ctx.fillStyle = cg;
  ctx.fill();
  // Gold trim
  ctx.strokeStyle = '#dda020';
  ctx.lineWidth = S*0.022;
  ctx.beginPath();
  ctx.moveTo(cx - S*0.06, cy + S*0.1);
  ctx.lineTo(cx + S*0.06, cy + S*0.1);
  ctx.stroke();
}

function drawShieldBattery(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Rounded shield-shaped body
  ctx.beginPath();
  ctx.ellipse(cx, cy, S*0.33, S*0.38, 0, 0, Math.PI*2);
  const bg = ctx.createRadialGradient(cx, cy - S*0.06, 0, cx, cy, S*0.38);
  bg.addColorStop(0, '#2a4888');
  bg.addColorStop(0.6, '#1a2a55');
  bg.addColorStop(1, '#0d1530');
  ctx.fillStyle = bg;
  ctx.fill();
  ctx.strokeStyle = '#5577cc';
  ctx.lineWidth = S*0.03;
  ctx.stroke();
  // Shield symbol
  ctx.beginPath();
  ctx.moveTo(cx, cy - S*0.22);
  ctx.lineTo(cx + S*0.18, cy - S*0.06);
  ctx.lineTo(cx + S*0.18, cy + S*0.08);
  ctx.lineTo(cx, cy + S*0.22);
  ctx.lineTo(cx - S*0.18, cy + S*0.08);
  ctx.lineTo(cx - S*0.18, cy - S*0.06);
  ctx.closePath();
  ctx.strokeStyle = '#88aaff';
  ctx.lineWidth = S*0.025;
  ctx.stroke();
  // Inner glow
  const ig = ctx.createRadialGradient(cx, cy, 0, cx, cy, S*0.2);
  ig.addColorStop(0, 'rgba(150,180,255,0.5)');
  ig.addColorStop(1, 'rgba(50,80,200,0)');
  ctx.beginPath();
  ctx.arc(cx, cy, S*0.2, 0, Math.PI*2);
  ctx.fillStyle = ig;
  ctx.fill();
}

function drawDarkShrine(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Dark angular base
  ctx.beginPath();
  ctx.moveTo(cx - S*0.32, S*0.82);
  ctx.lineTo(cx + S*0.32, S*0.82);
  ctx.lineTo(cx + S*0.22, S*0.55);
  ctx.lineTo(cx - S*0.22, S*0.55);
  ctx.closePath();
  ctx.fillStyle = '#0d0518';
  ctx.fill();
  ctx.strokeStyle = '#6611aa';
  ctx.lineWidth = S*0.022;
  ctx.stroke();
  // Central void spire
  ctx.beginPath();
  ctx.moveTo(cx, S*0.06);
  ctx.lineTo(cx + S*0.14, S*0.55);
  ctx.lineTo(cx - S*0.14, S*0.55);
  ctx.closePath();
  const sg = ctx.createLinearGradient(cx, S*0.06, cx, S*0.55);
  sg.addColorStop(0, '#330055');
  sg.addColorStop(0.5, '#220033');
  sg.addColorStop(1, '#110022');
  ctx.fillStyle = sg;
  ctx.fill();
  ctx.strokeStyle = '#9922cc';
  ctx.lineWidth = S*0.02;
  ctx.stroke();
  // Two flanking void crystals
  [cx - S*0.2, cx + S*0.2].forEach(px => {
    ctx.beginPath();
    ctx.moveTo(px, S*0.18);
    ctx.lineTo(px + S*0.07, S*0.48);
    ctx.lineTo(px - S*0.07, S*0.48);
    ctx.closePath();
    ctx.fillStyle = '#1a0033';
    ctx.fill();
    ctx.strokeStyle = '#7700bb';
    ctx.lineWidth = S*0.015;
    ctx.stroke();
  });
  // Void glow at apex
  const vg = ctx.createRadialGradient(cx, S*0.1, 0, cx, S*0.1, S*0.1);
  vg.addColorStop(0, 'rgba(180,50,255,0.9)');
  vg.addColorStop(0.5, 'rgba(100,0,200,0.4)');
  vg.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.beginPath();
  ctx.arc(cx, S*0.1, S*0.1, 0, Math.PI*2);
  ctx.fillStyle = vg;
  ctx.fill();
}

function drawTemplarArchives(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Grand ornate base with steps
  ctx.fillStyle = '#1a2a3e';
  ctx.fillRect(S*0.08, S*0.72, S*0.84, S*0.14);
  ctx.fillStyle = '#1e3450';
  ctx.fillRect(S*0.14, S*0.62, S*0.72, S*0.12);
  // Main hall
  const bg = ctx.createLinearGradient(cx, S*0.12, cx, S*0.64);
  bg.addColorStop(0, '#2a4870');
  bg.addColorStop(1, '#163050');
  ctx.fillStyle = bg;
  ctx.fillRect(S*0.18, S*0.24, S*0.64, S*0.4);
  // Arched roof
  ctx.beginPath();
  ctx.moveTo(S*0.18, S*0.24);
  ctx.lineTo(cx, S*0.1);
  ctx.lineTo(S*0.82, S*0.24);
  ctx.closePath();
  ctx.fillStyle = '#336699';
  ctx.fill();
  ctx.strokeStyle = '#dda020';
  ctx.lineWidth = S*0.022;
  ctx.stroke();
  // Four pillars
  [S*0.22, S*0.35, S*0.54, S*0.67].forEach(px => {
    ctx.fillStyle = '#3a5a88';
    ctx.fillRect(px, S*0.24, S*0.07, S*0.38);
    ctx.strokeStyle = '#5588bb';
    ctx.lineWidth = S*0.01;
    ctx.strokeRect(px, S*0.24, S*0.07, S*0.38);
  });
  // Gold trim
  ctx.strokeStyle = '#dda020';
  ctx.lineWidth = S*0.02;
  ctx.beginPath();
  ctx.moveTo(S*0.18, S*0.24);
  ctx.lineTo(S*0.82, S*0.24);
  ctx.stroke();
  // Central khaydarin crystal
  const cg = ctx.createRadialGradient(cx, cy, 0, cx, cy, S*0.08);
  cg.addColorStop(0, '#ffffff');
  cg.addColorStop(0.5, '#aaddff');
  cg.addColorStop(1, 'rgba(100,200,255,0)');
  ctx.beginPath();
  ctx.arc(cx, cy, S*0.08, 0, Math.PI*2);
  ctx.fillStyle = cg;
  ctx.fill();
}

function drawFleetBeacon(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Signal rings emanating outward
  [S*0.46, S*0.38, S*0.28].forEach((r, i) => {
    ctx.beginPath();
    ctx.arc(cx, cy, r, 0, Math.PI*2);
    ctx.strokeStyle = `rgba(100,200,255,${0.15 + i*0.1})`;
    ctx.lineWidth = S*0.018;
    ctx.stroke();
  });
  // Beacon tower body
  ctx.beginPath();
  ctx.moveTo(cx, S*0.06);
  ctx.lineTo(cx + S*0.08, S*0.45);
  ctx.lineTo(cx + S*0.16, S*0.82);
  ctx.lineTo(cx - S*0.16, S*0.82);
  ctx.lineTo(cx - S*0.08, S*0.45);
  ctx.closePath();
  const bg = ctx.createLinearGradient(cx - S*0.16, 0, cx + S*0.16, 0);
  bg.addColorStop(0, '#1a3a55');
  bg.addColorStop(0.5, '#2a5588');
  bg.addColorStop(1, '#1a3a55');
  ctx.fillStyle = bg;
  ctx.fill();
  ctx.strokeStyle = '#4488bb';
  ctx.lineWidth = S*0.02;
  ctx.stroke();
  // Apex beacon light
  const ag = ctx.createRadialGradient(cx, S*0.1, 0, cx, S*0.1, S*0.12);
  ag.addColorStop(0, '#ffffff');
  ag.addColorStop(0.3, '#88ddff');
  ag.addColorStop(0.7, 'rgba(100,200,255,0.3)');
  ag.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.beginPath();
  ctx.arc(cx, S*0.1, S*0.12, 0, Math.PI*2);
  ctx.fillStyle = ag;
  ctx.fill();
  // Gold trim bands
  ctx.strokeStyle = '#dda020';
  ctx.lineWidth = S*0.02;
  [S*0.35, S*0.55, S*0.72].forEach(ry => {
    ctx.beginPath();
    ctx.moveTo(cx - S*0.08 - (ry - S*0.45)*0.18, ry);
    ctx.lineTo(cx + S*0.08 + (ry - S*0.45)*0.18, ry);
    ctx.stroke();
  });
}

function drawRoboticsBay(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Compact tech cube body
  const bg = ctx.createLinearGradient(S*0.1, S*0.18, S*0.1, S*0.82);
  bg.addColorStop(0, '#1e3a55');
  bg.addColorStop(1, '#0c1e2e');
  ctx.fillStyle = bg;
  ctx.fillRect(S*0.1, S*0.18, S*0.8, S*0.62);
  ctx.strokeStyle = '#2a5577';
  ctx.lineWidth = S*0.025;
  ctx.strokeRect(S*0.1, S*0.18, S*0.8, S*0.62);
  // Tech dome on top
  ctx.beginPath();
  ctx.ellipse(cx, S*0.18, S*0.3, S*0.12, 0, Math.PI, 0);
  ctx.fillStyle = '#264d6e';
  ctx.fill();
  ctx.strokeStyle = '#3a6a99';
  ctx.lineWidth = S*0.018;
  ctx.stroke();
  // Grid panels
  ctx.strokeStyle = '#1a3a55';
  ctx.lineWidth = S*0.012;
  [S*0.3, S*0.5, S*0.7].forEach(px => {
    ctx.beginPath();
    ctx.moveTo(px, S*0.22);
    ctx.lineTo(px, S*0.76);
    ctx.stroke();
  });
  [S*0.34, S*0.5, S*0.66].forEach(ry => {
    ctx.beginPath();
    ctx.moveTo(S*0.14, ry);
    ctx.lineTo(S*0.86, ry);
    ctx.stroke();
  });
  // Central scanning display
  const eg = ctx.createRadialGradient(cx, cy + S*0.05, 0, cx, cy + S*0.05, S*0.12);
  eg.addColorStop(0, '#88eeff');
  eg.addColorStop(0.5, 'rgba(100,200,220,0.4)');
  eg.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.beginPath();
  ctx.arc(cx, cy + S*0.05, S*0.12, 0, Math.PI*2);
  ctx.fillStyle = eg;
  ctx.fill();
  // Gold corner accents
  ctx.strokeStyle = '#dda020';
  ctx.lineWidth = S*0.02;
  [[S*0.1, S*0.18],[S*0.9, S*0.18],[S*0.1, S*0.8],[S*0.9, S*0.8]].forEach(([nx, ny]) => {
    ctx.beginPath();
    ctx.arc(nx, ny, S*0.05, 0, Math.PI*2);
    ctx.stroke();
  });
}

// ── Terran building draw functions ───────────────────────────────────────────

function drawCommandCenter(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Large imposing base
  const bg = ctx.createLinearGradient(cx, S*0.15, cx, S*0.85);
  bg.addColorStop(0, '#4a4a4a');
  bg.addColorStop(0.5, '#333333');
  bg.addColorStop(1, '#1e1e1e');
  ctx.fillStyle = bg;
  ctx.fillRect(S*0.08, S*0.28, S*0.84, S*0.55);
  ctx.strokeStyle = '#666666';
  ctx.lineWidth = S*0.028;
  ctx.strokeRect(S*0.08, S*0.28, S*0.84, S*0.55);
  // Upper structure/bridge
  ctx.fillStyle = '#444444';
  ctx.fillRect(S*0.18, S*0.12, S*0.64, S*0.2);
  ctx.strokeStyle = '#777777';
  ctx.lineWidth = S*0.02;
  ctx.strokeRect(S*0.18, S*0.12, S*0.64, S*0.2);
  // Windows/sensors
  [S*0.28, S*0.48, S*0.68].forEach(px => {
    ctx.fillStyle = '#88ccff';
    ctx.fillRect(px, S*0.16, S*0.08, S*0.1);
  });
  // Blue status light
  const bl = ctx.createRadialGradient(cx, S*0.19, 0, cx, S*0.19, S*0.06);
  bl.addColorStop(0, '#aaddff');
  bl.addColorStop(1, 'rgba(100,180,255,0)');
  ctx.beginPath();
  ctx.arc(cx, S*0.19, S*0.06, 0, Math.PI*2);
  ctx.fillStyle = bl;
  ctx.fill();
  // Bottom vents
  [S*0.18, S*0.32, S*0.46, S*0.60, S*0.72].forEach(px => {
    ctx.fillStyle = '#111111';
    ctx.fillRect(px, S*0.66, S*0.08, S*0.1);
  });
  // Red Terran accent
  ctx.fillStyle = '#cc2222';
  ctx.fillRect(S*0.08, S*0.28, S*0.84, S*0.04);
}

function drawOrbitalCommand(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // CC body
  const bg = ctx.createLinearGradient(cx, S*0.2, cx, S*0.85);
  bg.addColorStop(0, '#4a4a55');
  bg.addColorStop(1, '#252530');
  ctx.fillStyle = bg;
  ctx.fillRect(S*0.1, S*0.3, S*0.8, S*0.52);
  ctx.strokeStyle = '#6666aa';
  ctx.lineWidth = S*0.025;
  ctx.strokeRect(S*0.1, S*0.3, S*0.8, S*0.52);
  // Scanner dish on top
  ctx.beginPath();
  ctx.ellipse(cx + S*0.15, S*0.24, S*0.2, S*0.08, -0.3, 0, Math.PI*2);
  ctx.fillStyle = '#3a3a55';
  ctx.fill();
  ctx.strokeStyle = '#6666cc';
  ctx.lineWidth = S*0.018;
  ctx.stroke();
  // Dish arm
  ctx.strokeStyle = '#5555aa';
  ctx.lineWidth = S*0.03;
  ctx.beginPath();
  ctx.moveTo(cx, S*0.3);
  ctx.lineTo(cx + S*0.15, S*0.24);
  ctx.stroke();
  // Blue scanner glow
  const sg = ctx.createRadialGradient(cx + S*0.15, S*0.24, 0, cx + S*0.15, S*0.24, S*0.15);
  sg.addColorStop(0, 'rgba(100,150,255,0.6)');
  sg.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.beginPath();
  ctx.arc(cx + S*0.15, S*0.24, S*0.15, 0, Math.PI*2);
  ctx.fillStyle = sg;
  ctx.fill();
  // Windows
  [S*0.22, S*0.44, S*0.66].forEach(px => {
    ctx.fillStyle = '#88aaff';
    ctx.fillRect(px, S*0.38, S*0.1, S*0.1);
  });
  ctx.fillStyle = '#cc2222';
  ctx.fillRect(S*0.1, S*0.3, S*0.8, S*0.035);
}

function drawPlanetaryFortress(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Heavy armored base
  const bg = ctx.createLinearGradient(cx, S*0.2, cx, S*0.9);
  bg.addColorStop(0, '#3a3a3a');
  bg.addColorStop(1, '#111111');
  ctx.fillStyle = bg;
  ctx.beginPath();
  ctx.roundRect(S*0.06, S*0.25, S*0.88, S*0.6, S*0.05);
  ctx.fill();
  ctx.strokeStyle = '#555555';
  ctx.lineWidth = S*0.03;
  ctx.stroke();
  // Two heavy cannons
  [cx - S*0.22, cx + S*0.22].forEach(gx => {
    ctx.fillStyle = '#2a2a2a';
    ctx.fillRect(gx - S*0.06, S*0.12, S*0.12, S*0.3);
    ctx.strokeStyle = '#888888';
    ctx.lineWidth = S*0.018;
    ctx.strokeRect(gx - S*0.06, S*0.12, S*0.12, S*0.3);
    // Muzzle
    ctx.fillStyle = '#111111';
    ctx.fillRect(gx - S*0.08, S*0.1, S*0.16, S*0.06);
  });
  // Armored plate panels
  [S*0.18, S*0.44, S*0.68].forEach(px => {
    ctx.fillStyle = '#444444';
    ctx.fillRect(px, S*0.36, S*0.12, S*0.4);
    ctx.strokeStyle = '#666666';
    ctx.lineWidth = S*0.01;
    ctx.strokeRect(px, S*0.36, S*0.12, S*0.4);
  });
  // Red Terran stripe
  ctx.fillStyle = '#cc2222';
  ctx.fillRect(S*0.06, S*0.25, S*0.88, S*0.04);
  // Status light
  const sl = ctx.createRadialGradient(cx, S*0.28, 0, cx, S*0.28, S*0.05);
  sl.addColorStop(0, '#ffaa00');
  sl.addColorStop(1, 'rgba(255,150,0,0)');
  ctx.beginPath();
  ctx.arc(cx, S*0.28, S*0.05, 0, Math.PI*2);
  ctx.fillStyle = sl;
  ctx.fill();
}

function drawSupplyDepot(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Bunker-door appearance (raised)
  const bg = ctx.createLinearGradient(cx, S*0.25, cx, S*0.82);
  bg.addColorStop(0, '#4a4a4a');
  bg.addColorStop(1, '#2a2a2a');
  ctx.fillStyle = bg;
  ctx.fillRect(S*0.12, S*0.3, S*0.76, S*0.5);
  ctx.strokeStyle = '#777777';
  ctx.lineWidth = S*0.025;
  ctx.strokeRect(S*0.12, S*0.3, S*0.76, S*0.5);
  // Two door panels
  ctx.fillStyle = '#333333';
  ctx.fillRect(S*0.15, S*0.33, S*0.33, S*0.44);
  ctx.fillRect(S*0.52, S*0.33, S*0.33, S*0.44);
  ctx.strokeStyle = '#555555';
  ctx.lineWidth = S*0.015;
  ctx.strokeRect(S*0.15, S*0.33, S*0.33, S*0.44);
  ctx.strokeRect(S*0.52, S*0.33, S*0.33, S*0.44);
  // Horizontal ribs
  [S*0.42, S*0.52, S*0.62].forEach(ry => {
    ctx.strokeStyle = '#444444';
    ctx.lineWidth = S*0.015;
    ctx.beginPath();
    ctx.moveTo(S*0.15, ry);
    ctx.lineTo(S*0.85, ry);
    ctx.stroke();
  });
  // Warning light
  ctx.fillStyle = '#ffcc00';
  ctx.beginPath();
  ctx.arc(cx, S*0.27, S*0.03, 0, Math.PI*2);
  ctx.fill();
  // Top ridge
  ctx.fillStyle = '#555555';
  ctx.fillRect(S*0.12, S*0.24, S*0.76, S*0.08);
}

function drawBarracks(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Main building body
  const bg = ctx.createLinearGradient(cx, S*0.14, cx, S*0.86);
  bg.addColorStop(0, '#3a3a3a');
  bg.addColorStop(1, '#1e1e1e');
  ctx.fillStyle = bg;
  ctx.fillRect(S*0.08, S*0.2, S*0.84, S*0.64);
  ctx.strokeStyle = '#666666';
  ctx.lineWidth = S*0.025;
  ctx.strokeRect(S*0.08, S*0.2, S*0.84, S*0.64);
  // Roof structure
  ctx.fillStyle = '#444444';
  ctx.fillRect(S*0.08, S*0.14, S*0.84, S*0.1);
  ctx.strokeStyle = '#777777';
  ctx.lineWidth = S*0.018;
  ctx.strokeRect(S*0.08, S*0.14, S*0.84, S*0.1);
  // Door opening
  ctx.fillStyle = '#111111';
  ctx.fillRect(cx - S*0.12, S*0.52, S*0.24, S*0.32);
  // Windows
  [S*0.18, S*0.7].forEach(px => {
    ctx.fillStyle = '#88aaff';
    ctx.fillRect(px, S*0.3, S*0.12, S*0.16);
    ctx.fillRect(px, S*0.52, S*0.12, S*0.16);
  });
  // Red Terran stripe
  ctx.fillStyle = '#cc2222';
  ctx.fillRect(S*0.08, S*0.2, S*0.84, S*0.04);
  // Marine logo hint
  ctx.strokeStyle = '#ffffff';
  ctx.lineWidth = S*0.025;
  ctx.beginPath();
  ctx.arc(cx, S*0.38, S*0.08, 0, Math.PI*2);
  ctx.stroke();
}

function drawEngineeringBay(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Technical L-shaped body
  ctx.fillStyle = '#3a3a3a';
  ctx.fillRect(S*0.1, S*0.2, S*0.8, S*0.62);
  ctx.strokeStyle = '#666666';
  ctx.lineWidth = S*0.025;
  ctx.strokeRect(S*0.1, S*0.2, S*0.8, S*0.62);
  // Tech panel with circuit lines
  ctx.strokeStyle = '#4488cc';
  ctx.lineWidth = S*0.015;
  [[S*0.16, S*0.3], [S*0.16, S*0.45], [S*0.16, S*0.6]].forEach(([x, y]) => {
    ctx.beginPath();
    ctx.moveTo(x, y);
    ctx.lineTo(x + S*0.48, y);
    ctx.stroke();
  });
  [S*0.24, S*0.36, S*0.48].forEach(px => {
    ctx.beginPath();
    ctx.moveTo(px, S*0.28);
    ctx.lineTo(px, S*0.65);
    ctx.stroke();
  });
  // Upgrade nodes
  [[S*0.24, S*0.3],[S*0.36, S*0.45],[S*0.48, S*0.6]].forEach(([nx,ny]) => {
    ctx.beginPath();
    ctx.arc(nx, ny, S*0.03, 0, Math.PI*2);
    ctx.fillStyle = '#44aaff';
    ctx.fill();
  });
  // Wrench/tool symbols (simplified)
  ctx.fillStyle = '#888888';
  ctx.fillRect(S*0.62, S*0.28, S*0.2, S*0.36);
  ctx.strokeStyle = '#aaaaaa';
  ctx.lineWidth = S*0.018;
  ctx.strokeRect(S*0.62, S*0.28, S*0.2, S*0.36);
  ctx.fillStyle = '#cc2222';
  ctx.fillRect(S*0.1, S*0.2, S*0.8, S*0.04);
}

function drawArmory(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Heavy armored building
  const bg = ctx.createLinearGradient(cx, S*0.16, cx, S*0.84);
  bg.addColorStop(0, '#3a3035');
  bg.addColorStop(1, '#1e1820');
  ctx.fillStyle = bg;
  ctx.fillRect(S*0.1, S*0.2, S*0.8, S*0.62);
  ctx.strokeStyle = '#776677';
  ctx.lineWidth = S*0.028;
  ctx.strokeRect(S*0.1, S*0.2, S*0.8, S*0.62);
  // Weapon rack silhouette
  [S*0.22, S*0.36, S*0.5, S*0.64, S*0.78].forEach(px => {
    ctx.fillStyle = '#555555';
    ctx.fillRect(px - S*0.04, S*0.3, S*0.04, S*0.4);
    ctx.fillStyle = '#777777';
    ctx.fillRect(px - S*0.06, S*0.3, S*0.06, S*0.06);
  });
  // Armored top section
  ctx.fillStyle = '#555555';
  ctx.fillRect(S*0.1, S*0.14, S*0.8, S*0.1);
  ctx.strokeStyle = '#888888';
  ctx.lineWidth = S*0.018;
  ctx.strokeRect(S*0.1, S*0.14, S*0.8, S*0.1);
  // Red stripe
  ctx.fillStyle = '#cc2222';
  ctx.fillRect(S*0.1, S*0.2, S*0.8, S*0.04);
  // Orange warning glow
  const wg = ctx.createRadialGradient(cx, S*0.17, 0, cx, S*0.17, S*0.08);
  wg.addColorStop(0, 'rgba(255,100,0,0.5)');
  wg.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.beginPath();
  ctx.arc(cx, S*0.17, S*0.08, 0, Math.PI*2);
  ctx.fillStyle = wg;
  ctx.fill();
}

function drawMissileTurret(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Base platform
  ctx.fillStyle = '#333333';
  ctx.beginPath();
  ctx.ellipse(cx, cy + S*0.3, S*0.3, S*0.1, 0, 0, Math.PI*2);
  ctx.fill();
  ctx.strokeStyle = '#555555';
  ctx.lineWidth = S*0.02;
  ctx.stroke();
  // Rotation mount
  ctx.fillStyle = '#444444';
  ctx.beginPath();
  ctx.arc(cx, cy + S*0.1, S*0.14, 0, Math.PI*2);
  ctx.fill();
  ctx.strokeStyle = '#777777';
  ctx.lineWidth = S*0.018;
  ctx.stroke();
  // Four missile tubes
  const tubes = [[-S*0.14, -S*0.2], [S*0.14, -S*0.2], [-S*0.06, -S*0.28], [S*0.06, -S*0.28]];
  tubes.forEach(([ox, oy]) => {
    ctx.fillStyle = '#555555';
    ctx.fillRect(cx + ox - S*0.03, cy + oy + S*0.1, S*0.06, S*0.14);
    ctx.strokeStyle = '#888888';
    ctx.lineWidth = S*0.015;
    ctx.strokeRect(cx + ox - S*0.03, cy + oy + S*0.1, S*0.06, S*0.14);
  });
  // Red indicator
  ctx.fillStyle = '#ff3333';
  ctx.beginPath();
  ctx.arc(cx, cy - S*0.1, S*0.03, 0, Math.PI*2);
  ctx.fill();
}

function drawBunker(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Fortified bunker body
  const bg = ctx.createLinearGradient(cx, S*0.22, cx, S*0.84);
  bg.addColorStop(0, '#5a5030');
  bg.addColorStop(0.5, '#3a3020');
  bg.addColorStop(1, '#222010');
  ctx.fillStyle = bg;
  ctx.fillRect(S*0.08, S*0.28, S*0.84, S*0.56);
  ctx.strokeStyle = '#6a6040';
  ctx.lineWidth = S*0.028;
  ctx.stroke();
  // Sloped top
  ctx.beginPath();
  ctx.moveTo(S*0.08, S*0.28);
  ctx.lineTo(cx, S*0.16);
  ctx.lineTo(S*0.92, S*0.28);
  ctx.closePath();
  ctx.fillStyle = '#4a4025';
  ctx.fill();
  ctx.strokeStyle = '#6a6040';
  ctx.lineWidth = S*0.02;
  ctx.stroke();
  // Gun slits
  [S*0.18, S*0.44, S*0.66].forEach(px => {
    ctx.fillStyle = '#111111';
    ctx.fillRect(px, S*0.38, S*0.14, S*0.06);
  });
  // Lower firing ports
  [S*0.18, S*0.44, S*0.66].forEach(px => {
    ctx.fillStyle = '#111111';
    ctx.fillRect(px, S*0.6, S*0.14, S*0.06);
  });
  // Warning stripe
  ctx.strokeStyle = '#ffaa00';
  ctx.lineWidth = S*0.025;
  ctx.beginPath();
  ctx.moveTo(S*0.08, S*0.28);
  ctx.lineTo(S*0.92, S*0.28);
  ctx.stroke();
}

function drawSensorTower(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Thin tower mast
  ctx.fillStyle = '#555555';
  ctx.fillRect(cx - S*0.04, S*0.12, S*0.08, S*0.7);
  ctx.strokeStyle = '#888888';
  ctx.lineWidth = S*0.015;
  ctx.strokeRect(cx - S*0.04, S*0.12, S*0.08, S*0.7);
  // Base plate
  ctx.fillStyle = '#444444';
  ctx.fillRect(S*0.2, S*0.74, S*0.6, S*0.12);
  ctx.strokeStyle = '#666666';
  ctx.lineWidth = S*0.02;
  ctx.strokeRect(S*0.2, S*0.74, S*0.6, S*0.12);
  // Sensor dish
  ctx.beginPath();
  ctx.ellipse(cx, S*0.18, S*0.22, S*0.08, 0.3, 0, Math.PI*2);
  ctx.fillStyle = '#3a3a3a';
  ctx.fill();
  ctx.strokeStyle = '#7777aa';
  ctx.lineWidth = S*0.018;
  ctx.stroke();
  // Radar sweep glow
  const rg = ctx.createRadialGradient(cx, S*0.18, 0, cx, S*0.18, S*0.3);
  rg.addColorStop(0, 'rgba(100,200,100,0.4)');
  rg.addColorStop(0.5, 'rgba(50,150,50,0.15)');
  rg.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.beginPath();
  ctx.arc(cx, S*0.18, S*0.3, 0, Math.PI*2);
  ctx.fillStyle = rg;
  ctx.fill();
  // Blinking light
  ctx.fillStyle = '#ff4444';
  ctx.beginPath();
  ctx.arc(cx, S*0.12, S*0.03, 0, Math.PI*2);
  ctx.fill();
}

function drawGhostAcademy(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Sleek dark building
  const bg = ctx.createLinearGradient(cx, S*0.1, cx, S*0.88);
  bg.addColorStop(0, '#1a1a22');
  bg.addColorStop(0.5, '#111118');
  bg.addColorStop(1, '#080810');
  ctx.fillStyle = bg;
  ctx.fillRect(S*0.12, S*0.16, S*0.76, S*0.7);
  ctx.strokeStyle = '#3333aa';
  ctx.lineWidth = S*0.025;
  ctx.strokeRect(S*0.12, S*0.16, S*0.76, S*0.7);
  // Peaked roof
  ctx.beginPath();
  ctx.moveTo(S*0.12, S*0.16);
  ctx.lineTo(cx, S*0.06);
  ctx.lineTo(S*0.88, S*0.16);
  ctx.closePath();
  ctx.fillStyle = '#222230';
  ctx.fill();
  ctx.strokeStyle = '#4444cc';
  ctx.lineWidth = S*0.02;
  ctx.stroke();
  // Ghost logo — crosshair
  ctx.strokeStyle = '#4444ff';
  ctx.lineWidth = S*0.02;
  ctx.beginPath();
  ctx.arc(cx, cy, S*0.12, 0, Math.PI*2);
  ctx.stroke();
  ctx.beginPath();
  ctx.moveTo(cx - S*0.18, cy);
  ctx.lineTo(cx + S*0.18, cy);
  ctx.moveTo(cx, cy - S*0.18);
  ctx.lineTo(cx, cy + S*0.18);
  ctx.stroke();
  // Blue window strips
  [S*0.22, S*0.7].forEach(px => {
    ctx.fillStyle = '#2244aa';
    ctx.fillRect(px, S*0.28, S*0.1, S*0.4);
  });
  ctx.fillStyle = '#4444cc';
  ctx.fillRect(S*0.12, S*0.16, S*0.76, S*0.03);
}

function drawFactory(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Wide industrial body
  const bg = ctx.createLinearGradient(cx, S*0.18, cx, S*0.86);
  bg.addColorStop(0, '#3a3a3a');
  bg.addColorStop(1, '#1a1a1a');
  ctx.fillStyle = bg;
  ctx.fillRect(S*0.06, S*0.22, S*0.88, S*0.62);
  ctx.strokeStyle = '#666666';
  ctx.lineWidth = S*0.025;
  ctx.strokeRect(S*0.06, S*0.22, S*0.88, S*0.62);
  // Industrial chimney/smokestack
  ctx.fillStyle = '#333333';
  ctx.fillRect(S*0.7, S*0.08, S*0.16, S*0.18);
  ctx.strokeStyle = '#555555';
  ctx.lineWidth = S*0.02;
  ctx.strokeRect(S*0.7, S*0.08, S*0.16, S*0.18);
  // Factory door opening
  ctx.fillStyle = '#111111';
  ctx.fillRect(S*0.1, S*0.48, S*0.36, S*0.34);
  ctx.strokeStyle = '#444444';
  ctx.lineWidth = S*0.015;
  ctx.strokeRect(S*0.1, S*0.48, S*0.36, S*0.34);
  // Mechanical arm/crane
  ctx.strokeStyle = '#777777';
  ctx.lineWidth = S*0.03;
  ctx.lineCap = 'round';
  ctx.beginPath();
  ctx.moveTo(S*0.55, S*0.28);
  ctx.lineTo(S*0.78, S*0.42);
  ctx.stroke();
  ctx.lineCap = 'butt';
  ctx.beginPath();
  ctx.arc(S*0.78, S*0.42, S*0.04, 0, Math.PI*2);
  ctx.fillStyle = '#999999';
  ctx.fill();
  // Orange factory glow
  const fg = ctx.createRadialGradient(S*0.78, S*0.1, 0, S*0.78, S*0.1, S*0.12);
  fg.addColorStop(0, 'rgba(255,120,0,0.6)');
  fg.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.beginPath();
  ctx.arc(S*0.78, S*0.1, S*0.12, 0, Math.PI*2);
  ctx.fillStyle = fg;
  ctx.fill();
  ctx.fillStyle = '#cc2222';
  ctx.fillRect(S*0.06, S*0.22, S*0.88, S*0.04);
}

function drawStarport(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Launch pad base
  const bg = ctx.createLinearGradient(cx, S*0.3, cx, S*0.86);
  bg.addColorStop(0, '#3a3a55');
  bg.addColorStop(1, '#1a1a2e');
  ctx.fillStyle = bg;
  ctx.fillRect(S*0.08, S*0.36, S*0.84, S*0.5);
  ctx.strokeStyle = '#5555aa';
  ctx.lineWidth = S*0.025;
  ctx.strokeRect(S*0.08, S*0.36, S*0.84, S*0.5);
  // Control tower
  ctx.fillStyle = '#2a2a4a';
  ctx.fillRect(S*0.62, S*0.14, S*0.26, S*0.26);
  ctx.strokeStyle = '#6666cc';
  ctx.lineWidth = S*0.02;
  ctx.strokeRect(S*0.62, S*0.14, S*0.26, S*0.26);
  // Launch rail in center
  ctx.fillStyle = '#111122';
  ctx.fillRect(cx - S*0.22, S*0.42, S*0.44, S*0.4);
  // Runway lights
  [S*0.42, S*0.52, S*0.62, S*0.72].forEach(ry => {
    ctx.fillStyle = '#ffcc00';
    ctx.beginPath();
    ctx.arc(cx - S*0.18, ry, S*0.022, 0, Math.PI*2);
    ctx.arc(cx + S*0.18, ry, S*0.022, 0, Math.PI*2);
    ctx.fill();
  });
  // Tower windows
  ctx.fillStyle = '#88aaff';
  ctx.fillRect(S*0.65, S*0.17, S*0.18, S*0.12);
  ctx.fillStyle = '#cc2222';
  ctx.fillRect(S*0.08, S*0.36, S*0.84, S*0.04);
}

function drawFusionCore(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Hexagonal fusion reactor body
  ctx.beginPath();
  const r = S * 0.36;
  for (let i = 0; i < 6; i++) {
    const a = (i / 6) * Math.PI * 2;
    i === 0 ? ctx.moveTo(cx + r*Math.cos(a), cy + r*Math.sin(a))
            : ctx.lineTo(cx + r*Math.cos(a), cy + r*Math.sin(a));
  }
  ctx.closePath();
  const bg = ctx.createRadialGradient(cx, cy, 0, cx, cy, r);
  bg.addColorStop(0, '#3a3a55');
  bg.addColorStop(0.7, '#1a1a33');
  bg.addColorStop(1, '#0a0a1a');
  ctx.fillStyle = bg;
  ctx.fill();
  ctx.strokeStyle = '#4444aa';
  ctx.lineWidth = S*0.028;
  ctx.stroke();
  // Fusion glow core
  const fg = ctx.createRadialGradient(cx, cy, 0, cx, cy, S*0.2);
  fg.addColorStop(0, '#ffffff');
  fg.addColorStop(0.25, '#ffcc44');
  fg.addColorStop(0.6, 'rgba(255,100,0,0.5)');
  fg.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.beginPath();
  ctx.arc(cx, cy, S*0.2, 0, Math.PI*2);
  ctx.fillStyle = fg;
  ctx.fill();
  // Energy rings
  ctx.strokeStyle = 'rgba(255,150,50,0.3)';
  ctx.lineWidth = S*0.02;
  ctx.beginPath();
  ctx.arc(cx, cy, S*0.28, 0, Math.PI*2);
  ctx.stroke();
  // Support struts
  for (let i = 0; i < 3; i++) {
    const a = (i / 3) * Math.PI * 2;
    ctx.strokeStyle = '#6666aa';
    ctx.lineWidth = S*0.02;
    ctx.beginPath();
    ctx.moveTo(cx + Math.cos(a)*S*0.2, cy + Math.sin(a)*S*0.2);
    ctx.lineTo(cx + Math.cos(a)*S*0.33, cy + Math.sin(a)*S*0.33);
    ctx.stroke();
  }
}

function drawRefinery(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Industrial processing unit
  const bg = ctx.createLinearGradient(cx, S*0.22, cx, S*0.82);
  bg.addColorStop(0, '#3a3530');
  bg.addColorStop(1, '#1e1a15');
  ctx.fillStyle = bg;
  ctx.fillRect(S*0.12, S*0.28, S*0.76, S*0.52);
  ctx.strokeStyle = '#665544';
  ctx.lineWidth = S*0.025;
  ctx.strokeRect(S*0.12, S*0.28, S*0.76, S*0.52);
  // Main processing pipe (vertical)
  ctx.fillStyle = '#444440';
  ctx.fillRect(cx - S*0.1, S*0.1, S*0.2, S*0.22);
  ctx.strokeStyle = '#666660';
  ctx.lineWidth = S*0.02;
  ctx.strokeRect(cx - S*0.1, S*0.1, S*0.2, S*0.22);
  // Horizontal pipe connector
  ctx.fillStyle = '#555550';
  ctx.fillRect(S*0.18, S*0.24, S*0.64, S*0.08);
  // Gas vespene green glow
  const gv = ctx.createRadialGradient(cx, S*0.14, 0, cx, S*0.14, S*0.1);
  gv.addColorStop(0, 'rgba(80,255,100,0.7)');
  gv.addColorStop(0.4, 'rgba(40,180,60,0.3)');
  gv.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.beginPath();
  ctx.arc(cx, S*0.14, S*0.1, 0, Math.PI*2);
  ctx.fillStyle = gv;
  ctx.fill();
  // Pipe outlets
  [S*0.22, S*0.38, S*0.54, S*0.68].forEach(px => {
    ctx.fillStyle = '#333330';
    ctx.fillRect(px, S*0.48, S*0.1, S*0.2);
    ctx.strokeStyle = '#555550';
    ctx.lineWidth = S*0.012;
    ctx.strokeRect(px, S*0.48, S*0.1, S*0.2);
  });
}

// ── Zerg building draw functions ─────────────────────────────────────────────

function drawHatchery(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Creep base
  ctx.beginPath();
  ctx.ellipse(cx, cy + S*0.12, S*0.42, S*0.18, 0, 0, Math.PI*2);
  ctx.fillStyle = 'rgba(60,40,20,0.7)';
  ctx.fill();
  // Larva eggs on base
  [[-S*0.28, S*0.18], [S*0.28, S*0.18], [S*0.0, S*0.25]].forEach(([ox, oy]) => {
    ctx.beginPath();
    ctx.ellipse(cx + ox, cy + oy, S*0.06, S*0.04, 0, 0, Math.PI*2);
    ctx.fillStyle = '#6a4422';
    ctx.fill();
  });
  // Main hatchery dome
  ctx.beginPath();
  ctx.ellipse(cx, cy, S*0.38, S*0.28, 0, Math.PI, 0);
  ctx.lineTo(cx + S*0.38, cy);
  ctx.lineTo(cx - S*0.38, cy);
  ctx.closePath();
  const hg = ctx.createRadialGradient(cx, cy - S*0.06, 0, cx, cy, S*0.38);
  hg.addColorStop(0, '#5a6a22');
  hg.addColorStop(0.6, '#3a4a12');
  hg.addColorStop(1, '#1a2208');
  ctx.fillStyle = hg;
  ctx.fill();
  ctx.strokeStyle = '#6a8822';
  ctx.lineWidth = S*0.025;
  ctx.beginPath();
  ctx.ellipse(cx, cy, S*0.38, S*0.28, 0, Math.PI, 0);
  ctx.stroke();
  // Organic spines
  [-S*0.22, 0, S*0.22].forEach(ox => {
    ctx.strokeStyle = '#4a6610';
    ctx.lineWidth = S*0.022;
    ctx.beginPath();
    ctx.moveTo(cx + ox, cy);
    ctx.lineTo(cx + ox + ox*0.3, cy - S*0.18);
    ctx.stroke();
  });
  // Queen pheromone glow
  const pg = ctx.createRadialGradient(cx, cy - S*0.08, 0, cx, cy - S*0.08, S*0.14);
  pg.addColorStop(0, 'rgba(180,255,50,0.5)');
  pg.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.beginPath();
  ctx.arc(cx, cy - S*0.08, S*0.14, 0, Math.PI*2);
  ctx.fillStyle = pg;
  ctx.fill();
}

function drawLair(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Evolved creep base
  ctx.beginPath();
  ctx.ellipse(cx, cy + S*0.15, S*0.44, S*0.18, 0, 0, Math.PI*2);
  ctx.fillStyle = 'rgba(50,30,15,0.8)';
  ctx.fill();
  // Larger enclosed dome
  ctx.beginPath();
  ctx.ellipse(cx, cy, S*0.4, S*0.32, 0, Math.PI, 0);
  ctx.lineTo(cx + S*0.4, cy);
  ctx.lineTo(cx - S*0.4, cy);
  ctx.closePath();
  const lg = ctx.createRadialGradient(cx, cy - S*0.1, 0, cx, cy, S*0.4);
  lg.addColorStop(0, '#6a7a28');
  lg.addColorStop(0.5, '#3a5015');
  lg.addColorStop(1, '#1a2808');
  ctx.fillStyle = lg;
  ctx.fill();
  ctx.strokeStyle = '#88aa22';
  ctx.lineWidth = S*0.028;
  ctx.beginPath();
  ctx.ellipse(cx, cy, S*0.4, S*0.32, 0, Math.PI, 0);
  ctx.stroke();
  // Additional spine growths
  [-S*0.3, -S*0.15, 0, S*0.15, S*0.3].forEach(ox => {
    const h = S*0.16 - Math.abs(ox)*0.3;
    ctx.strokeStyle = '#558810';
    ctx.lineWidth = S*0.02;
    ctx.beginPath();
    ctx.moveTo(cx + ox, cy);
    ctx.quadraticCurveTo(cx + ox + ox*0.2, cy - h*0.5, cx + ox, cy - h);
    ctx.stroke();
  });
  // Lair yellow-green glow
  const yg = ctx.createRadialGradient(cx, cy - S*0.1, 0, cx, cy - S*0.1, S*0.16);
  yg.addColorStop(0, 'rgba(220,255,50,0.55)');
  yg.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.beginPath();
  ctx.arc(cx, cy - S*0.1, S*0.16, 0, Math.PI*2);
  ctx.fillStyle = yg;
  ctx.fill();
}

function drawHive(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Dark massive creep base
  ctx.beginPath();
  ctx.ellipse(cx, cy + S*0.18, S*0.46, S*0.2, 0, 0, Math.PI*2);
  ctx.fillStyle = 'rgba(30,10,5,0.9)';
  ctx.fill();
  // Imposing hive dome (tallest/darkest)
  ctx.beginPath();
  ctx.ellipse(cx, cy - S*0.02, S*0.42, S*0.36, 0, Math.PI, 0);
  ctx.lineTo(cx + S*0.42, cy - S*0.02);
  ctx.lineTo(cx - S*0.42, cy - S*0.02);
  ctx.closePath();
  const hg = ctx.createRadialGradient(cx, cy - S*0.15, 0, cx, cy, S*0.42);
  hg.addColorStop(0, '#7a3a22');
  hg.addColorStop(0.5, '#442010');
  hg.addColorStop(1, '#1a0a05');
  ctx.fillStyle = hg;
  ctx.fill();
  ctx.strokeStyle = '#aa5522';
  ctx.lineWidth = S*0.03;
  ctx.beginPath();
  ctx.ellipse(cx, cy - S*0.02, S*0.42, S*0.36, 0, Math.PI, 0);
  ctx.stroke();
  // Many large spines
  [-S*0.32, -S*0.18, 0, S*0.18, S*0.32].forEach((ox, i) => {
    ctx.strokeStyle = '#882200';
    ctx.lineWidth = S*0.025;
    ctx.beginPath();
    ctx.moveTo(cx + ox, cy - S*0.02);
    ctx.lineTo(cx + ox + ox*0.15, cy - S*0.2 - (i === 2 ? S*0.06 : 0));
    ctx.stroke();
  });
  // Red-orange hive glow
  const rg = ctx.createRadialGradient(cx, cy - S*0.12, 0, cx, cy - S*0.12, S*0.18);
  rg.addColorStop(0, 'rgba(255,80,20,0.65)');
  rg.addColorStop(0.5, 'rgba(180,40,0,0.3)');
  rg.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.beginPath();
  ctx.arc(cx, cy - S*0.12, S*0.18, 0, Math.PI*2);
  ctx.fillStyle = rg;
  ctx.fill();
}

function drawSpawningPool(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Pool edge/rim
  ctx.beginPath();
  ctx.ellipse(cx, cy + S*0.05, S*0.4, S*0.3, 0, 0, Math.PI*2);
  ctx.fillStyle = '#1a3308';
  ctx.fill();
  ctx.strokeStyle = '#4a8810';
  ctx.lineWidth = S*0.025;
  ctx.stroke();
  // Pool liquid
  ctx.beginPath();
  ctx.ellipse(cx, cy + S*0.08, S*0.34, S*0.24, 0, 0, Math.PI*2);
  const pl = ctx.createRadialGradient(cx, cy + S*0.08, 0, cx, cy + S*0.08, S*0.34);
  pl.addColorStop(0, '#55cc22');
  pl.addColorStop(0.6, '#228810');
  pl.addColorStop(1, '#0a3306');
  ctx.fillStyle = pl;
  ctx.fill();
  // Organic pillar around edge
  [-S*0.3, S*0.3].forEach(ox => {
    ctx.fillStyle = '#2a5508';
    ctx.fillRect(cx + ox - S*0.06, S*0.12, S*0.12, S*0.4);
    ctx.strokeStyle = '#55aa14';
    ctx.lineWidth = S*0.018;
    ctx.strokeRect(cx + ox - S*0.06, S*0.12, S*0.12, S*0.4);
  });
  // Surface bubbles
  [[cx - S*0.1, cy],[cx + S*0.12, cy + S*0.12],[cx, cy - S*0.06]].forEach(([bx, by]) => {
    ctx.beginPath();
    ctx.arc(bx, by, S*0.03, 0, Math.PI*2);
    ctx.fillStyle = 'rgba(150,255,80,0.5)';
    ctx.fill();
  });
}

function drawEvolutionChamber(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Low organic building with tentacle growth
  ctx.beginPath();
  ctx.ellipse(cx, cy + S*0.18, S*0.36, S*0.2, 0, 0, Math.PI*2);
  const bg = ctx.createRadialGradient(cx, cy + S*0.18, 0, cx, cy + S*0.18, S*0.36);
  bg.addColorStop(0, '#3a5515');
  bg.addColorStop(1, '#1a2808');
  ctx.fillStyle = bg;
  ctx.fill();
  ctx.strokeStyle = '#5a8820';
  ctx.lineWidth = S*0.022;
  ctx.stroke();
  // DNA helix hint (two spiraling lines)
  for (let s = 0; s < 2; s++) {
    ctx.beginPath();
    ctx.strokeStyle = s === 0 ? '#44cc22' : '#cc4422';
    ctx.lineWidth = S*0.025;
    for (let t = 0; t <= 1; t += 0.05) {
      const a = t * Math.PI * 3 + s * Math.PI;
      const x = cx + Math.cos(a) * S*0.12;
      const y = cy - S*0.28 + t * S*0.52;
      t === 0 ? ctx.moveTo(x, y) : ctx.lineTo(x, y);
    }
    ctx.stroke();
  }
  // Cross-links
  for (let i = 0; i < 4; i++) {
    const a = i * Math.PI * 3 / 4;
    const y = cy - S*0.28 + (i / 4) * S*0.52;
    ctx.strokeStyle = '#888844';
    ctx.lineWidth = S*0.015;
    ctx.beginPath();
    ctx.moveTo(cx + Math.cos(a) * S*0.12, y);
    ctx.lineTo(cx + Math.cos(a + Math.PI) * S*0.12, y);
    ctx.stroke();
  }
}

function drawRoachWarren(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Underground burrow entrance
  ctx.beginPath();
  ctx.ellipse(cx, cy + S*0.15, S*0.38, S*0.24, 0, 0, Math.PI*2);
  ctx.fillStyle = '#2a1808';
  ctx.fill();
  ctx.strokeStyle = '#5a3515';
  ctx.lineWidth = S*0.02;
  ctx.stroke();
  // Tunnel opening
  ctx.beginPath();
  ctx.ellipse(cx, cy + S*0.18, S*0.28, S*0.18, 0, 0, Math.PI*2);
  ctx.fillStyle = '#080808';
  ctx.fill();
  // Acid drips around opening
  [[cx - S*0.18, cy + S*0.02], [cx, cy - S*0.04], [cx + S*0.18, cy + S*0.02]].forEach(([ax, ay]) => {
    ctx.strokeStyle = '#88cc22';
    ctx.lineWidth = S*0.018;
    ctx.beginPath();
    ctx.moveTo(ax, ay);
    ctx.lineTo(ax, ay + S*0.14);
    ctx.stroke();
    ctx.beginPath();
    ctx.arc(ax, ay + S*0.14, S*0.03, 0, Math.PI*2);
    ctx.fillStyle = 'rgba(100,200,30,0.7)';
    ctx.fill();
  });
  // Chitinous ridge
  ctx.beginPath();
  ctx.ellipse(cx, cy - S*0.05, S*0.32, S*0.14, 0, Math.PI, 0);
  ctx.fillStyle = '#3a2210';
  ctx.fill();
  ctx.strokeStyle = '#6a4420';
  ctx.lineWidth = S*0.02;
  ctx.stroke();
}

function drawBanelingNest(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Acid-dripping nest
  ctx.beginPath();
  ctx.ellipse(cx, cy + S*0.1, S*0.36, S*0.26, 0, 0, Math.PI*2);
  const ng = ctx.createRadialGradient(cx, cy + S*0.1, 0, cx, cy + S*0.1, S*0.36);
  ng.addColorStop(0, '#6a8810');
  ng.addColorStop(0.6, '#3a5508');
  ng.addColorStop(1, '#1a2804');
  ctx.fillStyle = ng;
  ctx.fill();
  ctx.strokeStyle = '#88aa14';
  ctx.lineWidth = S*0.025;
  ctx.stroke();
  // Baneling eggs
  [[-S*0.18, -S*0.04], [S*0.18, -S*0.04], [0, -S*0.12], [-S*0.1, S*0.08], [S*0.1, S*0.08]].forEach(([ox, oy]) => {
    ctx.beginPath();
    ctx.arc(cx + ox, cy + oy, S*0.08, 0, Math.PI*2);
    const eg = ctx.createRadialGradient(cx + ox, cy + oy, 0, cx + ox, cy + oy, S*0.08);
    eg.addColorStop(0, '#ccee22');
    eg.addColorStop(0.6, '#88aa10');
    eg.addColorStop(1, '#3a5508');
    ctx.fillStyle = eg;
    ctx.fill();
    ctx.strokeStyle = '#aacc18';
    ctx.lineWidth = S*0.012;
    ctx.stroke();
  });
  // Acid pool under nest
  ctx.beginPath();
  ctx.ellipse(cx, cy + S*0.26, S*0.3, S*0.1, 0, 0, Math.PI*2);
  ctx.fillStyle = 'rgba(120,200,0,0.4)';
  ctx.fill();
}

function drawSpineCrawler(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Ground burrow base
  ctx.beginPath();
  ctx.ellipse(cx, cy + S*0.3, S*0.26, S*0.1, 0, 0, Math.PI*2);
  ctx.fillStyle = '#1a1a08';
  ctx.fill();
  ctx.strokeStyle = '#3a3a15';
  ctx.lineWidth = S*0.018;
  ctx.stroke();
  // Worm body/stalk
  ctx.beginPath();
  ctx.moveTo(cx - S*0.08, cy + S*0.3);
  ctx.quadraticCurveTo(cx - S*0.06, cy, cx, cy - S*0.28);
  ctx.quadraticCurveTo(cx + S*0.06, cy, cx + S*0.08, cy + S*0.3);
  ctx.closePath();
  const wg = ctx.createLinearGradient(cx - S*0.08, 0, cx + S*0.08, 0);
  wg.addColorStop(0, '#5a8820');
  wg.addColorStop(0.5, '#88cc2a');
  wg.addColorStop(1, '#5a8820');
  ctx.fillStyle = wg;
  ctx.fill();
  ctx.strokeStyle = '#44aa14';
  ctx.lineWidth = S*0.018;
  ctx.stroke();
  // Fang tip
  ctx.beginPath();
  ctx.moveTo(cx, cy - S*0.28);
  ctx.lineTo(cx - S*0.08, cy - S*0.14);
  ctx.lineTo(cx + S*0.08, cy - S*0.14);
  ctx.closePath();
  ctx.fillStyle = '#cc4422';
  ctx.fill();
  // Body segments
  [cy - S*0.05, cy + S*0.1].forEach(ry => {
    ctx.strokeStyle = '#335510';
    ctx.lineWidth = S*0.015;
    ctx.beginPath();
    ctx.ellipse(cx, ry, S*0.07, S*0.025, 0, 0, Math.PI*2);
    ctx.stroke();
  });
}

function drawSporeCrawler(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Root anchor
  ctx.beginPath();
  ctx.ellipse(cx, cy + S*0.32, S*0.22, S*0.09, 0, 0, Math.PI*2);
  ctx.fillStyle = '#1a1a0a';
  ctx.fill();
  ctx.strokeStyle = '#4a4a1a';
  ctx.lineWidth = S*0.018;
  ctx.stroke();
  // Plant-like stalk (passes through centre so smoke test alpha > 0)
  ctx.strokeStyle = '#5a8820';
  ctx.lineWidth = S*0.08;
  ctx.lineCap = 'round';
  ctx.beginPath();
  ctx.moveTo(cx, cy + S*0.3);
  ctx.quadraticCurveTo(cx + S*0.04, cy, cx, cy - S*0.18);
  ctx.stroke();
  ctx.lineCap = 'butt';
  // Spore pod/flower at top
  const r = S*0.12;
  for (let i = 0; i < 6; i++) {
    const a = (i / 6) * Math.PI * 2;
    ctx.beginPath();
    ctx.ellipse(cx + Math.cos(a)*r, cy - S*0.22 + Math.sin(a)*r*0.5, S*0.06, S*0.04, a, 0, Math.PI*2);
    ctx.fillStyle = '#8acc33';
    ctx.fill();
  }
  // Center spore
  ctx.beginPath();
  ctx.arc(cx, cy - S*0.22, S*0.06, 0, Math.PI*2);
  const sg = ctx.createRadialGradient(cx, cy - S*0.22, 0, cx, cy - S*0.22, S*0.06);
  sg.addColorStop(0, '#eeff44');
  sg.addColorStop(1, '#88cc22');
  ctx.fillStyle = sg;
  ctx.fill();
}

function drawHydraliskDen(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Cave entrance shape
  ctx.beginPath();
  ctx.arc(cx, cy + S*0.1, S*0.36, Math.PI, 0);
  ctx.lineTo(cx + S*0.36, cy + S*0.38);
  ctx.lineTo(cx - S*0.36, cy + S*0.38);
  ctx.closePath();
  const bg = ctx.createRadialGradient(cx, cy + S*0.1, 0, cx, cy + S*0.1, S*0.36);
  bg.addColorStop(0, '#4a5520');
  bg.addColorStop(0.6, '#2a3515');
  bg.addColorStop(1, '#1a2208');
  ctx.fillStyle = bg;
  ctx.fill();
  ctx.strokeStyle = '#6a8828';
  ctx.lineWidth = S*0.025;
  ctx.stroke();
  // Dark cave interior
  ctx.beginPath();
  ctx.arc(cx, cy + S*0.14, S*0.22, Math.PI, 0);
  ctx.lineTo(cx + S*0.22, cy + S*0.38);
  ctx.lineTo(cx - S*0.22, cy + S*0.38);
  ctx.closePath();
  ctx.fillStyle = '#080810';
  ctx.fill();
  // Needle spines around opening
  [-S*0.28, -S*0.14, 0, S*0.14, S*0.28].forEach((ox, i) => {
    const a = Math.PI * 1.1 + (i / 4) * Math.PI * 0.8;
    ctx.strokeStyle = '#66aa22';
    ctx.lineWidth = S*0.018;
    ctx.beginPath();
    ctx.moveTo(cx + ox, cy + S*0.1 + S*0.36*Math.sin(a - Math.PI));
    ctx.lineTo(cx + ox, cy + S*0.1 + S*0.36*Math.sin(a - Math.PI) - S*0.12);
    ctx.stroke();
  });
  // Glowing eyes in darkness
  ctx.fillStyle = '#ffcc00';
  [[-S*0.1, cy + S*0.2], [S*0.1, cy + S*0.2]].forEach(([ex, ey]) => {
    ctx.beginPath();
    ctx.arc(ex, ey, S*0.03, 0, Math.PI*2);
    ctx.fill();
  });
}

function drawLurkerDen(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Deep burrow — darker than hydralisk den
  ctx.beginPath();
  ctx.arc(cx, cy + S*0.08, S*0.38, Math.PI, 0);
  ctx.lineTo(cx + S*0.38, cy + S*0.4);
  ctx.lineTo(cx - S*0.38, cy + S*0.4);
  ctx.closePath();
  const bg = ctx.createRadialGradient(cx, cy + S*0.08, 0, cx, cy + S*0.08, S*0.38);
  bg.addColorStop(0, '#3a2215');
  bg.addColorStop(0.5, '#221510');
  bg.addColorStop(1, '#0a0808');
  ctx.fillStyle = bg;
  ctx.fill();
  ctx.strokeStyle = '#5a3320';
  ctx.lineWidth = S*0.025;
  ctx.stroke();
  // Abyss interior
  ctx.beginPath();
  ctx.arc(cx, cy + S*0.12, S*0.24, Math.PI, 0);
  ctx.lineTo(cx + S*0.24, cy + S*0.4);
  ctx.lineTo(cx - S*0.24, cy + S*0.4);
  ctx.closePath();
  ctx.fillStyle = '#030305';
  ctx.fill();
  // Sharp bony spikes
  [-S*0.3, -S*0.14, S*0.14, S*0.3].forEach(ox => {
    ctx.fillStyle = '#885522';
    ctx.beginPath();
    ctx.moveTo(cx + ox, cy + S*0.08);
    ctx.lineTo(cx + ox - S*0.04, cy + S*0.08 - S*0.2);
    ctx.lineTo(cx + ox + S*0.04, cy + S*0.08 - S*0.2);
    ctx.closePath();
    ctx.fill();
  });
  // Purple lurker glow
  const lg = ctx.createRadialGradient(cx, cy + S*0.2, 0, cx, cy + S*0.2, S*0.18);
  lg.addColorStop(0, 'rgba(150,50,200,0.5)');
  lg.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.beginPath();
  ctx.arc(cx, cy + S*0.2, S*0.18, 0, Math.PI*2);
  ctx.fillStyle = lg;
  ctx.fill();
}

function drawInfestationPit(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Pit opening
  ctx.beginPath();
  ctx.ellipse(cx, cy + S*0.1, S*0.38, S*0.26, 0, 0, Math.PI*2);
  ctx.fillStyle = '#1a0a08';
  ctx.fill();
  ctx.strokeStyle = '#4a1a10';
  ctx.lineWidth = S*0.025;
  ctx.stroke();
  // Infestation fluid
  ctx.beginPath();
  ctx.ellipse(cx, cy + S*0.14, S*0.3, S*0.2, 0, 0, Math.PI*2);
  const ig = ctx.createRadialGradient(cx, cy + S*0.14, 0, cx, cy + S*0.14, S*0.3);
  ig.addColorStop(0, '#884422');
  ig.addColorStop(0.5, '#441808');
  ig.addColorStop(1, '#1a0808');
  ctx.fillStyle = ig;
  ctx.fill();
  // Tentacles rising
  for (let i = 0; i < 5; i++) {
    const a = (i / 5) * Math.PI * 2;
    const r = S*0.22;
    const tx = cx + Math.cos(a) * r;
    const ty = cy + S*0.14 + Math.sin(a) * r * 0.5;
    ctx.strokeStyle = '#662211';
    ctx.lineWidth = S*0.02;
    ctx.beginPath();
    ctx.moveTo(tx, ty);
    ctx.quadraticCurveTo(tx + Math.cos(a)*S*0.06, ty - S*0.12, tx + Math.cos(a)*S*0.04, ty - S*0.22);
    ctx.stroke();
  }
  // Sickly glow
  const sg = ctx.createRadialGradient(cx, cy + S*0.1, 0, cx, cy + S*0.1, S*0.22);
  sg.addColorStop(0, 'rgba(200,80,30,0.4)');
  sg.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.beginPath();
  ctx.arc(cx, cy + S*0.1, S*0.22, 0, Math.PI*2);
  ctx.fillStyle = sg;
  ctx.fill();
}

function drawSpire(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Creep base
  ctx.beginPath();
  ctx.ellipse(cx, S*0.82, S*0.24, S*0.1, 0, 0, Math.PI*2);
  ctx.fillStyle = '#1a1008';
  ctx.fill();
  // Main spire crystal
  ctx.beginPath();
  ctx.moveTo(cx, S*0.06);
  ctx.lineTo(cx + S*0.14, S*0.42);
  ctx.lineTo(cx + S*0.18, S*0.82);
  ctx.lineTo(cx - S*0.18, S*0.82);
  ctx.lineTo(cx - S*0.14, S*0.42);
  ctx.closePath();
  const sg = ctx.createLinearGradient(cx - S*0.18, 0, cx + S*0.18, 0);
  sg.addColorStop(0, '#442255');
  sg.addColorStop(0.5, '#773388');
  sg.addColorStop(1, '#442255');
  ctx.fillStyle = sg;
  ctx.fill();
  ctx.strokeStyle = '#aa44cc';
  ctx.lineWidth = S*0.022;
  ctx.stroke();
  // Two flanking mini-spires
  [cx - S*0.28, cx + S*0.2].forEach(sx => {
    ctx.beginPath();
    ctx.moveTo(sx + S*0.04, S*0.22);
    ctx.lineTo(sx + S*0.1, S*0.55);
    ctx.lineTo(sx + S*0.16, S*0.82);
    ctx.lineTo(sx - S*0.02, S*0.82);
    ctx.lineTo(sx, S*0.55);
    ctx.closePath();
    ctx.fillStyle = '#331a44';
    ctx.fill();
    ctx.strokeStyle = '#8833aa';
    ctx.lineWidth = S*0.015;
    ctx.stroke();
  });
  // Apex glow
  const ag = ctx.createRadialGradient(cx, S*0.1, 0, cx, S*0.1, S*0.1);
  ag.addColorStop(0, 'rgba(220,100,255,0.8)');
  ag.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.beginPath();
  ctx.arc(cx, S*0.1, S*0.1, 0, Math.PI*2);
  ctx.fillStyle = ag;
  ctx.fill();
}

function drawGreaterSpire(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Larger base
  ctx.beginPath();
  ctx.ellipse(cx, S*0.84, S*0.3, S*0.12, 0, 0, Math.PI*2);
  ctx.fillStyle = '#1a0a18';
  ctx.fill();
  // Taller main spire
  ctx.beginPath();
  ctx.moveTo(cx, S*0.04);
  ctx.lineTo(cx + S*0.16, S*0.38);
  ctx.lineTo(cx + S*0.22, S*0.84);
  ctx.lineTo(cx - S*0.22, S*0.84);
  ctx.lineTo(cx - S*0.16, S*0.38);
  ctx.closePath();
  const sg = ctx.createLinearGradient(cx - S*0.22, 0, cx + S*0.22, 0);
  sg.addColorStop(0, '#5a1a66');
  sg.addColorStop(0.5, '#9933bb');
  sg.addColorStop(1, '#5a1a66');
  ctx.fillStyle = sg;
  ctx.fill();
  ctx.strokeStyle = '#cc55ee';
  ctx.lineWidth = S*0.025;
  ctx.stroke();
  // Four flanking spires
  [-S*0.32, -S*0.18, S*0.18, S*0.32].forEach((ox, i) => {
    const h = i < 2 ? S*0.52 : S*0.46;
    ctx.beginPath();
    ctx.moveTo(cx + ox, S*0.18);
    ctx.lineTo(cx + ox + S*0.06, h);
    ctx.lineTo(cx + ox + S*0.1, S*0.84);
    ctx.lineTo(cx + ox - S*0.04, S*0.84);
    ctx.lineTo(cx + ox, h);
    ctx.closePath();
    ctx.fillStyle = '#3a1150';
    ctx.fill();
    ctx.strokeStyle = '#aa33dd';
    ctx.lineWidth = S*0.012;
    ctx.stroke();
  });
  // Intense apex glow
  const ag = ctx.createRadialGradient(cx, S*0.08, 0, cx, S*0.08, S*0.14);
  ag.addColorStop(0, 'rgba(255,150,255,0.9)');
  ag.addColorStop(0.4, 'rgba(180,50,255,0.5)');
  ag.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.beginPath();
  ctx.arc(cx, S*0.08, S*0.14, 0, Math.PI*2);
  ctx.fillStyle = ag;
  ctx.fill();
}

function drawNydusNetwork(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Organic network hub
  ctx.beginPath();
  ctx.ellipse(cx, cy + S*0.08, S*0.36, S*0.28, 0, 0, Math.PI*2);
  const bg = ctx.createRadialGradient(cx, cy + S*0.08, 0, cx, cy + S*0.08, S*0.36);
  bg.addColorStop(0, '#552a18');
  bg.addColorStop(0.6, '#331808');
  bg.addColorStop(1, '#1a0a04');
  ctx.fillStyle = bg;
  ctx.fill();
  ctx.strokeStyle = '#885522';
  ctx.lineWidth = S*0.025;
  ctx.stroke();
  // Worm hole opening
  ctx.beginPath();
  ctx.ellipse(cx, cy + S*0.1, S*0.2, S*0.14, 0, 0, Math.PI*2);
  const hg = ctx.createRadialGradient(cx, cy + S*0.1, 0, cx, cy + S*0.1, S*0.2);
  hg.addColorStop(0, '#440000');
  hg.addColorStop(1, '#1a0808');
  ctx.fillStyle = hg;
  ctx.fill();
  // Network tubes radiating out
  for (let i = 0; i < 4; i++) {
    const a = (i / 4) * Math.PI * 2 + Math.PI*0.25;
    ctx.strokeStyle = '#664422';
    ctx.lineWidth = S*0.04;
    ctx.lineCap = 'round';
    ctx.beginPath();
    ctx.moveTo(cx + Math.cos(a)*S*0.2, cy + S*0.08 + Math.sin(a)*S*0.14);
    ctx.lineTo(cx + Math.cos(a)*S*0.38, cy + S*0.08 + Math.sin(a)*S*0.26);
    ctx.stroke();
  }
  ctx.lineCap = 'butt';
  // Red-orange portal glow
  const rg = ctx.createRadialGradient(cx, cy + S*0.1, 0, cx, cy + S*0.1, S*0.14);
  rg.addColorStop(0, 'rgba(255,60,20,0.7)');
  rg.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.beginPath();
  ctx.arc(cx, cy + S*0.1, S*0.14, 0, Math.PI*2);
  ctx.fillStyle = rg;
  ctx.fill();
}

function drawNydusCanal(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Worm emerging from ground
  // Ground crack
  ctx.beginPath();
  ctx.ellipse(cx, S*0.75, S*0.22, S*0.08, 0, 0, Math.PI*2);
  ctx.fillStyle = '#1a0a04';
  ctx.fill();
  ctx.strokeStyle = '#442211';
  ctx.lineWidth = S*0.02;
  ctx.stroke();
  // Worm body
  ctx.beginPath();
  ctx.moveTo(cx - S*0.12, S*0.75);
  ctx.quadraticCurveTo(cx - S*0.08, S*0.4, cx, S*0.12);
  ctx.quadraticCurveTo(cx + S*0.08, S*0.4, cx + S*0.12, S*0.75);
  ctx.closePath();
  const wg = ctx.createLinearGradient(cx - S*0.12, 0, cx + S*0.12, 0);
  wg.addColorStop(0, '#663322');
  wg.addColorStop(0.5, '#995544');
  wg.addColorStop(1, '#663322');
  ctx.fillStyle = wg;
  ctx.fill();
  ctx.strokeStyle = '#886655';
  ctx.lineWidth = S*0.018;
  ctx.stroke();
  // Worm segments
  [S*0.32, S*0.48, S*0.62].forEach(ry => {
    ctx.strokeStyle = '#442211';
    ctx.lineWidth = S*0.015;
    ctx.beginPath();
    ctx.ellipse(cx, ry, S*0.1, S*0.025, 0, 0, Math.PI*2);
    ctx.stroke();
  });
  // Maw at top
  const mg = ctx.createRadialGradient(cx, S*0.16, 0, cx, S*0.16, S*0.12);
  mg.addColorStop(0, '#ff3300');
  mg.addColorStop(0.5, 'rgba(200,50,0,0.5)');
  mg.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.beginPath();
  ctx.arc(cx, S*0.16, S*0.12, 0, Math.PI*2);
  ctx.fillStyle = mg;
  ctx.fill();
  // Teeth
  [-S*0.06, 0, S*0.06].forEach(ox => {
    ctx.fillStyle = '#ffffff';
    ctx.beginPath();
    ctx.moveTo(cx + ox, S*0.1);
    ctx.lineTo(cx + ox - S*0.03, S*0.18);
    ctx.lineTo(cx + ox + S*0.03, S*0.18);
    ctx.fill();
  });
}

function drawUltraliskCavern(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Massive cave entrance
  ctx.beginPath();
  ctx.arc(cx, cy + S*0.12, S*0.4, Math.PI, 0);
  ctx.lineTo(cx + S*0.4, cy + S*0.44);
  ctx.lineTo(cx - S*0.4, cy + S*0.44);
  ctx.closePath();
  const bg = ctx.createRadialGradient(cx, cy + S*0.12, 0, cx, cy + S*0.12, S*0.4);
  bg.addColorStop(0, '#5a3315');
  bg.addColorStop(0.6, '#331808');
  bg.addColorStop(1, '#1a0a04');
  ctx.fillStyle = bg;
  ctx.fill();
  ctx.strokeStyle = '#7a4820';
  ctx.lineWidth = S*0.03;
  ctx.stroke();
  // Deep dark interior
  ctx.beginPath();
  ctx.arc(cx, cy + S*0.18, S*0.26, Math.PI, 0);
  ctx.lineTo(cx + S*0.26, cy + S*0.44);
  ctx.lineTo(cx - S*0.26, cy + S*0.44);
  ctx.closePath();
  ctx.fillStyle = '#040204';
  ctx.fill();
  // Giant kaiser blades silhouette
  [[-S*0.3, S*0.08], [S*0.3, S*0.08]].forEach(([bx, by]) => {
    ctx.fillStyle = '#663300';
    ctx.beginPath();
    ctx.moveTo(cx + bx, cy + by);
    ctx.lineTo(cx + bx + (bx > 0 ? S*0.14 : -S*0.14), cy + by - S*0.26);
    ctx.lineTo(cx + bx + (bx > 0 ? -S*0.06 : S*0.06), cy + by + S*0.1);
    ctx.closePath();
    ctx.fill();
    ctx.strokeStyle = '#995522';
    ctx.lineWidth = S*0.018;
    ctx.stroke();
  });
  // Amber eyes glow
  const eg = ctx.createRadialGradient(cx, cy + S*0.28, 0, cx, cy + S*0.28, S*0.1);
  eg.addColorStop(0, 'rgba(255,150,0,0.7)');
  eg.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.beginPath();
  ctx.arc(cx, cy + S*0.28, S*0.1, 0, Math.PI*2);
  ctx.fillStyle = eg;
  ctx.fill();
}

function drawExtractor(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  // Organic gas collector — chitin dome
  ctx.beginPath();
  ctx.ellipse(cx, cy + S*0.06, S*0.36, S*0.26, 0, Math.PI, 0);
  ctx.lineTo(cx + S*0.36, cy + S*0.06);
  ctx.lineTo(cx - S*0.36, cy + S*0.06);
  ctx.closePath();
  const dg = ctx.createRadialGradient(cx, cy - S*0.08, 0, cx, cy, S*0.36);
  dg.addColorStop(0, '#335528');
  dg.addColorStop(0.6, '#1a3315');
  dg.addColorStop(1, '#0a1808');
  ctx.fillStyle = dg;
  ctx.fill();
  ctx.strokeStyle = '#448833';
  ctx.lineWidth = S*0.025;
  ctx.beginPath();
  ctx.ellipse(cx, cy + S*0.06, S*0.36, S*0.26, 0, Math.PI, 0);
  ctx.stroke();
  // Chitin ribs
  [-S*0.2, 0, S*0.2].forEach(ox => {
    ctx.strokeStyle = '#2a6620';
    ctx.lineWidth = S*0.018;
    ctx.beginPath();
    ctx.moveTo(cx + ox, cy + S*0.06);
    ctx.lineTo(cx + ox + ox*0.2, cy - S*0.14);
    ctx.stroke();
  });
  // Gas vent pipes on top
  [-S*0.14, 0, S*0.14].forEach(ox => {
    ctx.fillStyle = '#1a3310';
    ctx.fillRect(cx + ox - S*0.025, cy - S*0.36, S*0.05, S*0.1);
    ctx.beginPath();
    ctx.arc(cx + ox, cy - S*0.32, S*0.035, 0, Math.PI*2);
    ctx.fillStyle = 'rgba(100,220,130,0.6)';
    ctx.fill();
  });
  // Base creep
  ctx.beginPath();
  ctx.ellipse(cx, cy + S*0.2, S*0.3, S*0.1, 0, 0, Math.PI*2);
  ctx.fillStyle = 'rgba(30,50,15,0.6)';
  ctx.fill();
}

function drawUnknownBuilding(ctx, S, dir, teamColor) {
  ctx.clearRect(0, 0, S, S);
  const cx = S/2, cy = S/2;
  ctx.fillStyle = '#1a2233';
  ctx.fillRect(S*0.15, S*0.15, S*0.7, S*0.7);
  ctx.strokeStyle = '#334455';
  ctx.lineWidth = S*0.03;
  ctx.strokeRect(S*0.15, S*0.15, S*0.7, S*0.7);
  ctx.fillStyle = '#556677';
  ctx.font = `bold ${Math.round(S*0.45)}px monospace`;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText('?', cx, cy);
}

// Populated by initSpriteMaterials() — do not read before init() runs
const UNIT_MATS = {};
const BUILDING_MATS = {};

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
  UNIT_MATS['SIEGE_TANK_SIEGED_F'] = makeDirTextures(drawSiegeTankSieged, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['SIEGE_TANK_SIEGED_E'] = makeDirTextures(drawSiegeTankSieged, TEAM_COLOR_ENEMY);
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
  UNIT_MATS['SCV_F'] = makeDirTextures(drawSCV, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['SCV_E'] = makeDirTextures(drawSCV, TEAM_COLOR_ENEMY);
  UNIT_MATS['REAPER_F'] = makeDirTextures(drawReaper, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['REAPER_E'] = makeDirTextures(drawReaper, TEAM_COLOR_ENEMY);
  UNIT_MATS['HELLION_F'] = makeDirTextures(drawHellion, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['HELLION_E'] = makeDirTextures(drawHellion, TEAM_COLOR_ENEMY);
  UNIT_MATS['HELLBAT_F'] = makeDirTextures(drawHellbat, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['HELLBAT_E'] = makeDirTextures(drawHellbat, TEAM_COLOR_ENEMY);
  UNIT_MATS['MULE_F'] = makeDirTextures(drawMULE, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['MULE_E'] = makeDirTextures(drawMULE, TEAM_COLOR_ENEMY);
  UNIT_MATS['VIKING_ASSAULT_F'] = makeDirTextures(drawVikingAssault, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['VIKING_ASSAULT_E'] = makeDirTextures(drawVikingAssault, TEAM_COLOR_ENEMY);
  UNIT_MATS['LIBERATOR_AG_F'] = makeDirTextures(drawLiberatorAG, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['LIBERATOR_AG_E'] = makeDirTextures(drawLiberatorAG, TEAM_COLOR_ENEMY);
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
  UNIT_MATS['VIPER_F'] = makeDirTextures(drawViper, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['VIPER_E'] = makeDirTextures(drawViper, TEAM_COLOR_ENEMY);
  UNIT_MATS['BROOD_LORD_F'] = makeDirTextures(drawBroodLord, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['BROOD_LORD_E'] = makeDirTextures(drawBroodLord, TEAM_COLOR_ENEMY);
  UNIT_MATS['ROACH_F']      = makeDirTextures(drawRoach,      TEAM_COLOR_FRIENDLY);
  UNIT_MATS['ROACH_E']      = makeDirTextures(drawRoach,      TEAM_COLOR_ENEMY);
  UNIT_MATS['HYDRALISK_F']  = makeDirTextures(drawHydralisk,  TEAM_COLOR_FRIENDLY);
  UNIT_MATS['HYDRALISK_E']  = makeDirTextures(drawHydralisk,  TEAM_COLOR_ENEMY);
  UNIT_MATS['MUTALISK_F']   = makeDirTextures(drawMutalisk,   TEAM_COLOR_FRIENDLY);
  UNIT_MATS['MUTALISK_E']   = makeDirTextures(drawMutalisk,   TEAM_COLOR_ENEMY);
  UNIT_MATS['UNKNOWN_F']    = makeDirTextures(drawEnemy,      TEAM_COLOR_FRIENDLY);
  UNIT_MATS['UNKNOWN_E']    = makeDirTextures(drawEnemy,      TEAM_COLOR_ENEMY);
  UNIT_MATS['PHOENIX_F'] = makeDirTextures(drawPhoenix, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['PHOENIX_E'] = makeDirTextures(drawPhoenix, TEAM_COLOR_ENEMY);
  UNIT_MATS['ORACLE_F'] = makeDirTextures(drawOracle, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['ORACLE_E'] = makeDirTextures(drawOracle, TEAM_COLOR_ENEMY);
  UNIT_MATS['TEMPEST_F'] = makeDirTextures(drawTempest, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['TEMPEST_E'] = makeDirTextures(drawTempest, TEAM_COLOR_ENEMY);
  UNIT_MATS['MOTHERSHIP_F'] = makeDirTextures(drawMothership, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['MOTHERSHIP_E'] = makeDirTextures(drawMothership, TEAM_COLOR_ENEMY);
  UNIT_MATS['WARP_PRISM_F'] = makeDirTextures(drawWarpPrism, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['WARP_PRISM_E'] = makeDirTextures(drawWarpPrism, TEAM_COLOR_ENEMY);
  UNIT_MATS['WARP_PRISM_PHASING_F'] = makeDirTextures(drawWarpPrismPhasing, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['WARP_PRISM_PHASING_E'] = makeDirTextures(drawWarpPrismPhasing, TEAM_COLOR_ENEMY);
  UNIT_MATS['INTERCEPTOR_F'] = makeDirTextures(drawInterceptor, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['INTERCEPTOR_E'] = makeDirTextures(drawInterceptor, TEAM_COLOR_ENEMY);
  UNIT_MATS['ADEPT_PHASE_SHIFT_F'] = makeDirTextures(drawAdeptPhaseShift, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['ADEPT_PHASE_SHIFT_E'] = makeDirTextures(drawAdeptPhaseShift, TEAM_COLOR_ENEMY);
  UNIT_MATS['DRONE_F'] = makeDirTextures(drawDrone, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['DRONE_E'] = makeDirTextures(drawDrone, TEAM_COLOR_ENEMY);
  UNIT_MATS['OVERLORD_F'] = makeDirTextures(drawOverlord, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['OVERLORD_E'] = makeDirTextures(drawOverlord, TEAM_COLOR_ENEMY);
  UNIT_MATS['OVERSEER_F'] = makeDirTextures(drawOverseer, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['OVERSEER_E'] = makeDirTextures(drawOverseer, TEAM_COLOR_ENEMY);
  UNIT_MATS['BANELING_F'] = makeDirTextures(drawBaneling, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['BANELING_E'] = makeDirTextures(drawBaneling, TEAM_COLOR_ENEMY);
  UNIT_MATS['LOCUST_F'] = makeDirTextures(drawLocust, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['LOCUST_E'] = makeDirTextures(drawLocust, TEAM_COLOR_ENEMY);
  UNIT_MATS['BROODLING_F'] = makeDirTextures(drawBroodling, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['BROODLING_E'] = makeDirTextures(drawBroodling, TEAM_COLOR_ENEMY);
  UNIT_MATS['INFESTED_TERRAN_F'] = makeDirTextures(drawInfestedTerran, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['INFESTED_TERRAN_E'] = makeDirTextures(drawInfestedTerran, TEAM_COLOR_ENEMY);
  UNIT_MATS['CHANGELING_F'] = makeDirTextures(drawChangeling, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['CHANGELING_E'] = makeDirTextures(drawChangeling, TEAM_COLOR_ENEMY);
  UNIT_MATS['AUTO_TURRET_F'] = makeDirTextures(drawAutoTurret, TEAM_COLOR_FRIENDLY);
  UNIT_MATS['AUTO_TURRET_E'] = makeDirTextures(drawAutoTurret, TEAM_COLOR_ENEMY);

  // Building materials — single texture per type (no directional variants; always friendly)
  function makeBuildingTexture(drawFn, size = 128) {
    const c = document.createElement('canvas');
    c.width = c.height = size;
    drawFn(c.getContext('2d'), size, 0, TEAM_COLOR_FRIENDLY);
    const tex = new THREE.CanvasTexture(c);
    tex.premultiplyAlpha = true;
    return new THREE.SpriteMaterial({ map: tex, transparent: true, depthWrite: true, alphaTest: 0.05 });
  }
  BUILDING_MATS['NEXUS']              = makeBuildingTexture(drawNexus);
  BUILDING_MATS['PYLON']              = makeBuildingTexture(drawPylon);
  BUILDING_MATS['GATEWAY']            = makeBuildingTexture(drawGateway);
  BUILDING_MATS['CYBERNETICS_CORE']   = makeBuildingTexture(drawCyberneticsCore);
  BUILDING_MATS['ASSIMILATOR']        = makeBuildingTexture(drawAssimilator);
  BUILDING_MATS['ROBOTICS_FACILITY']  = makeBuildingTexture(drawRoboticsFacility);
  BUILDING_MATS['STARGATE']           = makeBuildingTexture(drawStargate);
  BUILDING_MATS['FORGE']              = makeBuildingTexture(drawForge);
  BUILDING_MATS['TWILIGHT_COUNCIL']   = makeBuildingTexture(drawTwilightCouncil);
  BUILDING_MATS['UNKNOWN']            = makeBuildingTexture(drawUnknownBuilding);
  // New Protoss
  BUILDING_MATS['PHOTON_CANNON']      = makeBuildingTexture(drawPhotonCannon);
  BUILDING_MATS['SHIELD_BATTERY']     = makeBuildingTexture(drawShieldBattery);
  BUILDING_MATS['DARK_SHRINE']        = makeBuildingTexture(drawDarkShrine);
  BUILDING_MATS['TEMPLAR_ARCHIVES']   = makeBuildingTexture(drawTemplarArchives);
  BUILDING_MATS['FLEET_BEACON']       = makeBuildingTexture(drawFleetBeacon);
  BUILDING_MATS['ROBOTICS_BAY']       = makeBuildingTexture(drawRoboticsBay);
  // Terran
  BUILDING_MATS['COMMAND_CENTER']     = makeBuildingTexture(drawCommandCenter);
  BUILDING_MATS['ORBITAL_COMMAND']    = makeBuildingTexture(drawOrbitalCommand);
  BUILDING_MATS['PLANETARY_FORTRESS'] = makeBuildingTexture(drawPlanetaryFortress);
  BUILDING_MATS['SUPPLY_DEPOT']       = makeBuildingTexture(drawSupplyDepot);
  BUILDING_MATS['BARRACKS']           = makeBuildingTexture(drawBarracks);
  BUILDING_MATS['ENGINEERING_BAY']    = makeBuildingTexture(drawEngineeringBay);
  BUILDING_MATS['ARMORY']             = makeBuildingTexture(drawArmory);
  BUILDING_MATS['MISSILE_TURRET']     = makeBuildingTexture(drawMissileTurret);
  BUILDING_MATS['BUNKER']             = makeBuildingTexture(drawBunker);
  BUILDING_MATS['SENSOR_TOWER']       = makeBuildingTexture(drawSensorTower);
  BUILDING_MATS['GHOST_ACADEMY']      = makeBuildingTexture(drawGhostAcademy);
  BUILDING_MATS['FACTORY']            = makeBuildingTexture(drawFactory);
  BUILDING_MATS['STARPORT']           = makeBuildingTexture(drawStarport);
  BUILDING_MATS['FUSION_CORE']        = makeBuildingTexture(drawFusionCore);
  BUILDING_MATS['REFINERY']           = makeBuildingTexture(drawRefinery);
  // Zerg
  BUILDING_MATS['HATCHERY']           = makeBuildingTexture(drawHatchery);
  BUILDING_MATS['LAIR']               = makeBuildingTexture(drawLair);
  BUILDING_MATS['HIVE']               = makeBuildingTexture(drawHive);
  BUILDING_MATS['SPAWNING_POOL']      = makeBuildingTexture(drawSpawningPool);
  BUILDING_MATS['EVOLUTION_CHAMBER']  = makeBuildingTexture(drawEvolutionChamber);
  BUILDING_MATS['ROACH_WARREN']       = makeBuildingTexture(drawRoachWarren);
  BUILDING_MATS['BANELING_NEST']      = makeBuildingTexture(drawBanelingNest);
  BUILDING_MATS['SPINE_CRAWLER']      = makeBuildingTexture(drawSpineCrawler);
  BUILDING_MATS['SPORE_CRAWLER']      = makeBuildingTexture(drawSporeCrawler);
  BUILDING_MATS['HYDRALISK_DEN']      = makeBuildingTexture(drawHydraliskDen);
  BUILDING_MATS['LURKER_DEN']         = makeBuildingTexture(drawLurkerDen);
  BUILDING_MATS['INFESTATION_PIT']    = makeBuildingTexture(drawInfestationPit);
  BUILDING_MATS['SPIRE']              = makeBuildingTexture(drawSpire);
  BUILDING_MATS['GREATER_SPIRE']      = makeBuildingTexture(drawGreaterSpire);
  BUILDING_MATS['NYDUS_NETWORK']      = makeBuildingTexture(drawNydusNetwork);
  BUILDING_MATS['NYDUS_CANAL']        = makeBuildingTexture(drawNydusCanal);
  BUILDING_MATS['ULTRALISK_CAVERN']   = makeBuildingTexture(drawUltraliskCavern);
  BUILDING_MATS['EXTRACTOR']          = makeBuildingTexture(drawExtractor);
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

// ── Replay control bar ──────────────────────────────────────────────────────
async function initReplayControls() {
  // Only show in replay profile
  const meta = await fetchJson('/qa/current-map');
  if (!meta) return;

  const status = await fetchJson('/qa/replay/status');
  const totalLoops = status ? status.totalLoops : 1;

  const bar = document.createElement('div');
  bar.id = 'replay-bar';
  bar.innerHTML = `
    <button id="rb-rewind" title="Rewind to start">⏮</button>
    <button id="rb-pp" title="Play / Pause">⏸</button>
    <input id="rb-scrub" type="range" min="0" max="${totalLoops}" value="0" step="22">
    <span id="rb-time">0:00 / ${fmtLoop(totalLoops)}</span>
    <button class="rb-speed" data-x="0">½×</button>
    <button class="rb-speed rb-speed-active" data-x="1">1×</button>
    <button class="rb-speed" data-x="2">2×</button>
    <button class="rb-speed" data-x="4">4×</button>
  `;
  document.body.appendChild(bar);

  const style = document.createElement('style');
  style.textContent = `
    #replay-bar {
      position: fixed; bottom: 0; left: 0; width: 100%; height: 44px;
      background: rgba(0,0,0,0.82); display: flex; align-items: center;
      gap: 8px; padding: 0 14px; box-sizing: border-box; z-index: 200;
      color: #fff; font-family: monospace; font-size: 13px;
    }
    #replay-bar button {
      background: #2a2a2a; color: #ddd; border: 1px solid #555;
      border-radius: 4px; padding: 3px 9px; cursor: pointer; font-size: 13px;
    }
    #replay-bar button:hover { background: #444; }
    .rb-speed-active { background: #1a6fd4 !important; border-color: #3a8fee !important; color: #fff !important; }
    #rb-scrub { flex: 1; cursor: pointer; }
    #rb-time { min-width: 100px; text-align: center; color: #aaa; }
  `;
  document.head.appendChild(style);

  let playing = true;

  document.getElementById('rb-rewind').onclick = async () => {
    playing = false;
    document.getElementById('rb-pp').textContent = '▶';
    await fetch('/qa/replay/pause', { method: 'POST' });
    await fetch('/qa/replay/seek?loop=0', { method: 'POST' });
    document.getElementById('rb-scrub').value = 0;
    document.getElementById('rb-time').textContent = `0:00 / ${fmtLoop(totalLoops)}`;
  };

  document.getElementById('rb-pp').onclick = async () => {
    if (playing) {
      await fetch('/qa/replay/pause', { method: 'POST' });
      document.getElementById('rb-pp').textContent = '▶';
    } else {
      await fetch('/qa/replay/resume', { method: 'POST' });
      document.getElementById('rb-pp').textContent = '⏸';
    }
    playing = !playing;
  };

  const scrub = document.getElementById('rb-scrub');
  scrub.addEventListener('mouseup', async () => {
    await fetch(`/qa/replay/seek?loop=${scrub.value}`, { method: 'POST' });
    scrub.blur(); // return focus to document so WASD/arrow keys work again
  });

  document.querySelectorAll('.rb-speed').forEach(btn => {
    btn.onclick = async () => {
      const x = parseInt(btn.dataset.x, 10);
      await fetch(`/qa/replay/speed?multiplier=${x}`, { method: 'POST' });
      document.querySelectorAll('.rb-speed').forEach(b => b.classList.remove('rb-speed-active'));
      btn.classList.add('rb-speed-active');
    };
  });

  // Poll status every 500ms to update scrub position and time
  setInterval(async () => {
    if (!playing) return;
    const s = await fetchJson('/qa/replay/status');
    if (!s) return;
    scrub.value = s.loop;
    document.getElementById('rb-time').textContent =
      `${fmtLoop(s.loop)} / ${fmtLoop(s.totalLoops)}`;
  }, 500);
}

function fmtLoop(loop) {
  const secs = Math.floor(loop / 22.4);
  return `${Math.floor(secs / 60)}:${String(secs % 60).padStart(2, '0')}`;
}

init();
