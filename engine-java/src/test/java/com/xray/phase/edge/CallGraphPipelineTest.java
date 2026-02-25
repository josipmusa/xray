package com.xray.phase.edge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.xray.engine.NodeIdGenerator;
import com.xray.io.OutputLayout;
import com.xray.model.Edge;
import com.xray.model.Enums;
import com.xray.parse.AstIndex;
import com.xray.parse.JavaParserFactory;
import com.xray.parse.ParsePipeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallGraphPipelineTest {

    @TempDir
    Path tempDir;

    @Test
    void emitsCallEdgeForImplicitThisMethodCall() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        OutputLayout outputLayout = createOutputLayout();

        Path sourceFile = tempDir.resolve("OrderService.java");
        Files.writeString(sourceFile, """
                class OrderService {
                    void process() {
                        validate();
                    }

                    void validate() {}
                }
                """);

        ParsePipeline parsePipeline = new ParsePipeline(JavaParserFactory.initialize(tempDir), objectMapper, outputLayout);
        AstIndex astIndex = parsePipeline.parseAll(Stream.of(sourceFile)).astIndex();

        ClassOrInterfaceDeclaration clazz = firstClass(astIndex).orElseThrow();
        CallGraphPipelineInput input = new CallGraphPipelineInput(
                List.of(new CallGraphPipelineInput.ClassData("OrderService", clazz, null))
        );

        new CallGraphPipeline(objectMapper, outputLayout).emitCallGraphs(input);

        List<Edge> callEdges = readEdges(outputLayout, objectMapper);
        assertEquals(1, callEdges.size());

        Edge edge = callEdges.getFirst();
        assertEquals(Enums.EdgeType.CALLS, edge.type());
        assertTrue(edge.confidence() == Enums.Confidence.HIGH || edge.confidence() == Enums.Confidence.MEDIUM);
    }

    @Test
    void emitsHighConfidenceCallEdgeForStaticMethodCall() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        OutputLayout outputLayout = createOutputLayout();

        Path sourceFile = tempDir.resolve("OrderService.java");
        Files.writeString(sourceFile, """
                class OrderService {
                    void process() {
                        OrderService.validate();
                    }

                    static void validate() {}
                }
                """);

        ParsePipeline parsePipeline = new ParsePipeline(JavaParserFactory.initialize(tempDir), objectMapper, outputLayout);
        AstIndex astIndex = parsePipeline.parseAll(Stream.of(sourceFile)).astIndex();

        ClassOrInterfaceDeclaration clazz = firstClass(astIndex).orElseThrow();
        CallGraphPipelineInput input = new CallGraphPipelineInput(
                List.of(new CallGraphPipelineInput.ClassData("OrderService", clazz, null))
        );

        new CallGraphPipeline(objectMapper, outputLayout).emitCallGraphs(input);

        List<Edge> callEdges = readEdges(outputLayout, objectMapper);
        assertEquals(1, callEdges.size());

        Edge edge = callEdges.getFirst();
        assertEquals(Enums.EdgeType.CALLS, edge.type());
        assertEquals(Enums.Confidence.HIGH, edge.confidence());

        var processMethod = clazz.getMethodsByName("process").getFirst();
        assertEquals(NodeIdGenerator.generateMethodNodeId("OrderService", processMethod), edge.fromId());
        assertEquals("OrderService#validate():void", edge.toId());
    }

    private Optional<ClassOrInterfaceDeclaration> firstClass(AstIndex astIndex) {
        return astIndex.fileToCu().values().stream()
                .map(cu -> cu.findFirst(ClassOrInterfaceDeclaration.class))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    private OutputLayout createOutputLayout() throws IOException {
        Path outputRoot = tempDir.resolve(".xray");
        Files.createDirectories(outputRoot);
        return new OutputLayout(outputRoot);
    }

    private List<Edge> readEdges(OutputLayout outputLayout, ObjectMapper objectMapper) throws IOException {
        Path edgesFile = outputLayout.getEdges();
        if (!Files.exists(edgesFile)) {
            return List.of();
        }
        try (Stream<String> lines = Files.lines(edgesFile)) {
            return lines
                    .filter(line -> !line.isBlank())
                    .map(line -> {
                        try {
                            return objectMapper.readValue(line, Edge.class);
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to parse edge line: " + line, e);
                        }
                    })
                    .toList();
        }
    }
}
