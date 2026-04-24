package io.quarkmind.sc2.map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MapDownloaderTest {

    @TempDir Path cacheDir;

    HttpClient mockHttp;
    MapDownloader downloader;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        mockHttp = mock(HttpClient.class);
        downloader = new MapDownloader(cacheDir, mockHttp);
    }

    @Test
    void returnsFromCacheIfAlreadyDownloaded() throws Exception {
        Path cached = cacheDir.resolve("TorchesAIE_v4.SC2Map");
        Files.writeString(cached, "fake-map-data");

        Optional<Path> result = downloader.download("TorchesAIE_v4.SC2Map");

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(cached);
        verifyNoInteractions(mockHttp);
    }

    @Test
    @SuppressWarnings("unchecked")
    void downloadsPackAndExtractsMap() throws Exception {
        byte[] zipBytes = makeZip("TorchesAIE_v4.SC2Map", new byte[]{1, 2, 3});
        HttpResponse<byte[]> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn(zipBytes);
        when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);

        Optional<Path> result = downloader.download("TorchesAIE_v4.SC2Map");

        assertThat(result).isPresent();
        assertThat(Files.readAllBytes(result.get())).isEqualTo(new byte[]{1, 2, 3});
    }

    @Test
    @SuppressWarnings("unchecked")
    void fuzzyMatchStripsVersionSuffix() throws Exception {
        byte[] zipBytes = makeZip("MagannathaAIE.SC2Map", new byte[]{4, 5, 6});
        HttpResponse<byte[]> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn(zipBytes);
        when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);

        Optional<Path> result = downloader.download("MagannathaAIE_v2.SC2Map");

        assertThat(result).isPresent();
        assertThat(result.get().getFileName().toString()).isEqualTo("MagannathaAIE_v2.SC2Map");
    }

    @Test
    void returnsEmptyForUnknownMap() throws Exception {
        Optional<Path> result = downloader.download("UnknownMap_v99.SC2Map");
        assertThat(result).isEmpty();
        verifyNoInteractions(mockHttp);
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsEmptyOnNetworkError() throws Exception {
        when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new java.io.IOException("timeout"));

        Optional<Path> result = downloader.download("TorchesAIE_v4.SC2Map");

        assertThat(result).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void secondCallReturnsCachedWithoutDownload() throws Exception {
        byte[] zipBytes = makeZip("TorchesAIE_v4.SC2Map", new byte[]{1, 2, 3});
        HttpResponse<byte[]> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn(zipBytes);
        when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);

        downloader.download("TorchesAIE_v4.SC2Map");
        downloader.download("TorchesAIE_v4.SC2Map");

        verify(mockHttp, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    private static byte[] makeZip(String entryName, byte[] content) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(content);
            zos.closeEntry();
        }
        return baos.toByteArray();
    }
}
