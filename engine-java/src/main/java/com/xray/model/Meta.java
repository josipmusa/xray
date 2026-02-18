package com.xray.model;

import com.xray.config.EngineConfig;

import java.time.Instant;

public record Meta(
        String engineVersion,
        int schemaVersion,
        Instant analyzedAt,
        String repoRoot,
        EngineConfig.Options options,
        Stats stats
) {

    public record Stats (long javaFilesFound,
                         long filesParsedOk,
                         long filesParsedFailed,
                         long nodesWritten) {}
}
