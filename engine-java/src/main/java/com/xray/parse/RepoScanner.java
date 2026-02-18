package com.xray.parse;

import com.xray.config.EngineConfig;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public final class RepoScanner {

    /**
     * Caller should close stream as to not leak file handles
     */
    public static Stream<Path> findJavaFiles(EngineConfig engineConfig) throws IOException {
        List<PathMatcher> includes = new ArrayList<>();
        for (String includeGlob : engineConfig.options().includeGlobs()) {
            includes.add(FileSystems.getDefault().getPathMatcher("glob:" + includeGlob));
        }

        Path root = engineConfig.repoRoot().toAbsolutePath().normalize();

        return Files.walk(root)
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> {
                    Path rel = root.relativize(p.toAbsolutePath().normalize());
                    return includes.isEmpty() || includes.stream().anyMatch(m -> m.matches(rel));
                });
    }
}
