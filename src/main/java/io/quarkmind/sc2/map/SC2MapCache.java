package io.quarkmind.sc2.map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkmind.domain.TerrainGrid;
import io.quarkmind.qa.TerrainResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class SC2MapCache {

    private static final Logger log = Logger.getLogger(SC2MapCache.class);
    private static final Path CACHE_DIR =
        Path.of(System.getProperty("user.home"), ".quarkmind", "maps");

    @Inject MapDownloader downloader;
    @Inject ObjectMapper objectMapper;

    public Optional<TerrainResponse> get(String mapName) {
        String fileName = mapName.endsWith(".SC2Map") ? mapName : mapName + ".SC2Map";
        String baseName = fileName.substring(0, fileName.length() - 7);
        Path jsonCache  = CACHE_DIR.resolve(baseName + "-terrain.json");

        if (Files.exists(jsonCache)) {
            try {
                return Optional.of(objectMapper.readValue(jsonCache.toFile(), TerrainResponse.class));
            } catch (Exception e) {
                log.warnf("[MAP] Corrupt terrain cache for %s, re-extracting: %s", mapName, e.getMessage());
                try { Files.delete(jsonCache); } catch (Exception ignored) {}
            }
        }

        Optional<Path> sc2map = downloader.download(fileName);
        if (sc2map.isEmpty()) return Optional.empty();

        try {
            TerrainGrid grid = SC2MapTerrainExtractor.extract(sc2map.get());
            TerrainResponse response = toResponse(grid);
            Files.createDirectories(CACHE_DIR);
            objectMapper.writeValue(jsonCache.toFile(), response);
            return Optional.of(response);
        } catch (Exception e) {
            log.errorf("[MAP] Terrain extraction failed for %s: %s", mapName, e.getMessage());
            return Optional.empty();
        }
    }

    private static TerrainResponse toResponse(TerrainGrid grid) {
        List<int[]> walls = new ArrayList<>(), high = new ArrayList<>(), ramps = new ArrayList<>();
        for (int x = 0; x < grid.width(); x++) {
            for (int y = 0; y < grid.height(); y++) {
                switch (grid.heightAt(x, y)) {
                    case WALL -> walls.add(new int[]{x, y});
                    case HIGH -> high.add(new int[]{x, y});
                    case RAMP -> ramps.add(new int[]{x, y});
                    case LOW  -> {}
                }
            }
        }
        return new TerrainResponse(grid.width(), grid.height(), walls, high, ramps);
    }
}
