package com.xray.parse;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithRange;
import com.xray.model.ParsePipelineResult;
import com.xray.model.ParseProblem;
import com.xray.model.SourceRange;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.xray.model.Enums.*;

@Slf4j
public final class ParsePipeline {

    private final JavaParser javaParser;

    public ParsePipeline(JavaParser javaParser) {
        this.javaParser = javaParser;
    }


    public ParsePipelineResult parseAll(Stream<Path> files) {
        AstIndex astIndex = new AstIndex();
        List<ParseProblem> parseProblems = new ArrayList<>();
        ParseStats stats = new ParseStats();

        //Explicit sequential - maybe will be swapped to parallel later
        files.sequential().forEach(file -> {
            ParseStatus parseStatus = parseFile(file, astIndex, parseProblems);
            stats.add(parseStatus);
        });

        return new ParsePipelineResult(
                astIndex,
                stats.total,
                stats.ok,
                stats.parseFailed,
                parseProblems
        );
    }

    /**
     * MUTATES AstIndex and ParseProblem
     */
    private ParseStatus parseFile(Path file, AstIndex astIndex, List<ParseProblem> parseProblems) {
        ParseResult<CompilationUnit> result;
        try {
            result = javaParser.parse(file);
        } catch (IOException | RuntimeException e) {
            log.error("Skipping file - Error reading {}, error message {}", file, e.getMessage());
            parseProblems.add(ParseProblem.error(file.toString(), e.getMessage()));
            return ParseStatus.PARSE_FAILED;
        }

        if (result.getResult().isEmpty() || !result.isSuccessful()) {
            for (Problem problem : result.getProblems()) {
                parseProblems.add(ParseProblem.error(file.toString(), problem.getMessage(),
                        problem.getLocation().flatMap(l -> l.getBegin().getRange().map(r -> r.begin.line)).orElse(null),
                        problem.getLocation().flatMap(l -> l.getBegin().getRange().map(r -> r.begin.column)).orElse(null))
                );
            }
            return ParseStatus.PARSE_FAILED;
        }

        if (result.isSuccessful() && !result.getProblems().isEmpty()) {
            for (Problem problem : result.getProblems()) {
                parseProblems.add(ParseProblem.warn(file.toString(), problem.getMessage(),
                        problem.getLocation().flatMap(l -> l.getBegin().getRange().map(r -> r.begin.line)).orElse(null),
                        problem.getLocation().flatMap(l -> l.getBegin().getRange().map(r -> r.begin.column)).orElse(null))
                );
            }
        }
        CompilationUnit compilationUnit = result.getResult().get();
        astIndex.putCompilationUnit(file, compilationUnit);

        for (ClassOrInterfaceDeclaration c : compilationUnit.findAll(ClassOrInterfaceDeclaration.class)) {
            String className = c.getNameAsString();
            String classFqn = c.getFullyQualifiedName().orElse(className);
            String classId = NodeIdGenerator.generateClassNodeId(c);
            List<String> classModifiers = c.getModifiers().stream()
                    .map((m -> m.getKeyword().asString()))
                    .toList();
            List<String> classAnnotations = c.getAnnotations().stream()
                    .map(AnnotationExpr::getNameAsString)
                    .toList();
            SourceRange classSourceRange = sourceRange(file, c);

            AstIndex.NodeDraft classDraft = new AstIndex.NodeDraft(
                    classId,
                    NodeKind.CLASS,
                    className,
                    classFqn,
                    null,
                    null,
                    classSourceRange,
                    classAnnotations,
                    classModifiers,
                    null,
                    null
            );

            astIndex.indexType(classFqn, className, classId, classDraft);

            for (MethodDeclaration m : c.getMethods()) {
                String methodName = m.getNameAsString();

                String methodKey = NodeIdGenerator.generateMethodNodeId(classFqn, m);
                String methodId = methodKey;
                List<String> methodModifiers = m.getModifiers().stream()
                        .map((modifier -> modifier.getKeyword().asString()))
                        .toList();
                List<String> methodAnnotations = m.getAnnotations().stream()
                        .map(AnnotationExpr::getNameAsString)
                        .toList();

                AstIndex.NodeDraft methodDraft = new AstIndex.NodeDraft(
                        methodId, //use methodKey as nodeId
                        NodeKind.METHOD,
                        methodName,
                        classFqn,
                        NodeIdGenerator.prettySignature(m),
                        classId,
                        sourceRange(file, m),
                        methodAnnotations,
                        methodModifiers,
                        null,
                        null
                );

                astIndex.indexMethod(methodKey, methodId, methodDraft);
            }
        }

        return ParseStatus.OK;
    }

    private enum ParseStatus {
        OK,
        PARSE_FAILED
    }

    private static final class ParseStats {
        long total;
        long ok;
        long parseFailed;

        void add(ParseStatus s) {
            total++;
            switch (s) {
                case OK -> ok++;
                case PARSE_FAILED -> parseFailed++;
            }
        }
    }

    private static SourceRange sourceRange(Path file, NodeWithRange<?> node) {
        int startLine = node.getBegin().map(p -> p.line).orElse(-1);
        int startCol = node.getBegin().map(p -> p.column).orElse(-1);
        int endLine = node.getEnd().map(p -> p.line).orElse(-1);
        int endCol = node.getEnd().map(p -> p.column).orElse(-1);
        return new SourceRange(file.toString(), startLine, startCol, endLine, endCol);
    }
}
