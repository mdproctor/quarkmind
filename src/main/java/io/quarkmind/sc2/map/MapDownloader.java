package io.quarkmind.sc2.map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@ApplicationScoped
public class MapDownloader {

    private static final Logger log = Logger.getLogger(MapDownloader.class);
    private static final Pattern VERSION_SUFFIX = Pattern.compile("_v\\d+$");

    private final Path cacheDir;
    private final HttpClient http;
    private String baseUrl;
    private List<Pack> packs;

    public MapDownloader() {
        this(Path.of(System.getProperty("user.home"), ".quarkmind", "maps"),
             HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build());
    }

    MapDownloader(Path cacheDir, HttpClient http) {
        this.cacheDir = cacheDir;
        this.http = http;
        init();
    }

    @PostConstruct
    void init() {
        try (InputStream in = getClass().getResourceAsStream("/map-registry.json")) {
            JsonNode root = new ObjectMapper().readTree(in);
            baseUrl = root.get("baseUrl").asText();
            packs = new ArrayList<>();
            for (JsonNode pack : root.get("packs")) {
                int id = pack.get("id").asInt();
                List<String> maps = new ArrayList<>();
                pack.get("maps").forEach(m -> maps.add(m.asText()));
                packs.add(new Pack(id, maps));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load map-registry.json", e);
        }
    }

    public Optional<Path> download(String mapFileName) {
        try { Files.createDirectories(cacheDir); } catch (Exception ignored) {}
        Path cached = cacheDir.resolve(mapFileName);
        if (Files.exists(cached)) return Optional.of(cached);

        String bare = bare(mapFileName);
        for (Pack pack : packs) {
            String match = exactOrFuzzy(pack, bare, mapFileName);
            if (match == null) continue;
            Optional<Path> result = downloadFromPack(pack.id, match, mapFileName);
            if (result.isPresent()) return result;
        }
        log.warnf("[MAP] Map not found in any known pack: %s", mapFileName);
        return Optional.empty();
    }

    private Optional<Path> downloadFromPack(int packId, String entryInZip, String saveAs) {
        String url = baseUrl + packId + "/";
        try {
            log.infof("[MAP] Downloading pack %d for map %s", packId, saveAs);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                log.warnf("[MAP] Pack download failed: HTTP %d", resp.statusCode());
                return Optional.empty();
            }
            byte[] extracted = extractFromZip(resp.body(), entryInZip);
            if (extracted == null) {
                log.warnf("[MAP] Entry %s not found in pack %d zip", entryInZip, packId);
                return Optional.empty();
            }
            Path dest = cacheDir.resolve(saveAs);
            Files.write(dest, extracted);
            log.infof("[MAP] Saved %s (%d bytes)", saveAs, extracted.length);
            return Optional.of(dest);
        } catch (Exception e) {
            log.warnf("[MAP] Download error for pack %d: %s", packId, e.getMessage());
            return Optional.empty();
        }
    }

    private static byte[] extractFromZip(byte[] zipBytes, String entryName) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(entryName)) return zis.readAllBytes();
            }
        }
        return null;
    }

    private static String bare(String fileName) {
        return fileName.endsWith(".SC2Map")
            ? VERSION_SUFFIX.matcher(fileName.substring(0, fileName.length() - 7)).replaceAll("")
            : fileName;
    }

    private static String exactOrFuzzy(Pack pack, String bare, String originalFileName) {
        // Exact match: the filename stem (no version stripping) is listed verbatim in the pack
        String stem = originalFileName.endsWith(".SC2Map")
            ? originalFileName.substring(0, originalFileName.length() - 7)
            : originalFileName;
        if (pack.maps.contains(stem)) return originalFileName;
        // Fuzzy match: strip version suffix and look for the base name in the pack
        if (pack.maps.contains(bare)) return bare + ".SC2Map";
        return null;
    }

    private record Pack(int id, List<String> maps) {}
}
