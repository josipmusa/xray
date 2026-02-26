package com.xray.phase.edge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.xray.io.OutputLayout;
import com.xray.model.Edge;
import com.xray.model.Enums;
import com.xray.parse.AstIndex;
import com.xray.parse.JavaParserFactory;
import com.xray.parse.ParsePipeline;
import com.xray.phase.edge.callgraph.CallGraphPipeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DIGraphPipelineTest {

    @TempDir
    Path tempDir;

    @Test
    void writesContainsEdgeForEachMethodNode() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        OutputLayout outputLayout = createOutputLayout();
        AstIndex astIndex = parseAll(
                objectMapper,
                outputLayout,
                file("OrderService.java", """
                        class OrderService {
                            void process() {}
                            String status(int code) { return \"ok\"; }
                        }
                        """)
        );

        new DIGraphPipeline(objectMapper, outputLayout).emitEdges(astIndex, buildFqcnToClassDecl(astIndex));

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
    void writesDiEdgeForBeanConstructorDependency() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        OutputLayout outputLayout = createOutputLayout();
        AstIndex astIndex = parseAll(
                objectMapper,
                outputLayout,
                file("OrderService.java", """
                        @Service
                        class OrderService {
                            private final OrderRepository orderRepository;

                            OrderService(OrderRepository orderRepository) {
                                this.orderRepository = orderRepository;
                            }
                        }
                        """),
                file("OrderRepository.java", """
                        class OrderRepository {}
                        """)
        );

        DIGraphPipeline.Result result = new DIGraphPipeline(objectMapper, outputLayout).emitEdges(astIndex, buildFqcnToClassDecl(astIndex));

        List<Edge> diEdges = readEdges(outputLayout, objectMapper).stream()
                .filter(edge -> edge.type() == Enums.EdgeType.DI)
                .toList();

        assertEquals(1, diEdges.size());

        Map<String, String> classIdByFqcn = astIndex.nodeDrafts().values().stream()
                .filter(draft -> draft.kind() == Enums.NodeKind.CLASS)
                .collect(Collectors.toMap(AstIndex.NodeDraft::fqcn, AstIndex.NodeDraft::id));

        assertEquals(classIdByFqcn.get("OrderService"), diEdges.getFirst().fromId());
        assertEquals(classIdByFqcn.get("OrderRepository"), diEdges.getFirst().toId());
        assertEquals(
                List.of(new CallGraphPipeline.Input.InjectedField("orderRepository", "OrderRepository")),
                result.injectedFieldsByClassFqcn().get("OrderService")
        );
    }

    @Test
    void writesDiEdgeForAutowiredFieldDependency() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        OutputLayout outputLayout = createOutputLayout();
        AstIndex astIndex = parseAll(
                objectMapper,
                outputLayout,
                file("PaymentService.java", """
                        @Service
                        class PaymentService {
                            @Autowired
                            private PaymentGateway paymentGateway;
                        }
                        """),
                file("PaymentGateway.java", """
                        class PaymentGateway {}
                        """)
        );

        DIGraphPipeline.Result result = new DIGraphPipeline(objectMapper, outputLayout).emitEdges(astIndex, buildFqcnToClassDecl(astIndex));

        List<Edge> diEdges = readEdges(outputLayout, objectMapper).stream()
                .filter(edge -> edge.type() == Enums.EdgeType.DI)
                .toList();

        assertEquals(1, diEdges.size());

        Map<String, String> classIdByFqcn = astIndex.nodeDrafts().values().stream()
                .filter(draft -> draft.kind() == Enums.NodeKind.CLASS)
                .collect(Collectors.toMap(AstIndex.NodeDraft::fqcn, AstIndex.NodeDraft::id));

        assertEquals(classIdByFqcn.get("PaymentService"), diEdges.getFirst().fromId());
        assertEquals(classIdByFqcn.get("PaymentGateway"), diEdges.getFirst().toId());
        assertEquals(
                List.of(new CallGraphPipeline.Input.InjectedField("paymentGateway", "PaymentGateway")),
                result.injectedFieldsByClassFqcn().get("PaymentService")
        );
    }

    @Test
    void writesNoEdgesWhenNoMethodNodesExist() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        OutputLayout outputLayout = createOutputLayout();
        AstIndex astIndex = parseAll(
                objectMapper,
                outputLayout,
                file("EmptyType.java", """
                        class EmptyType {}
                        """)
        );

        new DIGraphPipeline(objectMapper, outputLayout).emitEdges(astIndex, buildFqcnToClassDecl(astIndex));

        List<Edge> edges = readEdges(outputLayout, objectMapper);
        assertTrue(edges.isEmpty());
    }

    private OutputLayout createOutputLayout() throws IOException {
        Path outputRoot = tempDir.resolve(".xray");
        Files.createDirectories(outputRoot);
        return new OutputLayout(outputRoot);
    }

    private AstIndex parseAll(ObjectMapper objectMapper, OutputLayout outputLayout, SourceFile... sourceFiles) throws IOException {
        List<Path> files = new java.util.ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            Path path = tempDir.resolve(sourceFile.fileName());
            Files.writeString(path, sourceFile.source());
            files.add(path);
        }

        ParsePipeline parsePipeline = new ParsePipeline(JavaParserFactory.initialize(tempDir), objectMapper, outputLayout);
        return parsePipeline.parseAll(files.stream()).astIndex();
    }

    private Map<String, ClassOrInterfaceDeclaration> buildFqcnToClassDecl(AstIndex astIndex) {
        Map<String, ClassOrInterfaceDeclaration> map = new HashMap<>();
        for (CompilationUnit cu : astIndex.fileToCu().values()) {
            for (ClassOrInterfaceDeclaration c : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                c.getFullyQualifiedName().ifPresent(fqcn -> map.putIfAbsent(fqcn, c));
            }
        }
        return map;
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

    private SourceFile file(String fileName, String source) {
        return new SourceFile(fileName, source);
    }

    private record SourceFile(String fileName, String source) {}
}
