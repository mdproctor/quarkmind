package io.quarkmind.qa;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Showcase verification test: seeds the visualizer via /sc2/showcase and
 * reports actual rendered counts, fog state, FPS, and HUD text.
 *
 * Checks:
 *   - enemyCount >= 7 (7 enemy units seeded)
 *   - fogVisible(11,9) == false  (Marine is within probe sight range — no fog)
 *   - fogVisible(40,40) == false (fog planes hidden in mock mode)
 */
@QuarkusTest
@Tag("browser")
class ShowcaseVerifyTest {

    @TestHTTPResource("/visualizer.html")
    URL pageUrl;

    @TestHTTPResource("/sc2/showcase")
    URL showcaseUrl;

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
            assumeTrue(false, "Chromium not installed — skipping showcase verify test: " + e.getMessage());
        }
    }

    @AfterAll
    static void closeBrowser() {
        if (browser    != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @Test
    void showcaseStateIsVisualizedCorrectly() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());

        // 1. Wait for WebSocket connection
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(10_000));

        System.out.println("[showcase] WebSocket connected");

        // 2. Trigger showcase seed via HTTP POST
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
            .uri(showcaseUrl.toURI())
            .POST(HttpRequest.BodyPublishers.noBody())
            .header("Content-Type", "application/json")
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        System.out.println("[showcase] POST /sc2/showcase -> " + resp.statusCode() + " " + resp.body());

        // 3. Wait 2 seconds for WebSocket messages to arrive
        page.waitForTimeout(2000);

        // 4a. unitCount — friendly sprites
        int unitCount = ((Number) page.evaluate("() => window.__test.unitCount()")).intValue();

        // 4b. enemyCount — enemy sprites
        int enemyCount = ((Number) page.evaluate("() => window.__test.enemyCount()")).intValue();

        // 4c. stagingCount
        int stagingCount = ((Number) page.evaluate("() => window.__test.stagingCount()")).intValue();

        // 4d. fogVisible(11,9) — Marine is within probe sight range, should be NOT visible (fog hidden)
        boolean fogNear = (Boolean) page.evaluate("() => window.__test.fogVisible(11, 9)");

        // 4e. fogVisible(40,40) — far tile, outside sight range
        boolean fogFar = (Boolean) page.evaluate("() => window.__test.fogVisible(40, 40)");

        // 4f. FPS measurement via requestAnimationFrame counting over 2 seconds
        page.evaluate("""
            () => {
              window.__fpsCount = 0;
              window.__fpsStart = performance.now();
              function tick() {
                window.__fpsCount++;
                if (performance.now() - window.__fpsStart < 2000) {
                  requestAnimationFrame(tick);
                }
              }
              requestAnimationFrame(tick);
            }
        """);
        page.waitForTimeout(2200);
        int rafFrames = ((Number) page.evaluate("() => window.__fpsCount")).intValue();
        double fps = rafFrames / 2.0;

        // 4g. Count visible fog planes
        int visibleFogPlanes = ((Number) page.evaluate(
            "() => Array.from({length:64},(_,z)=>Array.from({length:64},(_,x)=>window.__test.fogVisible(x,z)))" +
            "      .flat().filter(Boolean).length"
        )).intValue();

        // 4h. HUD text
        String hud = (String) page.evaluate("() => window.__test.hudText()");

        // Print all results clearly
        System.out.println("=== SHOWCASE VERIFY RESULTS ===");
        System.out.println("  unitCount()         = " + unitCount    + "  (expect 12 probes)");
        System.out.println("  enemyCount()        = " + enemyCount   + "  (expect 7)");
        System.out.println("  stagingCount()      = " + stagingCount);
        System.out.println("  fogVisible(11,9)    = " + fogNear      + "  (expect false — Marine in sight range)");
        System.out.println("  fogVisible(40,40)   = " + fogFar       + "  (expect false — fog planes hidden in mock mode)");
        System.out.println("  render FPS (RAF/2s) = " + fps          + " fps");
        System.out.println("  visible fog planes  = " + visibleFogPlanes + " / 4096");
        System.out.println("  HUD text            = " + hud);
        System.out.println("================================");

        // Assertions
        assertTrue(enemyCount >= 7,
            "Expected >= 7 enemy sprites after showcase seed, got: " + enemyCount);
        assertTrue(!fogNear,
            "fogVisible(11,9) should be false (Marine is within probe sight range, fog should be hidden), got: " + fogNear);
        assertTrue(!fogFar,
            "fogVisible(40,40) should be false (fog planes are hidden in mock mode), got: " + fogFar);

        page.close();
    }
}
