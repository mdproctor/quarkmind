// visualizer.js — QuarkMind 3D visualizer (Three.js r128)

const TILE = 0.7;
const RECONNECT_MS = 2000;

let renderer, scene, camera;
let wsConnected = false;
let group2d, group3d;

window.__test = {
  threeReady:    () => !!renderer,
  wsConnected:   () => wsConnected,
  hudText:       () => document.getElementById('hud')?.textContent ?? '',
  unitCount:     () => group2d?.children.length ?? 0,
  enemyCount:    () => 0,
  buildingCount: () => 0,
  stagingCount:  () => 0,
  geyserCount:   () => 0,
  fogOpacity:    (x, z) => -1,
  fogVisible:    (x, z) => false,
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

function setupCamera() {}
function smoothCamera() {}
function setAngle(p, btn) {}
function setMode(m) {}

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

async function loadTerrain() {}
function connectWebSocket() {}
function initConfigPanel() {}

init();
