package io.quarkmind.qa;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.ScreenshotType;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import jakarta.inject.Inject;
import io.quarkmind.agent.AgentOrchestrator;
import io.quarkus.test.common.http.TestHTTPResource;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Thorough Playwright investigation of the QuarkMind visualizer showcase.
 * Screenshots are written to /tmp/v2-*.png for visual inspection.
 * All metrics are printed via System.out for investigation.
 */
@QuarkusTest
@Tag("browser")
class ShowcaseInvestigateTest {

    @TestHTTPResource("/visualizer.html")
    URL pageUrl;

    @TestHTTPResource("/sc2/showcase")
    URL showcaseUrl;

    @Inject AgentOrchestrator orchestrator;

    @Test
    void investigateShowcase() throws Exception {
        Playwright playwright = Playwright.create();
        Browser browser;
        try {
            browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(true));
        } catch (PlaywrightException e) {
            playwright.close();
            assumeTrue(false,
                "Chromium not installed — skipping showcase investigation.\n" +
                "Install with: mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI" +
                " -D exec.args=\"install chromium\"");
            return;
        }

        try {
            Page page = browser.newPage();
            page.setViewportSize(1280, 800);

            // ----------------------------------------------------------------
            // 1. Open page and wait for wsConnected (10s timeout)
            // ----------------------------------------------------------------
            page.navigate(pageUrl.toString());
            page.waitForFunction("() => window.__test?.wsConnected?.() === true",
                null, new Page.WaitForFunctionOptions().setTimeout(10_000));
            System.out.println("[investigate] WebSocket connected");

            // ----------------------------------------------------------------
            // 2. POST to /sc2/showcase to seed units
            // ----------------------------------------------------------------
            HttpClient http = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                .uri(showcaseUrl.toURI())
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/json")
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            System.out.println("[investigate] POST /sc2/showcase -> " + resp.statusCode() + " " + resp.body());

            // ----------------------------------------------------------------
            // 3. Wait 2 seconds for WebSocket messages to propagate
            // ----------------------------------------------------------------
            page.waitForTimeout(2000);

            // ----------------------------------------------------------------
            // 4. Print unit counts
            // ----------------------------------------------------------------
            int unitCount     = ((Number) page.evaluate("() => window.__test.unitCount()")).intValue();
            int enemyCount    = ((Number) page.evaluate("() => window.__test.enemyCount()")).intValue();
            int buildingCount = ((Number) page.evaluate("() => window.__test.buildingCount()")).intValue();
            int geyserCount   = ((Number) page.evaluate("() => window.__test.geyserCount()")).intValue();

            System.out.println("\n=== SPRITE COUNTS ===");
            System.out.println("  unitCount()     = " + unitCount);
            System.out.println("  enemyCount()    = " + enemyCount);
            System.out.println("  buildingCount() = " + buildingCount);
            System.out.println("  geyserCount()   = " + geyserCount);

            // ----------------------------------------------------------------
            // 5. Fog planes investigation
            // ----------------------------------------------------------------
            System.out.println("\n=== FOG INVESTIGATION ===");

            // First, see what's available on window.__test
            String testApiKeys = (String) page.evaluate("() => Object.keys(window.__test).join(',')");
            System.out.println("  window.__test keys: " + testApiKeys);

            // Try to get fog plane count from the scene directly
            Object fogPlanesInfo = page.evaluate("""
                () => {
                  try {
                    // Try fogPlanes map if it exists in global scope
                    if (typeof fogPlanes !== 'undefined') {
                      return 'fogPlanes map size: ' + fogPlanes.size;
                    }
                    // Try via scene children — count Mesh objects that match fog pattern
                    const scene = window._three?.scene;
                    if (!scene) return 'no _three.scene';
                    // Count meshes with fog-like materials (opacity < 1, transparent)
                    let fogCount = 0;
                    scene.traverse(obj => {
                      if (obj.isMesh && obj.material && obj.material.transparent &&
                          obj.material.opacity <= 1.0 && obj.userData && obj.userData.isFog) {
                        fogCount++;
                      }
                    });
                    return 'fog meshes by userData.isFog: ' + fogCount;
                  } catch(e) {
                    return 'error: ' + e.message;
                  }
                }
            """);
            System.out.println("  fog planes direct: " + fogPlanesInfo);

            // Try fogOpacity API to gauge visible fog cells
            int visibleFogCount = ((Number) page.evaluate("""
                () => {
                  let count = 0;
                  for (let z = 0; z < 64; z++) {
                    for (let x = 0; x < 64; x++) {
                      try {
                        const op = window.__test.fogOpacity(x, z);
                        if (op !== undefined && op !== null && op > 0) count++;
                      } catch(e) { /* skip */ }
                    }
                  }
                  return count;
                }
            """)).intValue();
            System.out.println("  visible fog cells (fogOpacity > 0): " + visibleFogCount + " / 4096");

            // Sample a few fog opacity values
            System.out.println("  fogOpacity(11,9)  = " + page.evaluate("() => window.__test.fogOpacity(11,9)"));
            System.out.println("  fogOpacity(40,40) = " + page.evaluate("() => window.__test.fogOpacity(40,40)"));

            // Try to get fogVisible count if available
            try {
                int visibleFogPlanes = ((Number) page.evaluate("""
                    () => Array.from({length:64},(_,z)=>Array.from({length:64},(_,x)=>window.__test.fogVisible(x,z)))
                               .flat().filter(Boolean).length
                """)).intValue();
                System.out.println("  fogVisible true cells: " + visibleFogPlanes + " / 4096");
            } catch (Exception e) {
                System.out.println("  fogVisible() not available: " + e.getMessage());
            }

            // ----------------------------------------------------------------
            // 6. FPS measurement in 2D isometric mode (current default)
            // ----------------------------------------------------------------
            System.out.println("\n=== FPS MEASUREMENT ===");

            // 2D mode FPS over 2 seconds
            page.evaluate("""
                () => {
                  window.__fpsCount2d = 0;
                  window.__fpsStart2d = performance.now();
                  function tick() {
                    window.__fpsCount2d++;
                    if (performance.now() - window.__fpsStart2d < 2000) {
                      requestAnimationFrame(tick);
                    }
                  }
                  requestAnimationFrame(tick);
                }
            """);
            page.waitForTimeout(2200);
            int raf2d = ((Number) page.evaluate("() => window.__fpsCount2d")).intValue();
            double fps2d = raf2d / 2.0;
            System.out.println("  2D isometric FPS (RAF/2s) = " + fps2d);

            // ----------------------------------------------------------------
            // 7. Screenshots
            // ----------------------------------------------------------------
            System.out.println("\n=== SCREENSHOTS ===");

            // --- Isometric 2D (current state) ---
            page.waitForTimeout(500);
            page.screenshot(new Page.ScreenshotOptions()
                .setPath(Path.of("/tmp/v2-iso.png"))
                .setType(ScreenshotType.PNG));
            System.out.println("  Saved /tmp/v2-iso.png  (isometric 2D)");

            // worldToScreen for enemy at tile (32,32) in isometric mode
            Object isoW2s = page.evaluate(
                "() => JSON.stringify(window.__test.worldToScreen(32*0.7-22.4, 32*0.7-22.4))");
            System.out.println("  worldToScreen(32*0.7-22.4, 32*0.7-22.4) in iso = " + isoW2s);

            // --- Top-down 2D ---
            page.click("button:has-text('Top-down')");
            page.waitForTimeout(2000); // smooth camera transition
            page.screenshot(new Page.ScreenshotOptions()
                .setPath(Path.of("/tmp/v2-top.png"))
                .setType(ScreenshotType.PNG));
            System.out.println("  Saved /tmp/v2-top.png  (top-down 2D)");

            Object topW2s = page.evaluate(
                "() => JSON.stringify(window.__test.worldToScreen(32*0.7-22.4, 32*0.7-22.4))");
            System.out.println("  worldToScreen(32*0.7-22.4, 32*0.7-22.4) in top-down = " + topW2s);

            // Reset to isometric for 3D switch
            page.click("button:has-text('Isometric')");
            page.waitForTimeout(500);

            // --- 3D mode ---
            // Try common button IDs/text for 3D mode
            try {
                page.click("#btn3d");
            } catch (Exception e) {
                try {
                    page.click("button:has-text('3D Models')");
                } catch (Exception e2) {
                    System.out.println("  WARNING: Could not find 3D mode button: " + e2.getMessage());
                }
            }
            page.waitForTimeout(500);
            page.screenshot(new Page.ScreenshotOptions()
                .setPath(Path.of("/tmp/v2-3d.png"))
                .setType(ScreenshotType.PNG));
            System.out.println("  Saved /tmp/v2-3d.png  (3D Models mode)");

            Object threeDW2s = page.evaluate(
                "() => JSON.stringify(window.__test.worldToScreen(32*0.7-22.4, 32*0.7-22.4))");
            System.out.println("  worldToScreen(32*0.7-22.4, 32*0.7-22.4) in 3D = " + threeDW2s);

            // Confirm 3D mode group state
            try {
                boolean btn3dActive = (Boolean) page.evaluate(
                    "() => document.getElementById('btn3d')?.classList.contains('active') ?? false");
                boolean group3dVis = (Boolean) page.evaluate("() => group3d?.visible ?? false");
                boolean group2dVis = (Boolean) page.evaluate("() => group2d?.visible ?? true");
                System.out.println("  3D mode: btn3d.active=" + btn3dActive +
                    " group3d.visible=" + group3dVis + " group2d.visible=" + group2dVis);
                int group3dChildren = ((Number) page.evaluate("() => group3d?.children?.length ?? 0")).intValue();
                int group2dChildren = ((Number) page.evaluate("() => group2d?.children?.length ?? 0")).intValue();
                System.out.println("  group3d.children=" + group3dChildren + " group2d.children=" + group2dChildren);
            } catch (Exception e) {
                System.out.println("  group3d/group2d not available: " + e.getMessage());
            }

            // 3D mode FPS over 2 seconds
            page.evaluate("""
                () => {
                  window.__fpsCount3d = 0;
                  window.__fpsStart3d = performance.now();
                  function tick() {
                    window.__fpsCount3d++;
                    if (performance.now() - window.__fpsStart3d < 2000) {
                      requestAnimationFrame(tick);
                    }
                  }
                  requestAnimationFrame(tick);
                }
            """);
            page.waitForTimeout(2200);
            int raf3d = ((Number) page.evaluate("() => window.__fpsCount3d")).intValue();
            double fps3d = raf3d / 2.0;
            System.out.println("  3D mode FPS (RAF/2s) = " + fps3d);

            // ----------------------------------------------------------------
            // Additional diagnostics
            // ----------------------------------------------------------------
            String hud = (String) page.evaluate("() => window.__test.hudText()");
            System.out.println("\n=== HUD TEXT ===");
            System.out.println("  \"" + hud + "\"");

            System.out.println("\n=== INVESTIGATION COMPLETE ===");

            // Minimal assertion so the test registers as passed
            assertTrue(enemyCount >= 1,
                "Expected at least 1 enemy sprite after showcase seed, got: " + enemyCount);

            page.close();
        } finally {
            browser.close();
            playwright.close();
        }
    }
}
