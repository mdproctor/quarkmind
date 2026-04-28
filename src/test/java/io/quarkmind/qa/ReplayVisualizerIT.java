package io.quarkmind.qa;

import com.microsoft.playwright.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests that start the REAL replay jar (not mock) and open a
 * real headless browser pointed at it — exactly what the user sees in Electron.
 *
 * These are the only tests that prove elements are actually visible.
 * All other visualizer tests run against the mock profile and do NOT test
 * what the replay viewer renders.
 *
 * Run with: mvn test -Pplaywright -Dtest=ReplayVisualizerIT
 *
 * Requires: mvn package -DskipTests -Dquarkus.profile=replay to have run first.
 * The replay jar is expected at target/quarkus-app/quarkus-run.jar.
 */
@Tag("browser")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReplayVisualizerIT {

    private static final int    PORT     = 8082;  // avoid clash with Electron on 8080
    private static final String BASE_URL = "http://localhost:" + PORT;

    private static Process    serverProcess;
    private static Playwright playwright;
    private static Browser    browser;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @BeforeAll
    static void startReplayServer() throws Exception {
        Path jar = Path.of("target/quarkus-app/quarkus-run.jar");
        assumeTrue(jar.toFile().exists(),
            "Replay jar not found — run: mvn package -DskipTests -Dquarkus.profile=replay");

        serverProcess = new ProcessBuilder(
            "java",
            "-Dquarkus.http.port=" + PORT,
            "-jar", jar.toString()
        )
            .directory(Path.of(".").toFile())
            .redirectErrorStream(true)
            .start();

        waitForServer(30);

        playwright = Playwright.create();
        try {
            browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(true));
        } catch (PlaywrightException e) {
            playwright.close();
            playwright = null;
            serverProcess.destroyForcibly();
            assumeTrue(false, "Chromium not installed — skipping replay visual tests");
        }
    }

    @AfterAll
    static void stopServer() {
        if (browser    != null) browser.close();
        if (playwright != null) playwright.close();
        if (serverProcess != null) serverProcess.destroyForcibly();
    }

    // -----------------------------------------------------------------------
    // Tests — each opens a fresh page, aims camera, samples pixel
    // -----------------------------------------------------------------------

    /**
     * Mineral patches must render as cyan pixels in the real replay viewer.
     *
     * The test aims the camera directly at the first mineral in the scene
     * (focusOnFirstMineral), then samples the actual WebGL pixel at that
     * position and asserts B > R (cyan, not sandy terrain).
     *
     * Failure means: minerals exist in game state but are NOT visually
     * rendered — regardless of camera angle, zoom, or rendering mode.
     */
    @Test
    @Order(1)
    void mineralRendersAsCyanPixelInRealReplay() throws Exception {
        try (var ctx = browser.newContext(
                 new Browser.NewContextOptions().setViewportSize(1280, 720));
             var page = ctx.newPage()) {

            page.navigate(BASE_URL + "/visualizer.html");
            page.waitForFunction("() => window.__test?.wsConnected?.() === true",
                null, new Page.WaitForFunctionOptions().setTimeout(15_000));

            page.waitForFunction("() => window.__test.mineralCount() > 0",
                null, new Page.WaitForFunctionOptions().setTimeout(10_000));

            int count = ((Number) page.evaluate("() => window.__test.mineralCount()")).intValue();
            assertThat(count).as("replay must have minerals in game state").isGreaterThan(0);

            // Aim camera at the first mineral, get its screen position
            page.waitForTimeout(200); // one render frame
            @SuppressWarnings("unchecked")
            Map<?, ?> pos = (Map<?, ?>) page.evaluate(
                "() => window.__test.focusOnFirstMineral()");
            page.waitForTimeout(200); // let camera settle and re-render

            assertThat(pos).as(
                "focusOnFirstMineral() returned null — no minerals in scene despite count=" + count
            ).isNotNull();

            int sx = ((Number) pos.get("x")).intValue();
            int sy = ((Number) pos.get("y")).intValue();

            // Sample the actual rendered pixel
            @SuppressWarnings("unchecked")
            Map<?, ?> pixel = (Map<?, ?>) page.evaluate(
                "() => window.__test.samplePixel(" + sx + ", " + sy + ")");
            int r = ((Number) pixel.get("r")).intValue();
            int g = ((Number) pixel.get("g")).intValue();
            int b = ((Number) pixel.get("b")).intValue();

            assertThat(b).as(
                "Mineral pixel at screen (%d,%d) must be CYAN (B > R). " +
                "Got R=%d G=%d B=%d. " +
                "If B <= R the pixel is terrain sandy — minerals are NOT visible to the user."
                .formatted(sx, sy, r, g, b)
            ).isGreaterThan(r);
        }
    }

    /**
     * Geysers must render as green pixels in the real replay viewer.
     */
    @Test
    @Order(2)
    void geyserRendersAsGreenPixelInRealReplay() throws Exception {
        try (var ctx = browser.newContext(
                 new Browser.NewContextOptions().setViewportSize(1280, 720));
             var page = ctx.newPage()) {

            page.navigate(BASE_URL + "/visualizer.html");
            page.waitForFunction("() => window.__test?.wsConnected?.() === true",
                null, new Page.WaitForFunctionOptions().setTimeout(15_000));

            page.waitForFunction("() => window.__test.geyserCount() > 0",
                null, new Page.WaitForFunctionOptions().setTimeout(10_000));

            page.waitForTimeout(200);
            @SuppressWarnings("unchecked")
            Map<?, ?> pos = (Map<?, ?>) page.evaluate(
                "() => window.__test.focusOnFirstGeyser()");
            page.waitForTimeout(200);

            assertThat(pos).as("focusOnFirstGeyser() returned null").isNotNull();

            int sx = ((Number) pos.get("x")).intValue();
            int sy = ((Number) pos.get("y")).intValue();

            @SuppressWarnings("unchecked")
            Map<?, ?> pixel = (Map<?, ?>) page.evaluate(
                "() => window.__test.samplePixel(" + sx + ", " + sy + ")");
            int r = ((Number) pixel.get("r")).intValue();
            int g = ((Number) pixel.get("g")).intValue();
            int b = ((Number) pixel.get("b")).intValue();

            assertThat(g).as(
                "Geyser pixel at screen (%d,%d) must be GREEN (G > R). " +
                "Got R=%d G=%d B=%d. Geyser is NOT visible to the user."
                .formatted(sx, sy, r, g, b)
            ).isGreaterThan(r);
        }
    }

    /**
     * Creep must render as purple pixels around a Zerg Hatchery in the real replay.
     */
    @Test
    @Order(3)
    void creepRendersPurpleAroundHatcheryInRealReplay() throws Exception {
        try (var ctx = browser.newContext(
                 new Browser.NewContextOptions().setViewportSize(1280, 720));
             var page = ctx.newPage()) {

            page.navigate(BASE_URL + "/visualizer.html");
            page.waitForFunction("() => window.__test?.wsConnected?.() === true",
                null, new Page.WaitForFunctionOptions().setTimeout(15_000));

            page.waitForFunction("() => window.__test.creepTileCount() > 0",
                null, new Page.WaitForFunctionOptions().setTimeout(10_000));

            int tiles = ((Number) page.evaluate("() => window.__test.creepTileCount()")).intValue();
            assertThat(tiles).as("replay must have creep tiles").isGreaterThan(0);

            // Aim camera at the first creep tile and sample its pixel
            page.waitForTimeout(200);
            @SuppressWarnings("unchecked")
            Map<?, ?> pos = (Map<?, ?>) page.evaluate(
                "() => window.__test.focusOnFirstCreep()");
            page.waitForTimeout(200);

            assertThat(pos).as("No creep tile found in scene despite count=" + tiles).isNotNull();

            int sx = ((Number) pos.get("x")).intValue();
            int sy = ((Number) pos.get("y")).intValue();

            @SuppressWarnings("unchecked")
            Map<?, ?> pixel = (Map<?, ?>) page.evaluate(
                "() => window.__test.samplePixel(" + sx + ", " + sy + ")");
            int r = ((Number) pixel.get("r")).intValue();
            int g = ((Number) pixel.get("g")).intValue();
            int b = ((Number) pixel.get("b")).intValue();

            assertThat(b).as(
                ("Creep pixel at screen (%d,%d) must be PURPLE (B > G). " +
                "Got R=%d G=%d B=%d. Creep is NOT visible to the user.")
                .formatted(sx, sy, r, g, b)
            ).isGreaterThan(g);
        }
    }

    /**
     * Clicking the first enemy building in the replay must open the inspect panel
     * and show the building type name from /qa/building/{tag}.
     */
    @Test
    @Order(4)
    void enemyBuildingInspectPanelOpensInRealReplay() throws Exception {
        try (var ctx = browser.newContext(
                 new Browser.NewContextOptions().setViewportSize(1280, 720));
             var page = ctx.newPage()) {

            page.navigate(BASE_URL + "/visualizer.html");
            page.waitForFunction("() => window.__test?.wsConnected?.() === true",
                null, new Page.WaitForFunctionOptions().setTimeout(15_000));

            page.waitForFunction("() => window.__test.enemyBuildingCount() > 0",
                null, new Page.WaitForFunctionOptions().setTimeout(10_000));

            String firstTag = (String) page.evaluate(
                "() => window.__test.firstEnemyBuildingTag()");

            assertThat(firstTag).as("replay must have an enemy building sprite in scene").isNotNull();

            boolean hit = (boolean) page.evaluate(
                "async () => window.__test.clickBuilding('" + firstTag + "', true)");
            assertTrue(hit, "clickBuilding must hit the enemy building sprite");
            assertTrue((Boolean) page.evaluate("() => window.__test.panelVisible()"),
                "Panel must be visible after clicking enemy building");

            String name = (String) page.evaluate(
                "() => document.getElementById('up-name').textContent");
            assertThat(name).as("panel shows building type from /qa/building/{tag}")
                .isNotBlank();
        }
    }

    // -----------------------------------------------------------------------
    // Infrastructure
    // -----------------------------------------------------------------------

    private static void waitForServer(int timeoutSeconds) throws Exception {
        var http = HttpClient.newHttpClient();
        var req  = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/q/health/ready"))
            .GET().build();

        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                int status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status == 200) return;
            } catch (Exception ignored) {}
            Thread.sleep(500);
        }
        serverProcess.destroyForcibly();
        throw new AssertionError("Replay server did not become ready within " + timeoutSeconds + "s");
    }
}
