package io.quarkmind.qa;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import io.quarkmind.agent.AgentOrchestrator;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.SC2Engine;
import io.quarkmind.sc2.mock.SimulatedGame;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end render tests for the PixiJS visualizer.
 *
 * Uses Playwright to drive a real headless Chromium browser against the Quarkus
 * test server. Assertions are semantic (sprite counts, positions, HUD text) rather
 * than pixel-perfect screenshots, so they are robust to visual styling changes.
 *
 * Requires Chromium to be installed:
 *   mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="install chromium"
 *
 * Tests skip gracefully if the browser is not available (e.g. bare CI agents).
 *
 * Design principles for non-fragile UI tests:
 * - waitForFunction() everywhere — never Thread.sleep()
 * - Semantic assertions via window.__test — never CSS/XPath selectors on the canvas
 * - Canvas positions derived mathematically from game coordinates, never hardcoded guesses
 * - Pixel sampling only to detect invisible sprites (not to assert exact colours)
 */
@QuarkusTest
class VisualizerRenderTest {

    // Game coordinate → canvas pixel formula (mirrors tile() in visualizer.js):
    //   canvasX = tileX * SCALE                    (SCALE = 20)
    //   canvasY = (VIEWPORT_H - tileY) * SCALE     (VIEWPORT_H = 32)
    private static final int SCALE      = 20;
    private static final int VIEWPORT_H = 32;

    /** Background colour: #1a1a2e = rgb(26, 26, 46). A rendered sprite must not be this. */
    private static final Color BACKGROUND = new Color(26, 26, 46);

    @TestHTTPResource("/visualizer.html")
    URL pageUrl;

    @Inject AgentOrchestrator orchestrator;
    @Inject SC2Engine engine;
    @Inject SimulatedGame simulatedGame;

    static Playwright playwright;
    static Browser    browser;

    @BeforeAll
    static void launchBrowser() {
        playwright = Playwright.create();
        try {
            browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(true));
        } catch (PlaywrightException e) {
            playwright.close();
            playwright = null;
            assumeTrue(false,
                "Chromium not installed — skipping visualizer render tests.\n" +
                "Install with: mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI" +
                " -D exec.args=\"install chromium\"");
        }
    }

    @AfterAll
    static void closeBrowser() {
        if (browser    != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @BeforeEach
    void startGame() {
        orchestrator.startGame();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Open the visualizer page and wait until the PixiJS test hooks are ready.
     * window.__test is set synchronously in init() before loadAssets(), so it
     * becomes available after the PixiJS Application is initialised (~50 ms).
     */
    private Page openPage() {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        // 1. Wait for PixiJS init + WebSocket handshake (browser side).
        page.waitForFunction("() => window.__test && window.__test.wsConnected()",
            null, new Page.WaitForFunctionOptions().setTimeout(10_000));
        // 2. Trigger an observe and wait for the first message to arrive.
        //    ws.onopen fires in the browser before the server's @OnOpen handler calls
        //    addSession() — the observe proves end-to-end connectivity is ready.
        engine.observe();
        page.waitForFunction("() => window.__test.hudText() !== 'Connecting...'",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));
        return page;
    }

    /**
     * Trigger an observe and wait until the named layer has at least minCount sprites.
     * This replaces any Thread.sleep() — Playwright polls until the condition is true.
     */
    private void observeAndWait(Page page, String prefix, int minCount) {
        engine.observe();
        // Embed values directly — avoids Playwright Java arg-serialisation ambiguity.
        // prefix is always a known safe literal ('unit','building','geyser','enemy').
        page.waitForFunction(
            "() => window.__test.spriteCount('" + prefix + "') >= " + minCount,
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));
    }

    /** Derive the expected canvas X coordinate for a game tile X. */
    private static int canvasX(float tileX) { return Math.round(tileX * SCALE); }

    /** Derive the expected canvas Y coordinate for a game tile Y (flipped). */
    private static int canvasY(float tileY) { return Math.round((VIEWPORT_H - tileY) * SCALE); }

    /**
     * Tint test: a full-health probe sprite must have no tint (0xffffff = white = no tint).
     */
    @Test
    void fullHealthUnitHasNoTint() {
        Page page = openPage();
        observeAndWait(page, "unit", 12);

        @SuppressWarnings("unchecked")
        Map<String, Object> sprite = (Map<String, Object>) page.evaluate(
            "() => window.__test.sprite('unit:probe-0')");
        assertThat(sprite).isNotNull();
        // 0xffffff = 16777215 decimal
        assertThat(((Number) sprite.get("tint")).intValue())
            .as("Full-health probe must have no tint (0xffffff)")
            .isEqualTo(0xffffff);

        page.close();
    }

    /**
     * Tint test: a critically low-health probe must receive a red tint.
     * Uses SimulatedGame.setUnitHealth() to inject a low-health state.
     */
    @Test
    void lowHealthUnitHasRedTint() {
        Page page = openPage();

        // Set probe-0 to 5 HP (11% of 45 max) → triggers red tint (ratio < 0.3)
        simulatedGame.setUnitHealth("probe-0", 5);
        engine.observe();

        // Wait for browser to receive updated state with non-white tint
        page.waitForFunction(
            "() => { const s = window.__test.sprite('unit:probe-0'); return s && s.tint !== 0xffffff; }",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        @SuppressWarnings("unchecked")
        Map<String, Object> sprite = (Map<String, Object>) page.evaluate(
            "() => window.__test.sprite('unit:probe-0')");
        assertThat(((Number) sprite.get("tint")).intValue())
            .as("Low-health probe must have a non-white (red) tint")
            .isNotEqualTo(0xffffff);

        page.close();
    }

    /**
     * Disappear test: a unit removed from game state must vanish from the visualizer.
     * Uses SimulatedGame.removeUnit() to simulate death.
     */
    @Test
    void unitDisappearsWhenRemovedFromGameState() {
        Page page = openPage();
        observeAndWait(page, "unit", 12);

        // Remove probe-0 — simulates it dying in combat
        simulatedGame.removeUnit("probe-0");
        engine.observe();

        // Wait for sprite count to drop
        page.waitForFunction(
            "() => window.__test.spriteCount('unit') < 12",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate("() => window.__test.spriteCount('unit')")).intValue();
        assertThat(count).isEqualTo(11);

        page.close();
    }

    /**
     * Staging layer test: units in enemyStagingArea must render as blue-tinted sprites.
     * Uses SimulatedGame.addStagedUnitForTesting() to inject a staged enemy without
     * needing the %emulated profile — same pattern as setUnitHealth for combat tests.
     */
    @Test
    void enemyStagedUnitsRenderAtSpawn() {
        Page page = openPage();

        simulatedGame.addStagedUnitForTesting(UnitType.ZEALOT, new Point2d(26, 26));
        engine.observe();

        page.waitForFunction(
            "() => window.__test.spriteCount('staging') >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate(
            "() => window.__test.spriteCount('staging')")).intValue();
        assertThat(count).as("staging layer must have 1 sprite").isEqualTo(1);

        page.close();
    }

    /**
     * Staging count matches game state: two different unit types staged → two sprites.
     */
    @Test
    void stagedUnitCountMatchesGameState() {
        Page page = openPage();

        simulatedGame.addStagedUnitForTesting(UnitType.ZEALOT,  new Point2d(26,    26));
        simulatedGame.addStagedUnitForTesting(UnitType.STALKER, new Point2d(26.5f, 26));
        engine.observe();

        page.waitForFunction(
            "() => window.__test.spriteCount('staging') >= 2",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate(
            "() => window.__test.spriteCount('staging')")).intValue();
        assertThat(count).as("two staged units → two staging sprites").isEqualTo(2);

        page.close();
    }

    /**
     * Staging sprites disappear when the game state clears the staging area.
     * Simulates an attack being sent (staging → enemy).
     */
    @Test
    void stagedUnitsDisappearWhenStagingClears() {
        Page page = openPage();

        simulatedGame.addStagedUnitForTesting(UnitType.ZEALOT, new Point2d(26, 26));
        engine.observe();

        page.waitForFunction(
            "() => window.__test.spriteCount('staging') >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        simulatedGame.clearStagedUnitsForTesting(); // simulates attack sent
        engine.observe();

        page.waitForFunction(
            "() => window.__test.spriteCount('staging') === 0",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate(
            "() => window.__test.spriteCount('staging')")).intValue();
        assertThat(count).as("staging layer must be empty after clear").isEqualTo(0);

        page.close();
    }

    /**
     * Enemy render test: an enemy unit spawned into game state must appear as a sprite
     * at the correct canvas position (coordinate transform is the same as friendlies).
     * This is the only end-to-end test for the enemy rendering layer — previously untested.
     */
    @Test
    void enemyUnitRendersAtCorrectCanvasPosition() {
        Page page = openPage();

        simulatedGame.spawnEnemyUnit(UnitType.ZEALOT, new Point2d(14, 14));
        engine.observe();

        page.waitForFunction(
            "() => window.__test.spriteCount('enemy') >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate("() => window.__test.spriteCount('enemy')")).intValue();
        assertThat(count).as("one enemy Zealot must render").isEqualTo(1);

        // Sprite key format: "enemy:<tag>" — the first spawned enemy gets tag "enemy-200"
        @SuppressWarnings("unchecked")
        Map<String, Object> sprite = (Map<String, Object>) page.evaluate(
            "() => window.__test.sprite('enemy:enemy-200')");
        assertThat(sprite).as("enemy sprite must exist by tag").isNotNull();
        assertThat(((Number) sprite.get("x")).intValue())
            .as("enemy canvas X (tile 14 * 20 = 280)")
            .isEqualTo(canvasX(14));
        assertThat(((Number) sprite.get("y")).intValue())
            .as("enemy canvas Y ((32-14) * 20 = 360)")
            .isEqualTo(canvasY(14));

        page.close();
    }

    /**
     * Terrain shading test: a HIGH ground tile must render with a brownish fill,
     * not the bare canvas background.
     *
     * HIGH tiles (y >= 19) are drawn by loadTerrain() with colour 0x8B6914 (α=0.55)
     * blended over the background #1a1a2e. The blended red channel is well above the
     * background red value of 26, so asserting red > 50 is a robust threshold.
     *
     * Tile (5, 20) is HIGH. Canvas position uses the terrain Y formula:
     *   canvasX = 5 * 20 = 100,  canvasY = (32 - 20 - 1) * 20 = 220
     * Centre of that tile is sampled at (110, 230).
     *
     * Note: terrain is drawn at startup before the WebSocket connects, so waiting
     * for wsConnected() is sufficient before screenshotting.
     */
    @Test
    @Tag("browser")
    void highGroundTileRendersWithBrownShading() throws Exception {
        Page page = openPage();

        // Wait for PixiJS to flush the frame before screenshotting
        page.evaluate("() => new Promise(r => requestAnimationFrame(r))");

        ElementHandle canvas = page.querySelector("canvas");
        byte[] png = canvas.screenshot();
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));

        // HIGH ground tile (5, 20): terrain Y formula uses (VIEWPORT_H - wy - 1)
        // canvasX = 5 * 20 = 100, canvasY = (32 - 20 - 1) * 20 = 220
        // Sample centre of tile: (110, 230)
        int canvasX = 5 * SCALE;
        int canvasY = (VIEWPORT_H - 20 - 1) * SCALE;
        int sampleX = canvasX + SCALE / 2;
        int sampleY = canvasY + SCALE / 2;

        Color pixel = new Color(img.getRGB(sampleX, sampleY));

        // HIGH ground (0x8B6914 α=0.55) blended over background gives red ≈ 88.
        // Background red = 26. Assert > 50 to confirm shading, not bare background.
        assertThat(pixel.getRed())
            .as("HIGH ground tile at canvas (%d,%d) should be brownish, not background (red=26)",
                sampleX, sampleY)
            .isGreaterThan(50);

        page.close();
    }

    /** Extract minerals integer from "Minerals: 55   Gas: ..." HUD text. */
    private static int parseMinerals(String hud) {
        int idx = hud.indexOf("Minerals:");
        if (idx < 0) throw new AssertionError("HUD text missing 'Minerals:': " + hud);
        String rest = hud.substring(idx + "Minerals:".length()).trim();
        int end = 0;
        while (end < rest.length() && Character.isDigit(rest.charAt(end))) end++;
        return Integer.parseInt(rest.substring(0, end));
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /**
     * Smoke test: after the first observe, the three entity layers contain the
     * correct number of sprites matching the initial game state (12 probes, 1 nexus,
     * 2 geysers).
     */
    @Test
    void initialSpriteCounts() {
        Page page = openPage();
        observeAndWait(page, "unit", 12);

        int units     = ((Number) page.evaluate("() => window.__test.spriteCount('unit')")).intValue();
        int buildings = ((Number) page.evaluate("() => window.__test.spriteCount('building')")).intValue();
        int geysers   = ((Number) page.evaluate("() => window.__test.spriteCount('geyser')")).intValue();

        assertThat(units).as("unit sprites (probes)").isEqualTo(12);
        assertThat(buildings).as("building sprites (nexus)").isEqualTo(1);
        assertThat(geysers).as("geyser sprites").isEqualTo(2);

        page.close();
    }

    /**
     * Position test: the Nexus sprite is placed at the correct canvas pixel
     * derived from its game tile coordinate (8, 8).
     * If this fails the coordinate transform (Y-flip, scale) is broken.
     */
    @Test
    void nexusIsAtCorrectCanvasPosition() {
        Page page = openPage();
        observeAndWait(page, "building", 1);

        @SuppressWarnings("unchecked")
        Map<String, Object> nexus =
            (Map<String, Object>) page.evaluate("() => window.__test.sprite('building:nexus-0')");

        assertThat(nexus).as("nexus sprite must exist").isNotNull();
        assertThat(((Number) nexus.get("x")).intValue())
            .as("nexus canvas X (tile 8 * scale 20)")
            .isEqualTo(canvasX(8));
        assertThat(((Number) nexus.get("y")).intValue())
            .as("nexus canvas Y ((32-8) * scale 20)")
            .isEqualTo(canvasY(8));

        page.close();
    }

    /**
     * Position test: probe-0 starts at tile (9, 9) — verify canvas placement.
     */
    @Test
    void probeZeroIsAtCorrectCanvasPosition() {
        Page page = openPage();
        observeAndWait(page, "unit", 12);

        @SuppressWarnings("unchecked")
        Map<String, Object> probe =
            (Map<String, Object>) page.evaluate("() => window.__test.sprite('unit:probe-0')");

        assertThat(probe).as("probe-0 sprite must exist").isNotNull();
        assertThat(((Number) probe.get("x")).intValue())
            .as("probe-0 canvas X (tile 9 * 20 = 180)")
            .isEqualTo(canvasX(9));
        assertThat(((Number) probe.get("y")).intValue())
            .as("probe-0 canvas Y ((32-9) * 20 = 460)")
            .isEqualTo(canvasY(9));

        page.close();
    }

    /**
     * Circular masking test: unit sprites use the Container/mask approach (PixiJS 8 fix).
     *
     * With the correct fix (GE-0144), the Container has hasMask === true (the mask IS present
     * and clips the sprite circular). The old workaround was to remove the mask entirely
     * (hasMask === false), which produced rectangular portraits. The fix reinstates circular
     * portraits by applying the mask to the Container rather than the Sprite directly.
     *
     * Note: a corner-pixel test (assert corner is background after clipping) is intentionally
     * omitted — WebGL anti-aliasing at the mask edge produces sub-pixel bleeding that makes
     * exact pixel assertions at the circle boundary brittle across different renderers.
     * Visibility is covered by probePixelIsNotBackground; masking by hasMask === true.
     */
    @Test
    void unitSpritesAreCircularlyMasked() {
        Page page = openPage();
        observeAndWait(page, "unit", 12);

        // Container must have a mask applied (circular clipping is active)
        boolean allMasked = (boolean) page.evaluate(
            "() => Array.from({length: 12}, (_, i) => window.__test.sprite('unit:probe-' + i))" +
            "      .every(s => s !== null && s.hasMask)");
        assertThat(allMasked)
            .as("All unit sprites must have a container mask applied (circular clipping)")
            .isTrue();

        page.close();
    }

    /**
     * Visibility test: all unit sprites must have alpha > 0 and visible === true.
     * Catches transparency bugs without pixel comparison.
     */
    @Test
    void unitSpritesAreVisible() {
        Page page = openPage();
        observeAndWait(page, "unit", 12);

        boolean allVisible = (boolean) page.evaluate(
            "() => Array.from({length: 12}, (_, i) => window.__test.sprite('unit:probe-' + i))" +
            "      .every(s => s !== null && s.visible && s.alpha > 0)");

        assertThat(allVisible)
            .as("All unit sprites must be visible with alpha > 0")
            .isTrue();

        page.close();
    }

    /**
     * HUD test: the heads-up display text must contain all four expected fields
     * after the first observe.
     */
    @Test
    void hudTextContainsAllFields() {
        Page page = openPage();
        engine.observe();
        page.waitForFunction(
            "() => window.__test.hudText().includes('Minerals:')",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        String hud = (String) page.evaluate("() => window.__test.hudText()");

        assertThat(hud).contains("Minerals:");
        assertThat(hud).contains("Gas:");
        assertThat(hud).contains("Supply:");
        assertThat(hud).contains("Frame:");

        page.close();
    }

    /**
     * HUD update test: the mineral counter must increase after game ticks.
     * MockEngine adds +5 minerals per tick. Uses waitForFunction so no sleep needed.
     */
    @Test
    void hudMineralCountIncreasesWithTicks() {
        Page page = openPage();
        engine.observe();
        page.waitForFunction(
            "() => window.__test.hudText().includes('Minerals:')",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int before = parseMinerals((String) page.evaluate("() => window.__test.hudText()"));

        for (int i = 0; i < 5; i++) engine.tick();
        engine.observe();

        final int threshold = before;
        page.waitForFunction(
            "() => { const m = window.__test.hudText().match(/Minerals:\\s*(\\d+)/); " +
            "        return m && parseInt(m[1]) > " + threshold + "; }",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int after = parseMinerals((String) page.evaluate("() => window.__test.hudText()"));
        assertThat(after).as("minerals must increase after 5 ticks").isGreaterThan(before);

        page.close();
    }

    /**
     * Pixel sampling test: the canvas pixel at the nexus tile position must not
     * be the background colour (#1a1a2e). This catches sprites that exist in the
     * scene graph but are visually invisible (the PixiJS 8 mask bug would fail here).
     *
     * Uses Playwright screenshot rather than WebGL pixel extraction — works regardless
     * of renderer type and is immune to PixiJS API changes.
     */
    @Test
    @Tag("browser")
    void nexusPixelIsNotBackground() throws Exception {
        Page page = openPage();
        observeAndWait(page, "building", 1);

        // Wait for PixiJS to flush the WebGL frame before screenshotting
        page.evaluate("() => new Promise(r => requestAnimationFrame(r))");

        ElementHandle canvas = page.querySelector("canvas");
        byte[] png = canvas.screenshot();
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));

        // Nexus is centred at canvas (160, 480) — either the SC2Nexus.jpg sprite
        // or the fallback blue rectangle; neither should be the background colour.
        int cx = canvasX(8);  // 160
        int cy = canvasY(8);  // 480
        Color pixel = new Color(img.getRGB(cx, cy));

        assertThat(pixel.getRed())
            .as("Nexus pixel at (%d,%d) must not be background r=%d — sprite may be invisible",
                cx, cy, BACKGROUND.getRed())
            .isNotEqualTo(BACKGROUND.getRed());

        page.close();
    }

    /**
     * Pixel sampling test: same check for a probe sprite at tile (9, 9).
     * Probes are the entities most recently affected by the mask bug.
     */
    @Test
    @Tag("browser")
    void probePixelIsNotBackground() throws Exception {
        Page page = openPage();
        observeAndWait(page, "unit", 12);

        page.evaluate("() => new Promise(r => requestAnimationFrame(r))");

        ElementHandle canvas = page.querySelector("canvas");
        byte[] png = canvas.screenshot();
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));

        // probe-0 is centred at tile (9, 9) → canvas (180, 460)
        int cx = canvasX(9);  // 180
        int cy = canvasY(9);  // 460
        Color pixel = new Color(img.getRGB(cx, cy));

        assertThat(pixel.getRed())
            .as("Probe pixel at (%d,%d) must not be background — sprite may be invisible",
                cx, cy)
            .isNotEqualTo(BACKGROUND.getRed());

        page.close();
    }

    /**
     * Building count test: after a gameTick(), buildingMeshes must contain at least
     * one entry — the initial Nexus. Validates that syncUnits() → syncBuildings()
     * correctly populates buildingMeshes from state.myBuildings.
     */
    @Test
    @Tag("browser")
    void buildingCountMatchesGameState() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8000));
        orchestrator.gameTick();
        page.waitForTimeout(400);
        Number buildings = (Number) page.evaluate("() => window.__test.buildingCount()");
        assertTrue(buildings.intValue() >= 1,
            "Expected at least 1 building (Nexus), got " + buildings);
        page.close();
    }

    @Test
    @Tag("browser")
    void unitCountMatchesGameState() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8000));
        orchestrator.gameTick();
        page.waitForTimeout(400);
        Number units = (Number) page.evaluate("() => window.__test.unitCount()");
        assertTrue(units.intValue() >= 12,
            "Expected ≥12 units (SimulatedGame default), got " + units);
        page.close();
    }

    @Test
    @Tag("browser")
    void getDir4ReturnsFrontWhenCameraAlignedWithFacing() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8000));
        // Unit facing angle=0 (south/+z), camera at (0,10,10) — in front of unit
        Number dir = (Number) page.evaluate("""
            () => {
              const unitPos = new THREE.Vector3(0, 0, 0);
              const camPos  = new THREE.Vector3(0, 10, 10);
              return getDir4(0, unitPos, camPos);
            }
        """);
        assertEquals(0, dir.intValue(),
            "Camera in front of unit (facing angle=0, cam at +z) should give dir=0 (front)");
        page.close();
    }

    /**
     * Three.js bootstrap test: the WebGLRenderer must be initialised and
     * window.__test.threeReady() must return true after page load.
     */
    @Test
    @Tag("browser")
    void threeJsCanvasExists() {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));
        Object ready = page.evaluate("() => window.__test.threeReady()");
        assertTrue((Boolean) ready, "Three.js renderer not initialised");
        page.close();
    }

    /**
     * Terrain tiles test: loadTerrain() must populate the Three.js scene with
     * grid tile meshes (BoxGeometry + EdgesGeometry). A 64x64 grid produces
     * 64*64*2 = 8192 scene children (tiles + edge lines), far above the threshold
     * of 10 used here as a conservative smoke check.
     */
    @Test
    @Tag("browser")
    void terrainRendersGridTiles() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.terrainReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8000));
        Number count = (Number) page.evaluate("() => window._three.scene.children.length");
        assertTrue(count.intValue() > 10,
            "Expected terrain tiles in scene, got " + count);
        page.close();
    }
}
