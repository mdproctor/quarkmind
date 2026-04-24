package io.quarkmind.qa;

import java.util.List;

public record TerrainResponse(
    int width,
    int height,
    List<int[]> walls,
    List<int[]> highGround,
    List<int[]> ramps
) {}
