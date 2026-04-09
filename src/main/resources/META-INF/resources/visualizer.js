// visualizer.js — QuarkMind live game visualizer (PixiJS 8)

const SCALE = 20;          // pixels per tile
const VIEWPORT_H = 32;     // tiles high (for Y-axis flip: game Y=0 is bottom, canvas Y=0 is top)
const RECONNECT_MS = 2000;

// Sprite assets loaded from the Quarkus sprite proxy
const ASSETS = [
    { alias: 'probe',   src: '/qa/sprites/SC2Probe.jpg' },
    { alias: 'nexus',   src: '/qa/sprites/SC2Nexus.jpg' },
    { alias: 'pylon',   src: '/qa/sprites/SC2Pylon.jpg' },
    { alias: 'gateway', src: '/qa/sprites/SC2Gateway.jpg' },
    { alias: 'zealot',  src: '/qa/sprites/SC2Zealot.jpg' },
    { alias: 'stalker', src: '/qa/sprites/SC2Stalker.jpg' },
];

// Map domain UnitType/BuildingType strings → sprite aliases
const UNIT_ALIAS = {
    PROBE: 'probe', ZEALOT: 'zealot', STALKER: 'stalker',
    MARINE: null, ZERGLING: null,
};
const BUILDING_ALIAS = { NEXUS: 'nexus', PYLON: 'pylon', GATEWAY: 'gateway' };
const BUILDING_SIZE  = { NEXUS: 64,      PYLON: 32,      GATEWAY: 48 };
const UNIT_RADIUS = 14;
const DEFAULT_BUILDING_SIZE = 36;

let hudText;
const activeSprites = new Map(); // "prefix:tag" → PIXI display object

/** Convert game tile coordinates to canvas pixels. Flips Y axis. */
function tile(x, y) {
    return { x: x * SCALE, y: (VIEWPORT_H - y) * SCALE };
}

/** Load all sprite assets from Quarkus sprite proxy. Logs warnings on failure. */
async function loadAssets() {
    for (const asset of ASSETS) {
        try {
            await PIXI.Assets.load(asset);
        } catch (e) {
            console.warn(`Sprite load failed: ${asset.alias} — using fallback shape`, e);
        }
    }
}

/**
 * Draw a static grid covering the 32×32 tile game area (640×640px).
 * The canvas (800×680px) is wider/taller — the right strip and bottom strip
 * outside the grid are intentionally empty (future sidebar; HUD below).
 */
function drawGrid(container) {
    const g = new PIXI.Graphics();
    for (let i = 0; i <= VIEWPORT_H; i++) {
        g.moveTo(i * SCALE, 0).lineTo(i * SCALE, VIEWPORT_H * SCALE);
        g.moveTo(0, i * SCALE).lineTo(VIEWPORT_H * SCALE, i * SCALE);
    }
    g.stroke({ width: 0.5, color: 0x2a2a4e });
    container.addChild(g);
}

/**
 * Create a circular-masked unit portrait using a PIXI.Container approach (PixiJS 8).
 *
 * PixiJS 8 gotcha (GE-0144): adding a Graphics mask as a child of an anchored Sprite
 * and setting sprite.mask makes the Sprite invisible — the mask's coordinate space
 * doesn't align with the anchor offset, clipping away all pixels.
 *
 * Fix: both the Sprite and the Graphics mask are children of a Container. The mask is
 * applied to the Container (not the Sprite). The Container is then positioned by syncLayer.
 *
 * If the texture is unavailable the fallback is a coloured circle — no mask needed
 * because Graphics shapes have no rectangular background to clip.
 */
function makeUnitSprite(alias, radius, tintColor) {
    const texture = alias ? PIXI.Assets.get(alias) : null;
    if (texture) {
        const container = new PIXI.Container();

        const sprite = new PIXI.Sprite(texture);
        sprite.width  = radius * 2;
        sprite.height = radius * 2;
        sprite.anchor.set(0.5);
        if (tintColor) sprite.tint = tintColor;

        const mask = new PIXI.Graphics();
        mask.circle(0, 0, radius).fill(0xffffff);

        container.addChild(sprite);
        container.addChild(mask);  // mask in same coordinate space as sprite
        container.mask = mask;     // mask on Container, not on Sprite

        return container;
    }
    // Fallback: coloured circle — Graphics has no rectangular background, no mask needed
    const g = new PIXI.Graphics();
    g.circle(0, 0, radius).fill(tintColor ?? 0x4488ff);
    return g;
}

/** Create a building sprite or fallback rectangle. */
function makeBuildingSprite(type) {
    const alias = BUILDING_ALIAS[type];
    const size  = BUILDING_SIZE[type] ?? DEFAULT_BUILDING_SIZE;
    const texture = alias ? PIXI.Assets.get(alias) : null;
    if (texture) {
        const sprite = new PIXI.Sprite(texture);
        sprite.width  = size;
        sprite.height = size;
        sprite.anchor.set(0.5);
        return sprite;
    }
    const g = new PIXI.Graphics();
    g.rect(-size / 2, -size / 2, size, size).fill(0x3366cc);
    return g;
}

/** Create a geyser marker (green circle). */
function makeGeyserSprite() {
    const g = new PIXI.Graphics();
    g.circle(0, 0, 12).fill(0x22cc66);
    return g;
}

/**
 * Returns a tint colour based on HP ratio, or null for full health (no tint).
 * Applied to both friendly and enemy unit sprites.
 */
function healthTint(health, maxHealth) {
    if (maxHealth <= 0 || health >= maxHealth) return null;
    const ratio = health / maxHealth;
    if (ratio > 0.6) return null;       // full colour — no tint
    if (ratio > 0.3) return 0xffcc44;  // yellow — wounded
    return 0xff3333;                    // red — critical
}

/**
 * Reconcile a layer's sprites against the received entity list.
 * Creates sprites for new entities, updates positions of existing ones,
 * destroys sprites for removed entities.
 */
function syncLayer(layer, entities, keyPrefix, spriteFactory) {
    const seen = new Set();
    for (const entity of entities) {
        const key = `${keyPrefix}:${entity.tag}`;
        seen.add(key);
        const pos = tile(entity.position.x, entity.position.y);
        if (activeSprites.has(key)) {
            const s = activeSprites.get(key);
            s.x = pos.x;
            s.y = pos.y;
            if (entity.health !== undefined && entity.maxHealth !== undefined) {
                const tint = healthTint(entity.health, entity.maxHealth);
                s.tint = tint ?? 0xffffff;
            }
        } else {
            const s = spriteFactory(entity);
            s.x = pos.x;
            s.y = pos.y;
            if (entity.health !== undefined && entity.maxHealth !== undefined) {
                const tint = healthTint(entity.health, entity.maxHealth);
                s.tint = tint ?? 0xffffff;
            }
            layer.addChild(s);
            activeSprites.set(key, s);
        }
    }
    for (const [key, sprite] of activeSprites.entries()) {
        if (key.startsWith(keyPrefix + ':') && !seen.has(key)) {
            layer.removeChild(sprite);
            sprite.destroy();
            activeSprites.delete(key);
        }
    }
}

/** Apply one GameState snapshot to the scene. */
function updateScene(state) {
    syncLayer(
        window._layers.resource,
        state.geysers,
        'geyser',
        () => makeGeyserSprite()
    );
    syncLayer(
        window._layers.building,
        state.myBuildings,
        'building',
        e => makeBuildingSprite(e.type)
    );
    syncLayer(
        window._layers.unit,
        state.myUnits,
        'unit',
        e => makeUnitSprite(UNIT_ALIAS[e.type] ?? null, UNIT_RADIUS, 0x88bbff)
    );
    syncLayer(
        window._layers.enemy,
        state.enemyUnits,
        'enemy',
        e => makeUnitSprite(UNIT_ALIAS[e.type] ?? null, UNIT_RADIUS, 0xff4444)
    );

    hudText.text =
        `Minerals: ${state.minerals}   Gas: ${state.vespene}` +
        `   Supply: ${state.supplyUsed}/${state.supply}` +
        `   Frame: ${state.gameFrame}`;
}

let wsConnected = false;

/** Open WebSocket; auto-reconnect on close. */
function connect() {
    const ws = new WebSocket(`ws://${window.location.host}/ws/gamestate`);
    ws.onopen    = () => { wsConnected = true; };
    ws.onmessage = e => {
        try { updateScene(JSON.parse(e.data)); }
        catch (err) { console.warn('Bad message', err); }
    };
    ws.onerror = () => ws.close();
    ws.onclose = () => {
        wsConnected = false;
        hudText.text = 'Disconnected — reconnecting...';
        setTimeout(connect, RECONNECT_MS);
    };
}

/**
 * Initialise the config panel sidebar.
 * Probes GET /qa/emulated/config — shows panel only in %emulated profile,
 * hides silently in %mock, %sc2, etc. (endpoint returns 404 in those profiles).
 */
function initConfigPanel() {
    const panel       = document.getElementById('config-panel');
    const speedSlider = document.getElementById('cfg-speed');
    const speedVal    = document.getElementById('cfg-speed-val');
    const status      = document.getElementById('cfg-status');

    // Probe the endpoint — show panel only if it exists (%emulated profile)
    fetch('/qa/emulated/config')
        .then(r => { if (!r.ok) return null; panel.style.display = 'block'; return r.json(); })
        .then(cfg => {
            if (!cfg) return;
            document.getElementById('cfg-wave-frame').value = cfg.waveSpawnFrame;
            document.getElementById('cfg-unit-count').value = cfg.waveUnitCount;
            document.getElementById('cfg-unit-type').value  = cfg.waveUnitType;
            speedSlider.value    = cfg.unitSpeed;
            speedVal.textContent = cfg.unitSpeed;
        })
        .catch(() => {}); // not in %emulated — panel stays hidden

    // Speed is live — sends immediately on slider move (no restart needed)
    speedSlider.addEventListener('input', () => {
        speedVal.textContent = speedSlider.value;
        sendConfig({ unitSpeed: parseFloat(speedSlider.value) });
    });

    // Apply button — sends wave + speed config (wave takes effect on next restart)
    document.getElementById('cfg-apply').addEventListener('click', () => {
        sendConfig(currentConfig()).then(() => showStatus('Applied — restart to activate wave'));
    });

    // Restart — apply config then call /sc2/start
    document.getElementById('cfg-restart').addEventListener('click', () => {
        sendConfig(currentConfig())
            .then(() => fetch('/sc2/start', { method: 'POST' }))
            .then(() => showStatus('Game restarted'))
            .catch(() => showStatus('Restart failed', true));
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
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(partial),
        }).then(r => r.json()).catch(() => showStatus('Update failed', true));
    }

    function showStatus(msg, isError = false) {
        status.textContent = msg;
        status.style.color = isError ? '#ff4444' : '#88ff88';
        setTimeout(() => { status.textContent = ''; }, 2500);
    }
}

/** Entry point — called once on page load. */
async function init() {
    const app = new PIXI.Application();
    await app.init({ width: 800, height: 680, background: 0x1a1a2e });
    document.getElementById('game').appendChild(app.canvas);

    // Layers — order matters (bottom to top)
    const background = new PIXI.Container();
    const resource   = new PIXI.Container();
    const building   = new PIXI.Container();
    const unit       = new PIXI.Container();
    const enemy      = new PIXI.Container();
    const hud        = new PIXI.Container();
    app.stage.addChild(background, resource, building, unit, enemy, hud);
    window._layers = { resource, building, unit, enemy };

    drawGrid(background);

    // HUD text anchored to bottom of canvas
    hudText = new PIXI.Text({
        text: 'Connecting...',
        style: { fill: 0xccccff, fontSize: 13, fontFamily: 'monospace' }
    });
    hudText.x = 8;
    hudText.y = 648;
    hud.addChild(hudText);

    // ---------------------------------------------------------------------------
    // Test hooks — used by VisualizerRenderTest (@QuarkusTest + Playwright).
    // Set before loadAssets() so tests can access them without waiting for
    // network fetches. Do not use in production code.
    // ---------------------------------------------------------------------------
    window.__pixiApp = app;
    window.__test = {
        /** Count sprites by entity type prefix ('unit', 'building', 'geyser', 'enemy'). */
        spriteCount: (prefix) =>
            [...activeSprites.keys()].filter(k => k.startsWith(prefix + ':')).length,

        /** Serialisable metadata for a single sprite looked up by key ('building:nexus-0'). */
        sprite: (key) => {
            const s = activeSprites.get(key);
            if (!s) return null;
            return {
                x:       s.x,
                y:       s.y,
                alpha:   s.alpha   ?? 1,
                visible: s.visible !== false,
                hasMask: s.mask != null,      // true → masked sprite (see mask-bug history)
                tint:    s.tint    ?? 0xffffff,   // 0xffffff = no tint (full health)
            };
        },

        /** Current HUD text — includes minerals, gas, supply, frame. */
        hudText: () => hudText?.text ?? '',

        /** True if the sprite texture was loaded successfully (not a fallback shape). */
        assetLoaded: (alias) => PIXI.Assets.get(alias) !== undefined,

        /** True once the WebSocket handshake completes. Use in waitForFunction before triggering observe(). */
        wsConnected: () => wsConnected,
    };

    await loadAssets();
    connect();
    initConfigPanel();
}

init();
