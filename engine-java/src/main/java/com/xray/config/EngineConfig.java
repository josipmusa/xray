package com.xray.config;

import java.nio.file.Path;
import java.util.Set;

public record EngineConfig(
        Path repoRoot,
        Path outputDir,          // default: repoRoot/.xray
        Options options
) {

    public record Options(
            Set<String> includeGlobs, // e.g. **/src/main/java/**
            Set<String> excludeGlobs, // e.g. **/target/**, **/.git/**
            boolean includeTests,     // default false
            boolean enableSummaries,  // default false
            int maxDepth)              // for flow/impact BFS limits
    {}
}
