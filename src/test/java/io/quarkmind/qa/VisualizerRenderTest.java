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
import org.junit.jupiter.api.Test;
import io.quarkmind.agent.AgentOrchestrator;
import io.quarkmind.sc2.SC2Engine;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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
     * Regression test for the PixiJS 8 mask bug (fixed in commit 6484a03):
     * adding a Graphics mask as a child of an anchored Sprite made the Sprite
     * invisible. All unit sprites must have hasMask === false.
     */
    @Test
    void unitSpritesHaveNoMask() {
        Page page = openPage();
        observeAndWait(page, "unit", 12);

        boolean noMasks = (boolean) page.evaluate(
            "() => Array.from({length: 12}, (_, i) => window.__test.sprite('unit:probe-' + i))" +
            "      .every(s => s !== null && !s.hasMask)");

        assertThat(noMasks)
            .as("No unit sprite should have a mask applied (PixiJS 8 mask bug regression)")
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
}
