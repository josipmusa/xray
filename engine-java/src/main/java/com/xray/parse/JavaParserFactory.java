package com.xray.parse;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public final class JavaParserFactory {

    public static JavaParser initialize(Path repoRoot) throws IOException {
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();

        // JDK types: String, List, Optional, etc.
        typeSolver.add(new ReflectionTypeSolver());

        // Project sources
        List<Path> sourceRoots = findJavaSourceRoots(repoRoot);
        for (Path root : sourceRoots) {
            typeSolver.add(new JavaParserTypeSolver(root));
        }
        return new JavaParser(new ParserConfiguration()
                .setCharacterEncoding(StandardCharsets.UTF_8)
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setSymbolResolver(new JavaSymbolSolver(typeSolver))
        );
    }

    private static List<Path> findJavaSourceRoots(Path repoRoot) throws IOException {
        List<Path> roots = new ArrayList<>();

        try (Stream<Path> s = Files.walk(repoRoot)) {
            s.filter(Files::isDirectory)
                    .filter(p -> p.endsWith(Paths.get("src", "main", "java")))
                    // skip build output folders
                    .filter(p -> !p.toString().contains(FileSystems.getDefault().getSeparator() + "target" + FileSystems.getDefault().getSeparator()))
                    .filter(p -> !p.toString().contains(FileSystems.getDefault().getSeparator() + "build" + FileSystems.getDefault().getSeparator()))
                    .forEach(roots::add);
        }

        // de-dupe and sort for stable behavior
        return roots.stream().distinct().sorted().toList();
    }
}
