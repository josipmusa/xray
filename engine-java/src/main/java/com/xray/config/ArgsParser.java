package com.xray.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class ArgsParser {

    public static EngineConfig parse(String[] args) throws IOException {
        Map<String, String> argsMap = parseArgs(args);

        String input = argsMap.get("input");
        String out = argsMap.get("out");

        if (input == null || out == null) {
            System.err.println("Usage: java -jar xray-engine.jar --input <repoPath> --out <outDir>");
            System.exit(2);
        }

        Path repoRoot = Paths.get(input).toAbsolutePath().normalize();
        Path outDir = Path.of(out);
        Files.createDirectories(outDir);

        EngineConfig.Options options = new EngineConfig.Options(
                Set.of("src/main/java/**"),
                Set.of(),
                false,
                false,
                500
        );
        return new EngineConfig(
                repoRoot,
                outDir,
                options
        );
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--input") && i + 1 < args.length) {
                m.put("input", args[++i]);
            } else if (a.equals("--out") && i + 1 < args.length) {
                m.put("out", args[++i]);
            }
        }
        return m;
    }
}
