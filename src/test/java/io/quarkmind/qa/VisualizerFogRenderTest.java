package io.quarkmind.qa;

import com.microsoft.playwright.*;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import io.quarkmind.agent.AgentOrchestrator;
import io.quarkmind.sc2.SC2Engine;
import org.junit.jupiter.api.*;

import java.net.URL;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Playwright browser tests for the E9 fog of war overlay.
 *
 * Verifies: fog layer exists in the PixiJS scene; the GameStateBroadcast
 * envelope is correctly unwrapped (updateScene receives msg.state, so the
 * HUD text shows real mineral values rather than undefined).
 *
 * Run with: mvn test -Pplaywright
 * Requires Chromium: mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI
 *                                  -D exec.args="install chromium"
 */
@Tag("browser")
@QuarkusTest
class VisualizerFogRenderTest {

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
                "Chromium not installed — skipping fog render tests.\n" +
                "Install: mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI" +
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

    /** Open page and wait for WebSocket connection + first frame, mirroring VisualizerRenderTest. */
    private Page openPage() {
        Page page = browser.newPage();
        page.navigate(pageUrl.toString());
        page.waitForFunction("() => window.__test && window.__test.wsConnected()",
            null, new Page.WaitForFunctionOptions().setTimeout(10_000));
        engine.observe();
        page.waitForFunction("() => window.__test.hudText() !== 'Connecting...'",
            null, new Page.WaitForFunctionOptions().setTimeout(5_000));
        return page;
    }

    @Test
    void fogLayerExistsInScene() {
        Page page = openPage();
        // window._layers.fog must be initialised by visualizer.js
        Object fogExists = page.evaluate("() => window._layers != null && window._layers.fog != null");
        assertThat(fogExists).isEqualTo(Boolean.TRUE);
        page.close();
    }

    @Test
    void broadcastEnvelopeUnwrappedCorrectly_hudShowsMinerals() {
        // If the visualizer incorrectly reads msg.minerals (raw envelope) instead of
        // msg.state.minerals, the HUD text would show 'undefined' or 'NaN'.
        Page page = openPage();
        String hud = (String) page.evaluate("() => window.__test.hudText()");
        assertThat(hud)
            .as("HUD must contain 'Minerals:' — confirms updateScene(msg.state) is working")
            .contains("Minerals:");
        // Minerals value must be a number, not NaN/undefined
        assertThat(hud).doesNotContain("NaN").doesNotContain("undefined");
        page.close();
    }
}
