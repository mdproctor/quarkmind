package io.quarkmind.qa;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import io.quarkmind.agent.AgentOrchestrator;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.SC2Engine;
import io.quarkmind.sc2.mock.SimulatedGame;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end render tests for the Three.js visualizer.
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
 * - Canvas positions derived via window.__test.worldToScreen(), never hardcoded guesses
 * - Pixel sampling avoided — worldToScreen() on-screen bounds check is sufficient
 */
@QuarkusTest
class VisualizerRenderTest {

    // Three.js world coordinate formula (mirrors visualizer.js):
    //   worldX = tileX * TILE - HALF_W   (TILE = 0.7, HALF_W = 64*0.7/2 = 22.4)
    //   worldZ = tileZ * TILE - HALF_H   (HALF_H = 64*0.7/2 = 22.4)
    private static final double TILE   = 0.7;
    private static final double HALF_W = 64 * TILE / 2;  // 22.4
    private static final double HALF_H = 64 * TILE / 2;  // 22.4
    private static final String TEAM_COLOR_FRIENDLY = "#4488ff";
    private static final String TEAM_COLOR_ENEMY    = "#ff4422";

    @TestHTTPResource("/visualizer.html")
    URL pageUrl;

    @TestHTTPResource("/sc2/showcase")
    URL showcaseUrl;

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
     * Open the visualizer page and wait until the Three.js test hooks are ready.
     * window.__test is set synchronously in visualizer.js before the renderer is
     * initialised, so it becomes available after the WebGLRenderer is up.
     */
    private Page openPage() {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        // 1. Wait for Three.js init + WebSocket handshake (browser side).
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
        // prefix is always a known safe literal ('unit','building','geyser','enemy','staging').
        page.waitForFunction(
            "() => window.__test.spriteCount('" + prefix + "') >= " + minCount,
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));
    }

    /** Convert a game tile X to Three.js world X. */
    private static double worldX(double tileX) { return tileX * TILE - HALF_W; }

    /** Convert a game tile Z to Three.js world Z. */
    private static double worldZ(double tileZ) { return tileZ * TILE - HALF_H; }

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
    // Tests — disabled (PixiJS-specific, not applicable to Three.js renderer)
    // -------------------------------------------------------------------------

    /**
     * Circular masking is a PixiJS Container/mask feature.
     * Three.js SpriteMaterial does not use a mask — unit sprites are full-quad billboards.
     */
    @Test
    @Disabled("Circular masking is PixiJS-specific — not applicable to Three.js sprites")
    @Tag("browser")
    void unitSpritesAreCircularlyMasked() {
    }

    /**
     * Health tint via SpriteMaterial colour is not implemented in E14.
     * The sprite() hook returns userData.tint which defaults to 0xffffff for all units.
     */
    @Test
    @Disabled("Health tint via SpriteMaterial colour not implemented in E14")
    @Tag("browser")
    void fullHealthUnitHasNoTint() {
    }

    /**
     * Health tint via SpriteMaterial colour is not implemented in E14.
     */
    @Test
    @Disabled("Health tint via SpriteMaterial colour not implemented in E14")
    @Tag("browser")
    void lowHealthUnitHasRedTint() {
    }

    /**
     * Pixel sampling at terrain tile coordinates used PixiJS canvas coordinate formulas
     * (canvasY = (VIEWPORT_H - tileY - 1) * SCALE) that do not apply to a 3D WebGL scene.
     * Terrain rendering is covered by terrainRendersGridTiles() via scene.children count.
     */
    @Test
    @Disabled("PixiJS canvas-coordinate pixel sampling — not applicable to Three.js 3D terrain")
    @Tag("browser")
    void highGroundTileRendersWithBrownShading() {
    }

    /**
     * Pixel sampling at nexus tile position used PixiJS canvas coordinate formulas.
     * Position correctness is now covered by nexusIsAtCorrectCanvasPosition() via worldToScreen().
     */
    @Test
    @Disabled("PixiJS canvas-coordinate pixel sampling — replaced by worldToScreen() position test")
    @Tag("browser")
    void nexusPixelIsNotBackground() {
    }

    /**
     * Pixel sampling at probe tile position used PixiJS canvas coordinate formulas.
     * Position correctness is now covered by probeZeroIsAtCorrectCanvasPosition() via worldToScreen().
     */
    @Test
    @Disabled("PixiJS canvas-coordinate pixel sampling — replaced by worldToScreen() position test")
    @Tag("browser")
    void probePixelIsNotBackground() {
    }

    // -------------------------------------------------------------------------
    // Tests — active
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

        int units     = ((Number) page.evaluate("() => window.__test.unitCount()")).intValue();
        int buildings = ((Number) page.evaluate("() => window.__test.buildingCount()")).intValue();
        int geysers   = ((Number) page.evaluate("() => window.__test.geyserCount()")).intValue();

        assertThat(units).as("unit sprites (probes)").isEqualTo(12);
        assertThat(buildings).as("building sprites (nexus)").isEqualTo(1);
        assertThat(geysers).as("geyser sprites").isEqualTo(2);

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

        // Wait for unit count to drop
        page.waitForFunction(
            "() => window.__test.unitCount() < 12",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate("() => window.__test.unitCount()")).intValue();
        assertThat(count).isEqualTo(11);

        page.close();
    }

    /**
     * Position test: the Nexus mesh is placed at the correct screen position
     * derived from its game tile coordinate (8, 8).
     *
     * Nexus at tile (8,8): world = (8*0.7 - 22.4, 8*0.7 - 22.4) = (-16.8, -16.8).
     * worldToScreen() projects through the Three.js camera to get screen pixel coords.
     * We assert the result is on-screen rather than exact pixels (camera orbit makes
     * exact coordinates impractical to compute on the Java side).
     */
    @Test
    void nexusIsAtCorrectCanvasPosition() {
        Page page = openPage();
        observeAndWait(page, "building", 1);

        // Nexus at tile (8,8): world coords (-16.8, -16.8)
        double wx = worldX(8);  // -16.8
        double wz = worldZ(8);  // -16.8

        @SuppressWarnings("unchecked")
        Map<?, ?> pos = (Map<?, ?>) page.evaluate(
            "() => window.__test.worldToScreen(" + wx + ", " + wz + ")");
        int sx = ((Number) pos.get("x")).intValue();
        int sy = ((Number) pos.get("y")).intValue();
        int W  = ((Number) page.evaluate("() => window.innerWidth")).intValue();
        int H  = ((Number) page.evaluate("() => window.innerHeight")).intValue();

        assertTrue(sx > 0 && sx < W && sy > 0 && sy < H,
            "Nexus world (" + wx + "," + wz + ") should project on-screen at ("
                + sx + "," + sy + ") within (" + W + "x" + H + ")");

        page.close();
    }

    /**
     * Position test: probe-0 starts at tile (9, 9) — verify screen placement.
     *
     * Probe at tile (9,9): world = (9*0.7 - 22.4, 9*0.7 - 22.4) = (-16.1, -16.1).
     */
    @Test
    void probeZeroIsAtCorrectCanvasPosition() {
        Page page = openPage();
        observeAndWait(page, "unit", 12);

        // probe-0 at tile (9,9): world coords (-16.1, -16.1)
        double wx = worldX(9);  // -16.1
        double wz = worldZ(9);  // -16.1

        @SuppressWarnings("unchecked")
        Map<?, ?> pos = (Map<?, ?>) page.evaluate(
            "() => window.__test.worldToScreen(" + wx + ", " + wz + ")");
        int sx = ((Number) pos.get("x")).intValue();
        int sy = ((Number) pos.get("y")).intValue();
        int W  = ((Number) page.evaluate("() => window.innerWidth")).intValue();
        int H  = ((Number) page.evaluate("() => window.innerHeight")).intValue();

        assertTrue(sx > 0 && sx < W && sy > 0 && sy < H,
            "probe-0 world (" + wx + "," + wz + ") should project on-screen at ("
                + sx + "," + sy + ") within (" + W + "x" + H + ")");

        page.close();
    }

    /**
     * Enemy render test: an enemy unit spawned into game state must appear as a sprite
     * at the correct screen position (coordinate transform is the same as friendlies).
     *
     * Enemy Zealot at tile (14,14): world = (14*0.7 - 22.4, 14*0.7 - 22.4) = (-12.6, -12.6).
     */
    @Test
    void enemyUnitRendersAtCorrectCanvasPosition() {
        Page page = openPage();

        simulatedGame.spawnEnemyUnit(UnitType.ZEALOT, new Point2d(14, 14));
        engine.observe();

        page.waitForFunction(
            "() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate("() => window.__test.enemyCount()")).intValue();
        assertThat(count).as("one enemy Zealot must render").isEqualTo(1);

        // Enemy Zealot at tile (14,14): world coords (-12.6, -12.6)
        double wx = worldX(14);  // -12.6
        double wz = worldZ(14);  // -12.6

        @SuppressWarnings("unchecked")
        Map<?, ?> pos = (Map<?, ?>) page.evaluate(
            "() => window.__test.worldToScreen(" + wx + ", " + wz + ")");
        int sx = ((Number) pos.get("x")).intValue();
        int sy = ((Number) pos.get("y")).intValue();
        int W  = ((Number) page.evaluate("() => window.innerWidth")).intValue();
        int H  = ((Number) page.evaluate("() => window.innerHeight")).intValue();

        assertTrue(sx > 0 && sx < W && sy > 0 && sy < H,
            "Enemy Zealot world (" + wx + "," + wz + ") should project on-screen at ("
                + sx + "," + sy + ") within (" + W + "x" + H + ")");

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
     * Staging layer test: units in enemyStagingArea must render as sprites.
     * Uses SimulatedGame.addStagedUnitForTesting() to inject a staged enemy.
     */
    @Test
    void enemyStagedUnitsRenderAtSpawn() {
        Page page = openPage();

        simulatedGame.addStagedUnitForTesting(UnitType.ZEALOT, new Point2d(26, 26));
        engine.observe();

        page.waitForFunction(
            "() => window.__test.stagingCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate(
            "() => window.__test.stagingCount()")).intValue();
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
            "() => window.__test.stagingCount() >= 2",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate(
            "() => window.__test.stagingCount()")).intValue();
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
            "() => window.__test.stagingCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        simulatedGame.clearStagedUnitsForTesting(); // simulates attack sent
        engine.observe();

        page.waitForFunction(
            "() => window.__test.stagingCount() === 0",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate(
            "() => window.__test.stagingCount()")).intValue();
        assertThat(count).as("staging layer must be empty after clear").isEqualTo(0);

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
        page.waitForFunction("() => window.__test.buildingCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(3000));
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
        page.waitForFunction("() => window.__test.unitCount() >= 12",
            null, new Page.WaitForFunctionOptions().setTimeout(3000));
        Number units = (Number) page.evaluate("() => window.__test.unitCount()");
        assertTrue(units.intValue() >= 12,
            "Expected ≥12 units (SimulatedGame default), got " + units);
        page.close();
    }

    @Test
    @Tag("browser")
    void enemyUnitsRenderWhenPresent() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8000));
        simulatedGame.spawnEnemyUnit(UnitType.ZERGLING,
            new Point2d(20, 20));
        orchestrator.gameTick();
        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(3000));
        Number enemies = (Number) page.evaluate("() => window.__test.enemyCount()");
        assertTrue(enemies.intValue() >= 1, "Expected ≥1 enemy unit, got " + enemies);
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
     * Config panel visibility test: the config panel must be hidden in %mock profile
     * (the /qa/emulated/config endpoint returns 404 — panel only shows in %emulated).
     */
    @Test
    @Tag("browser")
    void configPanelHiddenInMockProfile() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8000));
        page.waitForTimeout(600); // let /qa/emulated/config fetch settle
        String display = (String) page.evaluate(
            "() => document.getElementById('config-panel').style.display");
        assertNotEquals("block", display,
            "Config panel should be hidden in %mock profile (emulated/config returns 404)");
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

    /**
     * UNIT_MATS must use TYPE_F / TYPE_E key format after Task 3 refactor.
     * Existing Protoss units and the UNKNOWN fallback must all be registered.
     */
    @Test
    @Tag("browser")
    void unitMatsCoversAllRegisteredTypes() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        @SuppressWarnings("unchecked")
        List<String> keys = (List<String>) page.evaluate("() => window.__test.unitMatsKeys()");

        assertThat(keys).contains(
            "PROBE_F", "PROBE_E",
            "ZEALOT_F", "ZEALOT_E",
            "STALKER_F", "STALKER_E",
            "UNKNOWN_F", "UNKNOWN_E"
        );
        page.close();
    }

    /**
     * Each existing draw function must produce non-transparent output for all
     * 4 directions and both team colours. Catches regressions when teamColor
     * parameter is added to existing draw function signatures.
     */
    @Test
    @Tag("browser")
    void existingDrawFunctionsProduceNonTransparentOutputForAllDirsAndTeams() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        String[] fns     = {"drawProbe", "drawZealot", "drawStalker", "drawEnemy"};
        String[] colors  = {TEAM_COLOR_FRIENDLY, TEAM_COLOR_ENEMY};
        int[]    dirs    = {0, 1, 2, 3};

        for (String fn : fns) {
          for (String color : colors) {
            for (int dir : dirs) {
              Number alpha = (Number) page.evaluate(
                  "() => window.__test.smokeTestDrawFn('" + fn + "', " + dir + ", '" + color + "')");
              assertThat(alpha.intValue())
                  .as(fn + " dir=" + dir + " team=" + color + " centre alpha")
                  .isGreaterThan(0);
            }
          }
        }
        page.close();
    }


    /**
     * drawMarine must produce non-transparent output for all 4 directions
     * and both team colours. Returns -1 (not > 0) until drawMarine is defined.
     */
    @Test
    @Tag("browser")
    void marineDrawFunctionProducesNonTransparentOutputForAllDirsAndTeams() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        for (String color : new String[]{TEAM_COLOR_FRIENDLY, TEAM_COLOR_ENEMY}) {
          for (int dir = 0; dir < 4; dir++) {
            Number alpha = (Number) page.evaluate(
                "() => window.__test.smokeTestDrawFn('drawMarine', " + dir + ", '" + color + "')");
            assertThat(alpha.intValue())
                .as("drawMarine dir=" + dir + " team=" + color)
                .isGreaterThan(0);
          }
        }
        page.close();
    }

    /**
     * Happy path: Marine enemy unit spawns and renders as a sprite.
     * UNIT_MATS['MARINE_E'] must be registered and dispatch must resolve it.
     */
    @Test
    @Tag("browser")
    void marineEnemySpawnsAndRendersInVisualizer() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.MARINE, new Point2d(20, 20));
        engine.observe();

        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate("() => window.__test.enemyCount()")).intValue();
        assertThat(count).as("one Marine enemy must render").isEqualTo(1);
        page.close();
    }

    /**
     * Smoke test: drawMarauder must produce non-transparent output for all 4 directions
     * and both team colours. Returns -1 (not > 0) until drawMarauder is defined.
     */
    @Test
    @Tag("browser")
    void marauderDrawFunctionProducesNonTransparentOutputForAllDirsAndTeams() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        for (String color : new String[]{TEAM_COLOR_FRIENDLY, TEAM_COLOR_ENEMY}) {
          for (int dir = 0; dir < 4; dir++) {
            Number alpha = (Number) page.evaluate(
                "() => window.__test.smokeTestDrawFn('drawMarauder', " + dir + ", '" + color + "')");
            assertThat(alpha.intValue())
                .as("drawMarauder dir=" + dir + " team=" + color)
                .isGreaterThan(0);
          }
        }
        page.close();
    }

    /**
     * Happy path: Marauder enemy unit spawns and renders as a sprite.
     * UNIT_MATS['MARAUDER_E'] must be registered and dispatch must resolve it.
     */
    @Test
    @Tag("browser")
    void marauderEnemySpawnsAndRendersInVisualizer() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.MARAUDER, new Point2d(22, 22));
        engine.observe();

        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate("() => window.__test.enemyCount()")).intValue();
        assertThat(count).as("one Marauder enemy must render").isEqualTo(1);
        page.close();
    }

    /**
     * Smoke test: drawMedivac must produce non-transparent output for all 4 directions
     * and both team colours. Returns -1 (not > 0) until drawMedivac is defined.
     */
    @Test
    @Tag("browser")
    void medivacDrawFunctionProducesNonTransparentOutputForAllDirsAndTeams() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        for (String color : new String[]{TEAM_COLOR_FRIENDLY, TEAM_COLOR_ENEMY}) {
          for (int dir = 0; dir < 4; dir++) {
            Number alpha = (Number) page.evaluate(
                "() => window.__test.smokeTestDrawFn('drawMedivac', " + dir + ", '" + color + "')");
            assertThat(alpha.intValue())
                .as("drawMedivac dir=" + dir + " team=" + color)
                .isGreaterThan(0);
          }
        }
        page.close();
    }

    /**
     * Happy path: Medivac enemy unit spawns and renders as a sprite.
     * UNIT_MATS['MEDIVAC_E'] must be registered and dispatch must resolve it.
     */
    @Test
    @Tag("browser")
    void medivacEnemySpawnsAndRendersInVisualizer() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.MEDIVAC, new Point2d(24, 24));
        engine.observe();

        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate("() => window.__test.enemyCount()")).intValue();
        assertThat(count).as("one Medivac enemy must render").isEqualTo(1);
        page.close();
    }

    /**
     * Correctness: Medivac must spawn at a higher Three.js world Y than a ground unit.
     * TILE=0.7: ground Y = 0.7*0.65 = 0.455; flying Y = 0.7*1.5 = 1.05.
     * Uses two separate browser pages to avoid shared enemy state between Marine and Medivac.
     */
    @Test
    @Tag("browser")
    void medivacSpawnsHigherThanGroundUnit() throws Exception {
        // Test Marine Y first (ground unit)
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.MARINE, new Point2d(20, 20));
        engine.observe();
        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        @SuppressWarnings("unchecked")
        List<Double> marineYs = ((List<?>) page.evaluate("() => window.__test.allEnemyWorldY()"))
            .stream().map(v -> ((Number) v).doubleValue()).toList();
        double marineY = marineYs.get(0);
        page.close();

        // Now test Medivac Y
        orchestrator.startGame(); // reset game state
        Page page2 = browser.newPage();
        page2.navigate(pageUrl.toString());
        page2.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.MEDIVAC, new Point2d(20, 20));
        engine.observe();
        page2.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        @SuppressWarnings("unchecked")
        List<Double> medivacYs = ((List<?>) page2.evaluate("() => window.__test.allEnemyWorldY()"))
            .stream().map(v -> ((Number) v).doubleValue()).toList();
        double medivacY = medivacYs.get(0);
        page2.close();

        assertThat(medivacY)
            .as("Medivac world Y (%.3f) must be higher than Marine world Y (%.3f)".formatted(medivacY, marineY))
            .isGreaterThan(marineY + 0.3); // 1.05 vs 0.455 — margin of 0.3 is safe
    }

    /**
     * Config panel dropdown must include all three Terran wave types.
     * Structural HTML test — asserts options exist in the DOM regardless of panel visibility
     * (panel only shows in %emulated profile, but options are always present in the HTML).
     */
    @Test
    @Tag("browser")
    void configPanelDropdownContainsTerranWaveTypes() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        int marineCount   = page.locator("#cfg-unit-type option[value='MARINE']").count();
        int marauderCount = page.locator("#cfg-unit-type option[value='MARAUDER']").count();
        int medivacCount  = page.locator("#cfg-unit-type option[value='MEDIVAC']").count();

        assertThat(marineCount).as("MARINE option must exist").isEqualTo(1);
        assertThat(marauderCount).as("MARAUDER option must exist").isEqualTo(1);
        assertThat(medivacCount).as("MEDIVAC option must exist").isEqualTo(1);
        page.close();
    }

    /**
     * UNIT_MATS must always include UNKNOWN_F and UNKNOWN_E fallback entries.
     * These are used when an unrecognised unit type reaches syncUnitLayer.
     */
    @Test
    @Tag("browser")
    void unitMatsContainsUnknownFallbacks() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        @SuppressWarnings("unchecked")
        List<String> keys = (List<String>) page.evaluate("() => window.__test.unitMatsKeys()");
        assertThat(keys).contains("UNKNOWN_F", "UNKNOWN_E");
        page.close();
    }

    /**
     * Robustness: UNKNOWN_E must be a 4-element SpriteMaterial array.
     * This ensures the fallback dispatch never returns null or an incomplete array.
     */
    @Test
    @Tag("browser")
    void unknownUnitTypeFallbackIsA4ElementArray() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        Number len = (Number) page.evaluate(
            "() => (UNIT_MATS['UNKNOWN_E'] ?? []).length");
        assertThat(len.intValue())
            .as("UNKNOWN_E must be a 4-element SpriteMaterial array")
            .isEqualTo(4);
        page.close();
    }

    /**
     * Coverage: all 11 unit types × 2 teams must be registered after initSpriteMaterials().
     * This catches any unit type missing from initSpriteMaterials registrations.
     */
    @Test
    @Tag("browser")
    void unitMatsCoversAllSevenTypesWithBothTeams() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        @SuppressWarnings("unchecked")
        List<String> keys = (List<String>) page.evaluate("() => window.__test.unitMatsKeys()");
        assertThat(keys).contains(
            "PROBE_F",     "PROBE_E",
            "ZEALOT_F",    "ZEALOT_E",
            "STALKER_F",   "STALKER_E",
            "MARINE_F",    "MARINE_E",
            "MARAUDER_F",  "MARAUDER_E",
            "MEDIVAC_F",   "MEDIVAC_E",
            "ZERGLING_F",  "ZERGLING_E",
            "ROACH_F",     "ROACH_E",
            "HYDRALISK_F", "HYDRALISK_E",
            "MUTALISK_F",  "MUTALISK_E",
            "UNKNOWN_F",   "UNKNOWN_E"
        );
        page.close();
    }

    /**
     * Full-loop smoke test: exercises 20 game ticks — unit movement, fog updates,
     * sprite direction switching — and asserts no JS errors occur and the HUD keeps
     * updating throughout.
     */
    @Test
    @Tag("browser")
    void fullLoopRunsWithoutJsErrors() throws Exception {
        Page page = browser.newPage();
        List<String> errors = new ArrayList<>();
        page.onPageError(e -> errors.add(e));
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8000));
        // Run 20 ticks — exercises unit movement, fog updates, sprite direction switching
        for (int i = 0; i < 20; i++) {
            orchestrator.gameTick();
            Thread.sleep(50);
        }
        page.waitForTimeout(400);
        assertTrue(errors.isEmpty(), "No JS errors expected in visualizer: " + errors);
        String hud = (String) page.evaluate("() => window.__test.hudText()");
        assertTrue(hud.contains("Frame:"), "HUD should still be updating after 20 ticks: " + hud);
        page.close();
    }

    /**
     * drawZergling must produce non-transparent output for all 4 directions
     * and both team colours. Returns -1 (not > 0) until drawZergling is defined.
     */
    @Test
    @Tag("browser")
    void zerglingDrawFunctionProducesNonTransparentOutputForAllDirsAndTeams() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        for (String color : new String[]{TEAM_COLOR_FRIENDLY, TEAM_COLOR_ENEMY}) {
          for (int dir = 0; dir < 4; dir++) {
            Number alpha = (Number) page.evaluate(
                "() => window.__test.smokeTestDrawFn('drawZergling', " + dir + ", '" + color + "')");
            assertThat(alpha.intValue())
                .as("drawZergling dir=" + dir + " team=" + color)
                .isGreaterThan(0);
          }
        }
        page.close();
    }

    /**
     * Happy path: Zergling enemy unit spawns and renders as a sprite.
     * UNIT_MATS['ZERGLING_E'] must be registered and dispatch must resolve it.
     */
    @Test
    @Tag("browser")
    void zerglingEnemySpawnsAndRendersInVisualizer() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.ZERGLING, new Point2d(20, 20));
        engine.observe();

        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate("() => window.__test.enemyCount()")).intValue();
        assertThat(count).as("one Zergling enemy must render").isEqualTo(1);
        page.close();
    }

    /**
     * drawRoach must produce non-transparent output for all 4 directions
     * and both team colours. Returns -1 (not > 0) until drawRoach is defined.
     */
    @Test
    @Tag("browser")
    void roachDrawFunctionProducesNonTransparentOutputForAllDirsAndTeams() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        for (String color : new String[]{TEAM_COLOR_FRIENDLY, TEAM_COLOR_ENEMY}) {
          for (int dir = 0; dir < 4; dir++) {
            Number alpha = (Number) page.evaluate(
                "() => window.__test.smokeTestDrawFn('drawRoach', " + dir + ", '" + color + "')");
            assertThat(alpha.intValue()).as("drawRoach dir=" + dir + " team=" + color).isGreaterThan(0);
          }
        }
        page.close();
    }

    /**
     * Happy path: Roach enemy unit spawns and renders as a sprite.
     * UNIT_MATS['ROACH_E'] must be registered and dispatch must resolve it.
     */
    @Test
    @Tag("browser")
    void roachEnemySpawnsAndRendersInVisualizer() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.ROACH, new Point2d(22, 22));
        engine.observe();

        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate("() => window.__test.enemyCount()")).intValue();
        assertThat(count).as("one Roach enemy must render").isEqualTo(1);
        page.close();
    }

    /**
     * drawHydralisk must produce non-transparent output for all 4 directions
     * and both team colours. Returns -1 (not > 0) until drawHydralisk is defined.
     */
    @Test
    @Tag("browser")
    void hydraliskDrawFunctionProducesNonTransparentOutputForAllDirsAndTeams() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        for (String color : new String[]{TEAM_COLOR_FRIENDLY, TEAM_COLOR_ENEMY}) {
          for (int dir = 0; dir < 4; dir++) {
            Number alpha = (Number) page.evaluate(
                "() => window.__test.smokeTestDrawFn('drawHydralisk', " + dir + ", '" + color + "')");
            assertThat(alpha.intValue()).as("drawHydralisk dir=" + dir + " team=" + color).isGreaterThan(0);
          }
        }
        page.close();
    }

    /**
     * Happy path: Hydralisk enemy unit spawns and renders as a sprite.
     * UNIT_MATS['HYDRALISK_E'] must be registered and dispatch must resolve it.
     */
    @Test
    @Tag("browser")
    void hydraliskEnemySpawnsAndRendersInVisualizer() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.HYDRALISK, new Point2d(24, 24));
        engine.observe();

        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate("() => window.__test.enemyCount()")).intValue();
        assertThat(count).as("one Hydralisk enemy must render").isEqualTo(1);
        page.close();
    }

    /**
     * Invariant: every object in the Three.js scene must be within the map bounds.
     *
     * The SC2 map is 64x64 tiles × TILE=0.7 = 44.8 world units, centred at origin.
     * Max valid world coordinate: ±23. Max valid Y: 5 (allows for buildings + flying units).
     *
     * Catches off-map geometry like the Pylon position overflow bug (tile y=1752 →
     * world z=1204) which appeared as purple rectangles floating in the distance.
     * Any future positioning bug — wrong tile calculation, missing bounds check,
     * stale mesh not cleaned up — will fail here without needing to look at the UI.
     */
    @Test
    @Tag("browser")
    void allSceneObjectsAreWithinMapBounds() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        // Run 10 ticks so the AI has a chance to build/train
        for (int i = 0; i < 10; i++) orchestrator.gameTick();
        engine.observe();
        page.waitForFunction("() => window.__test.buildingCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        @SuppressWarnings("unchecked")
        List<Map<?,?>> outliers = (List<Map<?,?>>) page.evaluate("""
            () => {
              const out = [];
              const MAX_XZ = 23, MAX_Y = 5;
              window._three.scene.traverse(obj => {
                if (!obj.isMesh && !obj.isSprite) return;
                const p = obj.getWorldPosition(new THREE.Vector3());
                if (Math.abs(p.x) > MAX_XZ || Math.abs(p.z) > MAX_XZ || p.y > MAX_Y || p.y < -1) {
                  out.push({
                    type: obj.type,
                    x: p.x.toFixed(1), y: p.y.toFixed(1), z: p.z.toFixed(1),
                    color: obj.material?.color?.getHexString() ?? 'none'
                  });
                }
              });
              return out;
            }
        """);

        assertThat(outliers)
            .as("Scene objects outside map bounds — indicates a tile position overflow or " +
                "stale mesh not cleaned up: " + outliers)
            .isEmpty();

        page.close();
    }

    /**
     * drawMutalisk must produce non-transparent output for all 4 directions
     * and both team colours. Returns -1 (not > 0) until drawMutalisk is defined.
     */
    @Test
    @Tag("browser")
    void mutaliskDrawFunctionProducesNonTransparentOutputForAllDirsAndTeams() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        for (String color : new String[]{TEAM_COLOR_FRIENDLY, TEAM_COLOR_ENEMY}) {
          for (int dir = 0; dir < 4; dir++) {
            Number alpha = (Number) page.evaluate(
                "() => window.__test.smokeTestDrawFn('drawMutalisk', " + dir + ", '" + color + "')");
            assertThat(alpha.intValue()).as("drawMutalisk dir=" + dir + " team=" + color).isGreaterThan(0);
          }
        }
        page.close();
    }

    /**
     * Happy path: Mutalisk enemy unit spawns and renders as a sprite.
     * UNIT_MATS['MUTALISK_E'] must be registered and dispatch must resolve it.
     */
    @Test
    @Tag("browser")
    void mutaliskEnemySpawnsAndRendersInVisualizer() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.MUTALISK, new Point2d(20, 20));
        engine.observe();

        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate("() => window.__test.enemyCount()")).intValue();
        assertThat(count).as("one Mutalisk enemy must render").isEqualTo(1);
        page.close();
    }

    /**
     * Correctness: Mutalisk must spawn at a higher Three.js world Y than a ground Zergling.
     * TILE=0.7: ground Y = 0.7*0.65 = 0.455; flying Y = 0.7*1.5 = 1.05.
     * Uses two separate browser pages to avoid shared enemy state.
     */
    @Test
    @Tag("browser")
    void mutaliskSpawnsHigherThanGroundUnit() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.ZERGLING, new Point2d(20, 20));
        engine.observe();
        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        @SuppressWarnings("unchecked")
        List<Double> zerglingYs = ((List<?>) page.evaluate("() => window.__test.allEnemyWorldY()"))
            .stream().map(v -> ((Number) v).doubleValue()).toList();
        double zerglingY = zerglingYs.get(0);
        page.close();

        orchestrator.startGame();
        Page page2 = browser.newPage();
        page2.navigate(pageUrl.toString());
        page2.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.MUTALISK, new Point2d(20, 20));
        engine.observe();
        page2.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        @SuppressWarnings("unchecked")
        List<Double> mutaliskYs = ((List<?>) page2.evaluate("() => window.__test.allEnemyWorldY()"))
            .stream().map(v -> ((Number) v).doubleValue()).toList();
        double mutaliskY = mutaliskYs.get(0);
        page2.close();

        assertThat(mutaliskY)
            .as("Mutalisk Y (%.3f) must be higher than Zergling Y (%.3f)".formatted(mutaliskY, zerglingY))
            .isGreaterThan(zerglingY + 0.3);
    }

    @Test
    @Tag("browser")
    void ghostDrawFunctionProducesNonTransparentOutputForAllDirsAndTeams() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        for (String color : new String[]{TEAM_COLOR_FRIENDLY, TEAM_COLOR_ENEMY}) {
          for (int dir = 0; dir < 4; dir++) {
            Number alpha = (Number) page.evaluate(
                "() => window.__test.smokeTestDrawFn('drawGhost', " + dir + ", '" + color + "')");
            assertThat(alpha.intValue()).as("drawGhost dir=" + dir + " team=" + color).isGreaterThan(0);
          }
        }
        page.close();
    }

    @Test
    @Tag("browser")
    void ghostEnemySpawnsAndRendersInVisualizer() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.GHOST, new Point2d(20, 20));
        engine.observe();

        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate("() => window.__test.enemyCount()")).intValue();
        assertThat(count).as("one Ghost enemy must render").isEqualTo(1);
        page.close();
    }

    @Test
    @Tag("browser")
    void cycloneDrawFunctionProducesNonTransparentOutputForAllDirsAndTeams() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        for (String color : new String[]{TEAM_COLOR_FRIENDLY, TEAM_COLOR_ENEMY}) {
          for (int dir = 0; dir < 4; dir++) {
            Number alpha = (Number) page.evaluate(
                "() => window.__test.smokeTestDrawFn('drawCyclone', " + dir + ", '" + color + "')");
            assertThat(alpha.intValue()).as("drawCyclone dir=" + dir + " team=" + color).isGreaterThan(0);
          }
        }
        page.close();
    }

    @Test
    @Tag("browser")
    void cycloneEnemySpawnsAndRendersInVisualizer() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.CYCLONE, new Point2d(20, 20));
        engine.observe();

        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate("() => window.__test.enemyCount()")).intValue();
        assertThat(count).as("one Cyclone enemy must render").isEqualTo(1);
        page.close();
    }

    @Test
    @Tag("browser")
    void widowMineDrawFunctionProducesNonTransparentOutputForAllDirsAndTeams() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        for (String color : new String[]{TEAM_COLOR_FRIENDLY, TEAM_COLOR_ENEMY}) {
          for (int dir = 0; dir < 4; dir++) {
            Number alpha = (Number) page.evaluate(
                "() => window.__test.smokeTestDrawFn('drawWidowMine', " + dir + ", '" + color + "')");
            assertThat(alpha.intValue()).as("drawWidowMine dir=" + dir + " team=" + color).isGreaterThan(0);
          }
        }
        page.close();
    }

    @Test
    @Tag("browser")
    void widowMineEnemySpawnsAndRendersInVisualizer() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.WIDOW_MINE, new Point2d(20, 20));
        engine.observe();

        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate("() => window.__test.enemyCount()")).intValue();
        assertThat(count).as("one Widow Mine enemy must render").isEqualTo(1);
        page.close();
    }

    @Test
    @Tag("browser")
    void siegeTankDrawFunctionProducesNonTransparentOutputForAllDirsAndTeams() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        for (String color : new String[]{TEAM_COLOR_FRIENDLY, TEAM_COLOR_ENEMY}) {
          for (int dir = 0; dir < 4; dir++) {
            Number alpha = (Number) page.evaluate(
                "() => window.__test.smokeTestDrawFn('drawSiegeTank', " + dir + ", '" + color + "')");
            assertThat(alpha.intValue()).as("drawSiegeTank dir=" + dir + " team=" + color).isGreaterThan(0);
          }
        }
        page.close();
    }

    @Test
    @Tag("browser")
    void siegeTankEnemySpawnsAndRendersInVisualizer() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.SIEGE_TANK, new Point2d(20, 20));
        engine.observe();

        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate("() => window.__test.enemyCount()")).intValue();
        assertThat(count).as("one Siege Tank enemy must render").isEqualTo(1);
        page.close();
    }

    @Test
    @Tag("browser")
    void thorDrawFunctionProducesNonTransparentOutputForAllDirsAndTeams() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        for (String color : new String[]{TEAM_COLOR_FRIENDLY, TEAM_COLOR_ENEMY}) {
          for (int dir = 0; dir < 4; dir++) {
            Number alpha = (Number) page.evaluate(
                "() => window.__test.smokeTestDrawFn('drawThor', " + dir + ", '" + color + "')");
            assertThat(alpha.intValue()).as("drawThor dir=" + dir + " team=" + color).isGreaterThan(0);
          }
        }
        page.close();
    }

    @Test
    @Tag("browser")
    void thorEnemySpawnsAndRendersInVisualizer() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.THOR, new Point2d(20, 20));
        engine.observe();

        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate("() => window.__test.enemyCount()")).intValue();
        assertThat(count).as("one Thor enemy must render").isEqualTo(1);
        page.close();
    }

    @Test
    @Tag("browser")
    void vikingDrawFunctionProducesNonTransparentOutputForAllDirsAndTeams() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        for (String color : new String[]{TEAM_COLOR_FRIENDLY, TEAM_COLOR_ENEMY}) {
          for (int dir = 0; dir < 4; dir++) {
            Number alpha = (Number) page.evaluate(
                "() => window.__test.smokeTestDrawFn('drawViking', " + dir + ", '" + color + "')");
            assertThat(alpha.intValue()).as("drawViking dir=" + dir + " team=" + color).isGreaterThan(0);
          }
        }
        page.close();
    }

    @Test
    @Tag("browser")
    void vikingEnemySpawnsAndRendersInVisualizer() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.VIKING, new Point2d(20, 20));
        engine.observe();

        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate("() => window.__test.enemyCount()")).intValue();
        assertThat(count).as("one Viking enemy must render").isEqualTo(1);
        page.close();
    }

    @Test
    @Tag("browser")
    void vikingSpawnsHigherThanGroundUnit() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.MARINE, new Point2d(20, 20));
        engine.observe();
        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        @SuppressWarnings("unchecked")
        List<Double> marineYs = ((List<?>) page.evaluate("() => window.__test.allEnemyWorldY()"))
            .stream().map(v -> ((Number) v).doubleValue()).toList();
        double marineY = marineYs.get(0);
        page.close();

        orchestrator.startGame();
        Page page2 = browser.newPage();
        page2.navigate(pageUrl.toString());
        page2.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.VIKING, new Point2d(20, 20));
        engine.observe();
        page2.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        @SuppressWarnings("unchecked")
        List<Double> vikingYs = ((List<?>) page2.evaluate("() => window.__test.allEnemyWorldY()"))
            .stream().map(v -> ((Number) v).doubleValue()).toList();
        double vikingY = vikingYs.get(0);
        page2.close();

        assertThat(vikingY)
            .as("Viking Y (%.3f) must be higher than Marine Y (%.3f)".formatted(vikingY, marineY))
            .isGreaterThan(marineY + 0.3);
    }

    @Test
    @Tag("browser")
    void ravenDrawFunctionProducesNonTransparentOutputForAllDirsAndTeams() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        for (String color : new String[]{TEAM_COLOR_FRIENDLY, TEAM_COLOR_ENEMY}) {
          for (int dir = 0; dir < 4; dir++) {
            Number alpha = (Number) page.evaluate(
                "() => window.__test.smokeTestDrawFn('drawRaven', " + dir + ", '" + color + "')");
            assertThat(alpha.intValue()).as("drawRaven dir=" + dir + " team=" + color).isGreaterThan(0);
          }
        }
        page.close();
    }

    @Test
    @Tag("browser")
    void ravenEnemySpawnsAndRendersInVisualizer() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.RAVEN, new Point2d(20, 20));
        engine.observe();

        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate("() => window.__test.enemyCount()")).intValue();
        assertThat(count).as("one Raven enemy must render").isEqualTo(1);
        page.close();
    }

    @Test
    @Tag("browser")
    void ravenSpawnsHigherThanGroundUnit() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.MARINE, new Point2d(20, 20));
        engine.observe();
        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        @SuppressWarnings("unchecked")
        double marineY = ((List<?>) page.evaluate("() => window.__test.allEnemyWorldY()"))
            .stream().map(v -> ((Number) v).doubleValue()).toList().get(0);
        page.close();

        orchestrator.startGame();
        Page page2 = browser.newPage();
        page2.navigate(pageUrl.toString());
        page2.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.RAVEN, new Point2d(20, 20));
        engine.observe();
        page2.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        @SuppressWarnings("unchecked")
        double ravenY = ((List<?>) page2.evaluate("() => window.__test.allEnemyWorldY()"))
            .stream().map(v -> ((Number) v).doubleValue()).toList().get(0);
        page2.close();

        assertThat(ravenY).as("Raven Y (%.3f) must be higher than Marine Y (%.3f)".formatted(ravenY, marineY))
            .isGreaterThan(marineY + 0.3);
    }

    @Test
    @Tag("browser")
    void bansheeDrawFunctionProducesNonTransparentOutputForAllDirsAndTeams() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        for (String color : new String[]{TEAM_COLOR_FRIENDLY, TEAM_COLOR_ENEMY}) {
          for (int dir = 0; dir < 4; dir++) {
            Number alpha = (Number) page.evaluate(
                "() => window.__test.smokeTestDrawFn('drawBanshee', " + dir + ", '" + color + "')");
            assertThat(alpha.intValue()).as("drawBanshee dir=" + dir + " team=" + color).isGreaterThan(0);
          }
        }
        page.close();
    }

    @Test
    @Tag("browser")
    void bansheeEnemySpawnsAndRendersInVisualizer() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.BANSHEE, new Point2d(20, 20));
        engine.observe();

        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate("() => window.__test.enemyCount()")).intValue();
        assertThat(count).as("one Banshee enemy must render").isEqualTo(1);
        page.close();
    }

    @Test
    @Tag("browser")
    void bansheeSpawnsHigherThanGroundUnit() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.MARINE, new Point2d(20, 20));
        engine.observe();
        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        @SuppressWarnings("unchecked")
        double marineY = ((List<?>) page.evaluate("() => window.__test.allEnemyWorldY()"))
            .stream().map(v -> ((Number) v).doubleValue()).toList().get(0);
        page.close();

        orchestrator.startGame();
        Page page2 = browser.newPage();
        page2.navigate(pageUrl.toString());
        page2.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.BANSHEE, new Point2d(20, 20));
        engine.observe();
        page2.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        @SuppressWarnings("unchecked")
        double bansheeY = ((List<?>) page2.evaluate("() => window.__test.allEnemyWorldY()"))
            .stream().map(v -> ((Number) v).doubleValue()).toList().get(0);
        page2.close();

        assertThat(bansheeY).as("Banshee Y (%.3f) must be higher than Marine Y (%.3f)".formatted(bansheeY, marineY))
            .isGreaterThan(marineY + 0.3);
    }

    @Test
    @Tag("browser")
    void liberatorDrawFunctionProducesNonTransparentOutputForAllDirsAndTeams() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        for (String color : new String[]{TEAM_COLOR_FRIENDLY, TEAM_COLOR_ENEMY}) {
          for (int dir = 0; dir < 4; dir++) {
            Number alpha = (Number) page.evaluate(
                "() => window.__test.smokeTestDrawFn('drawLiberator', " + dir + ", '" + color + "')");
            assertThat(alpha.intValue()).as("drawLiberator dir=" + dir + " team=" + color).isGreaterThan(0);
          }
        }
        page.close();
    }

    @Test
    @Tag("browser")
    void liberatorEnemySpawnsAndRendersInVisualizer() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.LIBERATOR, new Point2d(20, 20));
        engine.observe();

        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate("() => window.__test.enemyCount()")).intValue();
        assertThat(count).as("one Liberator enemy must render").isEqualTo(1);
        page.close();
    }

    @Test
    @Tag("browser")
    void liberatorSpawnsHigherThanGroundUnit() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.MARINE, new Point2d(20, 20));
        engine.observe();
        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        @SuppressWarnings("unchecked")
        double marineY = ((List<?>) page.evaluate("() => window.__test.allEnemyWorldY()"))
            .stream().map(v -> ((Number) v).doubleValue()).toList().get(0);
        page.close();

        orchestrator.startGame();
        Page page2 = browser.newPage();
        page2.navigate(pageUrl.toString());
        page2.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.LIBERATOR, new Point2d(20, 20));
        engine.observe();
        page2.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        @SuppressWarnings("unchecked")
        double liberatorY = ((List<?>) page2.evaluate("() => window.__test.allEnemyWorldY()"))
            .stream().map(v -> ((Number) v).doubleValue()).toList().get(0);
        page2.close();

        assertThat(liberatorY).as("Liberator Y (%.3f) must be higher than Marine Y (%.3f)".formatted(liberatorY, marineY))
            .isGreaterThan(marineY + 0.3);
    }

    @Test
    @Tag("browser")
    void battlecruiserDrawFunctionProducesNonTransparentOutputForAllDirsAndTeams() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        for (String color : new String[]{TEAM_COLOR_FRIENDLY, TEAM_COLOR_ENEMY}) {
          for (int dir = 0; dir < 4; dir++) {
            Number alpha = (Number) page.evaluate(
                "() => window.__test.smokeTestDrawFn('drawBattlecruiser', " + dir + ", '" + color + "')");
            assertThat(alpha.intValue()).as("drawBattlecruiser dir=" + dir + " team=" + color).isGreaterThan(0);
          }
        }
        page.close();
    }

    @Test
    @Tag("browser")
    void battlecruiserEnemySpawnsAndRendersInVisualizer() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.BATTLECRUISER, new Point2d(20, 20));
        engine.observe();

        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate("() => window.__test.enemyCount()")).intValue();
        assertThat(count).as("one Battlecruiser enemy must render").isEqualTo(1);
        page.close();
    }

    @Test
    @Tag("browser")
    void battlecruiserSpawnsHigherThanGroundUnit() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.MARINE, new Point2d(20, 20));
        engine.observe();
        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        @SuppressWarnings("unchecked")
        double marineY = ((List<?>) page.evaluate("() => window.__test.allEnemyWorldY()"))
            .stream().map(v -> ((Number) v).doubleValue()).toList().get(0);
        page.close();

        orchestrator.startGame();
        Page page2 = browser.newPage();
        page2.navigate(pageUrl.toString());
        page2.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.BATTLECRUISER, new Point2d(20, 20));
        engine.observe();
        page2.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        @SuppressWarnings("unchecked")
        double bcY = ((List<?>) page2.evaluate("() => window.__test.allEnemyWorldY()"))
            .stream().map(v -> ((Number) v).doubleValue()).toList().get(0);
        page2.close();

        assertThat(bcY).as("Battlecruiser Y (%.3f) must be higher than Marine Y (%.3f)".formatted(bcY, marineY))
            .isGreaterThan(marineY + 0.3);
    }

    /**
     * Showcase validation: POST /sc2/showcase must render all 10 units with sprites above
     * terrain surface and all objects within map bounds.
     *
     * Prevents the recurring class of showcase regressions:
     *   1. Unit count — all 10 enemies rendered (catches fog placement, broken endpoint,
     *      units off-screen or not pushed to browser)
     *   2. Y positions — every enemy sprite above TERRAIN_SURFACE_Y (catches ground-sinking
     *      regressions where sprite Y < terrain height; reads TERRAIN_SURFACE_Y from the
     *      live JS so the threshold is profile-aware)
     *   3. Map bounds — no mesh/sprite outside ±23 world units or y > 5 (catches tile-
     *      position overflow like the Pylon y=1752 bug that produced off-screen geometry)
     *
     * Note: runs in %test (mock) profile — no fog, flat terrain. The Y check catches
     * gross regressions (y ≤ TERRAIN_SURFACE_Y). Subtle emulated-mode sinking and fog
     * placement still require manual verification per the Showcase Validation section of
     * CLAUDE.md.
     */
    @Test
    @Tag("browser")
    void showcaseRendersAllUnitsAboveTerrainSurface() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        // Seed via the QA endpoint — tests the full HTTP → game state → broadcast → render path
        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
            .uri(showcaseUrl.toURI())
            .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
            .build();
        http.send(req, java.net.http.HttpResponse.BodyHandlers.discarding());

        // 1. All 20 enemies rendered
        page.waitForFunction("() => window.__test.enemyCount() >= 20",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));
        int count = ((Number) page.evaluate("() => window.__test.enemyCount()")).intValue();
        assertThat(count).as("all 20 showcase enemy units must render").isEqualTo(20);

        // 2. No unit sunk at or below terrain surface
        double terrainSurfaceY = ((Number) page.evaluate("() => TERRAIN_SURFACE_Y")).doubleValue();
        @SuppressWarnings("unchecked")
        List<Double> ys = ((List<?>) page.evaluate("() => window.__test.allEnemyWorldY()"))
            .stream().map(v -> ((Number) v).doubleValue()).toList();
        for (double y : ys) {
            assertThat(y)
                .as("enemy sprite Y %.3f must be above terrain surface %.3f".formatted(y, terrainSurfaceY))
                .isGreaterThan(terrainSurfaceY);
        }

        // 3. No objects outside map bounds (±23 world units, y ≤ 5)
        @SuppressWarnings("unchecked")
        List<Map<?,?>> outliers = (List<Map<?,?>>) page.evaluate("""
            () => {
              const out = [];
              const MAX_XZ = 23, MAX_Y = 5;
              window._three.scene.traverse(obj => {
                if (!obj.isMesh && !obj.isSprite) return;
                const p = obj.getWorldPosition(new THREE.Vector3());
                if (Math.abs(p.x) > MAX_XZ || Math.abs(p.z) > MAX_XZ || p.y > MAX_Y || p.y < -1) {
                  out.push({type: obj.type, x: p.x.toFixed(1), y: p.y.toFixed(1), z: p.z.toFixed(1)});
                }
              });
              return out;
            }
        """);
        assertThat(outliers)
            .as("no showcase objects outside map bounds — indicates position overflow or stale mesh: " + outliers)
            .isEmpty();

        page.close();
    }

    @Test
    @Tag("browser")
    void sentryDrawFunctionProducesNonTransparentOutputForAllDirsAndTeams() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        for (String color : new String[]{TEAM_COLOR_FRIENDLY, TEAM_COLOR_ENEMY}) {
          for (int dir = 0; dir < 4; dir++) {
            Number alpha = (Number) page.evaluate(
                "() => window.__test.smokeTestDrawFn('drawSentry', " + dir + ", '" + color + "')");
            assertThat(alpha.intValue()).as("drawSentry dir=" + dir + " team=" + color).isGreaterThan(0);
          }
        }
        page.close();
    }

    @Test
    @Tag("browser")
    void sentryEnemySpawnsAndRendersInVisualizer() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.SENTRY, new Point2d(20, 20));
        engine.observe();

        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate("() => window.__test.enemyCount()")).intValue();
        assertThat(count).as("one Sentry enemy must render").isEqualTo(1);
        page.close();
    }

    @Test
    @Tag("browser")
    void adeptDrawFunctionProducesNonTransparentOutputForAllDirsAndTeams() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        for (String color : new String[]{TEAM_COLOR_FRIENDLY, TEAM_COLOR_ENEMY}) {
          for (int dir = 0; dir < 4; dir++) {
            Number alpha = (Number) page.evaluate(
                "() => window.__test.smokeTestDrawFn('drawAdept', " + dir + ", '" + color + "')");
            assertThat(alpha.intValue()).as("drawAdept dir=" + dir + " team=" + color).isGreaterThan(0);
          }
        }
        page.close();
    }

    @Test
    @Tag("browser")
    void adeptEnemySpawnsAndRendersInVisualizer() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.ADEPT, new Point2d(20, 20));
        engine.observe();

        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate("() => window.__test.enemyCount()")).intValue();
        assertThat(count).as("one Adept enemy must render").isEqualTo(1);
        page.close();
    }

    @Test
    @Tag("browser")
    void darkTemplarDrawFunctionProducesNonTransparentOutputForAllDirsAndTeams() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        for (String color : new String[]{TEAM_COLOR_FRIENDLY, TEAM_COLOR_ENEMY}) {
          for (int dir = 0; dir < 4; dir++) {
            Number alpha = (Number) page.evaluate(
                "() => window.__test.smokeTestDrawFn('drawDarkTemplar', " + dir + ", '" + color + "')");
            assertThat(alpha.intValue()).as("drawDarkTemplar dir=" + dir + " team=" + color).isGreaterThan(0);
          }
        }
        page.close();
    }

    @Test
    @Tag("browser")
    void darkTemplarEnemySpawnsAndRendersInVisualizer() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.DARK_TEMPLAR, new Point2d(20, 20));
        engine.observe();

        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate("() => window.__test.enemyCount()")).intValue();
        assertThat(count).as("one Dark Templar enemy must render").isEqualTo(1);
        page.close();
    }

    @Test
    @Tag("browser")
    void highTemplarDrawFunctionProducesNonTransparentOutputForAllDirsAndTeams() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        for (String color : new String[]{TEAM_COLOR_FRIENDLY, TEAM_COLOR_ENEMY}) {
          for (int dir = 0; dir < 4; dir++) {
            Number alpha = (Number) page.evaluate(
                "() => window.__test.smokeTestDrawFn('drawHighTemplar', " + dir + ", '" + color + "')");
            assertThat(alpha.intValue()).as("drawHighTemplar dir=" + dir + " team=" + color).isGreaterThan(0);
          }
        }
        page.close();
    }

    @Test
    @Tag("browser")
    void highTemplarEnemySpawnsAndRendersInVisualizer() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.HIGH_TEMPLAR, new Point2d(20, 20));
        engine.observe();

        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate("() => window.__test.enemyCount()")).intValue();
        assertThat(count).as("one High Templar enemy must render").isEqualTo(1);
        page.close();
    }

    @Test
    @Tag("browser")
    void disruptorDrawFunctionProducesNonTransparentOutputForAllDirsAndTeams() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        for (String color : new String[]{TEAM_COLOR_FRIENDLY, TEAM_COLOR_ENEMY}) {
          for (int dir = 0; dir < 4; dir++) {
            Number alpha = (Number) page.evaluate(
                "() => window.__test.smokeTestDrawFn('drawDisruptor', " + dir + ", '" + color + "')");
            assertThat(alpha.intValue()).as("drawDisruptor dir=" + dir + " team=" + color).isGreaterThan(0);
          }
        }
        page.close();
    }

    @Test
    @Tag("browser")
    void disruptorEnemySpawnsAndRendersInVisualizer() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.DISRUPTOR, new Point2d(20, 20));
        engine.observe();

        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate("() => window.__test.enemyCount()")).intValue();
        assertThat(count).as("one Disruptor enemy must render").isEqualTo(1);
        page.close();
    }

    @Test
    @Tag("browser")
    void immortalDrawFunctionProducesNonTransparentOutputForAllDirsAndTeams() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        for (String color : new String[]{TEAM_COLOR_FRIENDLY, TEAM_COLOR_ENEMY}) {
          for (int dir = 0; dir < 4; dir++) {
            Number alpha = (Number) page.evaluate(
                "() => window.__test.smokeTestDrawFn('drawImmortal', " + dir + ", '" + color + "')");
            assertThat(alpha.intValue()).as("drawImmortal dir=" + dir + " team=" + color).isGreaterThan(0);
          }
        }
        page.close();
    }

    @Test
    @Tag("browser")
    void immortalEnemySpawnsAndRendersInVisualizer() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.IMMORTAL, new Point2d(20, 20));
        engine.observe();

        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate("() => window.__test.enemyCount()")).intValue();
        assertThat(count).as("one Immortal enemy must render").isEqualTo(1);
        page.close();
    }

    @Test
    @Tag("browser")
    void archonDrawFunctionProducesNonTransparentOutputForAllDirsAndTeams() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        for (String color : new String[]{TEAM_COLOR_FRIENDLY, TEAM_COLOR_ENEMY}) {
          for (int dir = 0; dir < 4; dir++) {
            Number alpha = (Number) page.evaluate(
                "() => window.__test.smokeTestDrawFn('drawArchon', " + dir + ", '" + color + "')");
            assertThat(alpha.intValue()).as("drawArchon dir=" + dir + " team=" + color).isGreaterThan(0);
          }
        }
        page.close();
    }

    @Test
    @Tag("browser")
    void archonEnemySpawnsAndRendersInVisualizer() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.ARCHON, new Point2d(20, 20));
        engine.observe();

        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate("() => window.__test.enemyCount()")).intValue();
        assertThat(count).as("one Archon enemy must render").isEqualTo(1);
        page.close();
    }

    @Test
    @Tag("browser")
    void colossusDrawFunctionProducesNonTransparentOutputForAllDirsAndTeams() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        for (String color : new String[]{TEAM_COLOR_FRIENDLY, TEAM_COLOR_ENEMY}) {
          for (int dir = 0; dir < 4; dir++) {
            Number alpha = (Number) page.evaluate(
                "() => window.__test.smokeTestDrawFn('drawColossus', " + dir + ", '" + color + "')");
            assertThat(alpha.intValue()).as("drawColossus dir=" + dir + " team=" + color).isGreaterThan(0);
          }
        }
        page.close();
    }

    @Test
    @Tag("browser")
    void colossusEnemySpawnsAndRendersInVisualizer() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.COLOSSUS, new Point2d(20, 20));
        engine.observe();

        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate("() => window.__test.enemyCount()")).intValue();
        assertThat(count).as("one Colossus enemy must render").isEqualTo(1);
        page.close();
    }

    @Test
    @Tag("browser")
    void observerDrawFunctionProducesNonTransparentOutputForAllDirsAndTeams() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        for (String color : new String[]{TEAM_COLOR_FRIENDLY, TEAM_COLOR_ENEMY}) {
          for (int dir = 0; dir < 4; dir++) {
            Number alpha = (Number) page.evaluate(
                "() => window.__test.smokeTestDrawFn('drawObserver', " + dir + ", '" + color + "')");
            assertThat(alpha.intValue()).as("drawObserver dir=" + dir + " team=" + color).isGreaterThan(0);
          }
        }
        page.close();
    }

    @Test
    @Tag("browser")
    void observerEnemySpawnsAndRendersInVisualizer() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.OBSERVER, new Point2d(20, 20));
        engine.observe();

        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate("() => window.__test.enemyCount()")).intValue();
        assertThat(count).as("one Observer enemy must render").isEqualTo(1);
        page.close();
    }

    @Test
    @Tag("browser")
    void observerSpawnsHigherThanGroundUnit() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.MARINE, new Point2d(20, 20));
        engine.observe();
        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        @SuppressWarnings("unchecked")
        double marineY = ((List<?>) page.evaluate("() => window.__test.allEnemyWorldY()"))
            .stream().map(v -> ((Number) v).doubleValue()).toList().get(0);
        page.close();

        orchestrator.startGame();
        Page page2 = browser.newPage();
        page2.navigate(pageUrl.toString());
        page2.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.OBSERVER, new Point2d(20, 20));
        engine.observe();
        page2.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        @SuppressWarnings("unchecked")
        double observerY = ((List<?>) page2.evaluate("() => window.__test.allEnemyWorldY()"))
            .stream().map(v -> ((Number) v).doubleValue()).toList().get(0);
        page2.close();

        assertThat(observerY)
            .as("Observer Y (%.3f) must be higher than Marine Y (%.3f)".formatted(observerY, marineY))
            .isGreaterThan(marineY + 0.3);
    }

    @Test
    @Tag("browser")
    void voidRayDrawFunctionProducesNonTransparentOutputForAllDirsAndTeams() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.threeReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        for (String color : new String[]{TEAM_COLOR_FRIENDLY, TEAM_COLOR_ENEMY}) {
          for (int dir = 0; dir < 4; dir++) {
            Number alpha = (Number) page.evaluate(
                "() => window.__test.smokeTestDrawFn('drawVoidRay', " + dir + ", '" + color + "')");
            assertThat(alpha.intValue()).as("drawVoidRay dir=" + dir + " team=" + color).isGreaterThan(0);
          }
        }
        page.close();
    }

    @Test
    @Tag("browser")
    void voidRayEnemySpawnsAndRendersInVisualizer() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.VOID_RAY, new Point2d(20, 20));
        engine.observe();

        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        int count = ((Number) page.evaluate("() => window.__test.enemyCount()")).intValue();
        assertThat(count).as("one Void Ray enemy must render").isEqualTo(1);
        page.close();
    }

    @Test
    @Tag("browser")
    void voidRaySpawnsHigherThanGroundUnit() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.MARINE, new Point2d(20, 20));
        engine.observe();
        page.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        @SuppressWarnings("unchecked")
        double marineY = ((List<?>) page.evaluate("() => window.__test.allEnemyWorldY()"))
            .stream().map(v -> ((Number) v).doubleValue()).toList().get(0);
        page.close();

        orchestrator.startGame();
        Page page2 = browser.newPage();
        page2.navigate(pageUrl.toString());
        page2.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8_000));

        simulatedGame.spawnEnemyUnit(UnitType.VOID_RAY, new Point2d(20, 20));
        engine.observe();
        page2.waitForFunction("() => window.__test.enemyCount() >= 1",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));

        @SuppressWarnings("unchecked")
        double voidRayY = ((List<?>) page2.evaluate("() => window.__test.allEnemyWorldY()"))
            .stream().map(v -> ((Number) v).doubleValue()).toList().get(0);
        page2.close();

        assertThat(voidRayY)
            .as("Void Ray Y (%.3f) must be higher than Marine Y (%.3f)".formatted(voidRayY, marineY))
            .isGreaterThan(marineY + 0.3);
    }
}
