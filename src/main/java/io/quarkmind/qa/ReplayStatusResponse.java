package io.quarkmind.qa;

public record ReplayStatusResponse(long loop, long totalLoops, boolean paused, int speed) {}
