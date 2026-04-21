package io.quarkmind.qa;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.*;

import jakarta.inject.Inject;
import io.quarkmind.agent.AgentOrchestrator;
import io.quarkmind.sc2.SC2Engine;

import java.net.URL;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@QuarkusTest
@Tag("browser")
class VisualizerFogRenderTest {

    @TestHTTPResource("/visualizer.html") URL pageUrl;
    @Inject AgentOrchestrator orchestrator;
    @Inject SC2Engine engine;

    private static Playwright playwright;
    private static Browser browser;

    @BeforeAll
    static void launchBrowser() {
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch();
        } catch (Exception e) {
            assumeTrue(false, "Chromium not available: " + e.getMessage());
        }
    }

    @AfterAll
    static void closeBrowser() {
        browser.close();
        playwright.close();
    }

    @Test
    void unseenTilesHaveSolidFogOverlay() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.terrainReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(10000));
        orchestrator.gameTick();
        page.waitForTimeout(400);
        // Tile (0,0) is at the map corner — always UNSEEN in mock/emulated mode
        Number opacity = (Number) page.evaluate("() => window.__test.fogOpacity(0, 0)");
        assertEquals(1.0, opacity.doubleValue(), 0.01,
            "Corner tile (0,0) should be fully fogged (UNSEEN), got opacity=" + opacity);
        page.close();
    }

    @Test
    void broadcastEnvelopeUnwrappedCorrectly_hudShowsMinerals() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.wsConnected?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(8000));
        // engine.observe() proves end-to-end WS connectivity before asserting HUD
        engine.observe();
        page.waitForFunction("() => window.__test.hudText() !== 'Connecting...'",
            null, new Page.WaitForFunctionOptions().setTimeout(5000));
        String hud = (String) page.evaluate("() => window.__test.hudText()");
        assertFalse(hud.contains("undefined"),
            "HUD should not contain 'undefined' — state must be unwrapped from envelope: " + hud);
        assertTrue(hud.contains("Minerals:"),
            "HUD should show minerals from unwrapped state, not raw envelope: " + hud);
        page.close();
    }

    @Test
    void fogPlanesPopulatedAfterTerrainLoad() throws Exception {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test?.terrainReady?.() === true",
            null, new Page.WaitForFunctionOptions().setTimeout(10000));
        // fogOpacity(0,0) returns -1 if fogPlanes Map is empty (plane not found)
        Number opacity = (Number) page.evaluate("() => window.__test.fogOpacity(0, 0)");
        assertNotEquals(-1.0, opacity.doubleValue(),
            "fogPlanes Map should be populated after terrainReady — got -1 (not found)");
        page.close();
    }
}
