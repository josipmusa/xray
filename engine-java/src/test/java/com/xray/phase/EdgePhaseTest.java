package com.xray.phase;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class EdgePhaseTest {

    @TempDir
    Path tempDir;

    @Test
    void writesContainsEdgeForEachMethodNode() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        OutputLayout outputLayout = createOutputLayout();
        AstIndex astIndex = parse(
                """
                        class OrderService {
                            void process() {}
                            String status(int code) { return "ok"; }
                        }
                        """,
                "OrderService.java",
                objectMapper,
                outputLayout
        );

        new EdgePhase(objectMapper, outputLayout).processEdges(astIndex);

        List<Edge> edges = readEdges(outputLayout, objectMapper);
        assertEquals(2, edges.size());

        Map<String, AstIndex.NodeDraft> methodDraftById = astIndex.nodeDrafts().values().stream()
                .filter(d -> d.kind() == Enums.NodeKind.METHOD)
                .collect(Collectors.toMap(AstIndex.NodeDraft::id, Function.identity()));

        for (Edge edge : edges) {
            assertEquals(Enums.EdgeType.CONTAINS, edge.type());
            assertEquals(Enums.Confidence.HIGH, edge.confidence());

            AstIndex.NodeDraft methodDraft = methodDraftById.get(edge.toId());
            assertNotNull(methodDraft, "Edge points to unknown method node: " + edge.toId());
            assertEquals(methodDraft.ownerId(), edge.fromId());
        }
    }

    @Test
    void writesNoEdgesWhenNoMethodNodesExist() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        OutputLayout outputLayout = createOutputLayout();
        AstIndex astIndex = parse(
                """
                        class EmptyType {}
                        """,
                "EmptyType.java",
                objectMapper,
                outputLayout
        );

        new EdgePhase(objectMapper, outputLayout).processEdges(astIndex);

        List<Edge> edges = readEdges(outputLayout, objectMapper);
        assertTrue(edges.isEmpty());
    }

    private OutputLayout createOutputLayout() throws IOException {
        Path outputRoot = tempDir.resolve(".xray");
        Files.createDirectories(outputRoot);
        return new OutputLayout(outputRoot);
    }

    private AstIndex parse(String source, String fileName, ObjectMapper objectMapper, OutputLayout outputLayout) throws IOException {
        Path sourceFile = tempDir.resolve(fileName);
        Files.writeString(sourceFile, source);

        ParsePipeline parsePipeline = new ParsePipeline(JavaParserFactory.initialize(), objectMapper, outputLayout);
        return parsePipeline.parseAll(Stream.of(sourceFile)).astIndex();
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
