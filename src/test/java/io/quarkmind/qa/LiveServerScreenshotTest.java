package io.quarkmind.qa;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Connects to the LIVE dev server at http://localhost:8080 (not the QuarkusTest server),
 * opens the visualizer, POSTs /sc2/showcase, then dumps all scene outliers
 * (objects with world coordinates outside ±25 in x or z) and building mesh positions
 * to diagnose the source of far-off purple rectangles.
 *
 * Run with:
 *   mvn test -Pplaywright -Dtest=LiveServerScreenshotTest -q
 *
 * The @QuarkusTest annotation starts its own server (different port) — we ignore that
 * server entirely. All page.navigate() calls are hardcoded to http://localhost:8080.
 *
 * Prerequisites:
 *   - Live dev server running: mvn quarkus:dev
 *   - Chromium installed: mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI \
 *       -D exec.args="install chromium"
 */
@QuarkusTest
@Tag("browser")
class LiveServerScreenshotTest {

    private static final String LIVE_URL     = "http://localhost:8080/visualizer.html";
    private static final String SHOWCASE_URL = "http://localhost:8080/sc2/showcase";

    static Playwright playwright;
    static Browser    browser;

    @BeforeAll
    static void launchBrowser() {
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(true));
        } catch (Exception e) {
            if (playwright != null) playwright.close();
            assumeTrue(false, "Chromium not installed — skipping live screenshot test: " + e.getMessage());
        }
    }

    @AfterAll
    static void closeBrowser() {
        if (browser    != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @Test
    void findOutlierObjects() throws Exception {
        Page page = browser.newPage();

        // 1. Open visualizer and wait for wsConnected
        System.out.println("[live] Navigating to " + LIVE_URL);
        page.navigate(LIVE_URL);

        page.waitForFunction(
            "() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(10_000));
        System.out.println("[live] WebSocket connected");

        // 2. POST /sc2/showcase
        Object showcaseResult = page.evaluate("""
            () => fetch('http://localhost:8080/sc2/showcase', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' }
            }).then(r => r.status)
        """);
        System.out.println("[live] POST /sc2/showcase -> HTTP " + showcaseResult);

        // 3. Wait 2 seconds for showcase state to propagate
        Thread.sleep(2_000);

        // 4. Find ALL scene objects with positions outside ±25 world units in x or z,
        //    or y > 3 (buildings should be near ground).
        Object outliersJson = page.evaluate("""
            () => {
              const outliers = [];
              window._three.scene.traverse(obj => {
                if (!obj.isMesh && !obj.isSprite) return;
                const p = obj.getWorldPosition(new THREE.Vector3());
                if (Math.abs(p.x) > 25 || Math.abs(p.z) > 25 || Math.abs(p.y) > 3) {
                  outliers.push({
                    type: obj.type,
                    x: p.x.toFixed(1), y: p.y.toFixed(1), z: p.z.toFixed(1),
                    color: obj.material?.color?.getHexString() ?? 'none',
                    visible: obj.visible,
                    parent: obj.parent?.type ?? 'scene'
                  });
                }
              });
              return JSON.stringify(outliers);
            }
        """);
        System.out.println("=== OUTLIER OBJECTS (|x|>25 or |z|>25 or |y|>3) ===");
        System.out.println(outliersJson);

        // 5. Print BUILDING_SCALE to see building dimensions
        Object buildingScale = page.evaluate("() => JSON.stringify(BUILDING_SCALE)");
        System.out.println("=== BUILDING_SCALE ===");
        System.out.println(buildingScale);

        // 6. Print ALL building mesh positions (not just outliers)
        Object buildingMeshPositions = page.evaluate("""
            () => JSON.stringify(
              Array.from(buildingMeshes.entries()).map(([tag, m]) => ({
                tag,
                x: m.position.x.toFixed(1),
                y: m.position.y.toFixed(1),
                z: m.position.z.toFixed(1),
                color: m.material?.color?.getHexString()
              }))
            )
        """);
        System.out.println("=== ALL BUILDING MESH POSITIONS ===");
        System.out.println(buildingMeshPositions);

        // 7. Take a screenshot for visual confirmation
        Path screenshot = Path.of("/tmp/outlier-investigation.png");
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshot).setFullPage(false));
        System.out.println("[live] Screenshot saved: " + screenshot);

        page.close();
    }
}
