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
import com.xray.phase.edge.callgraph.CallGraphPipeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

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
        CallGraphPipeline.Input input = new CallGraphPipeline.Input(
                List.of(new CallGraphPipeline.Input.ClassData("OrderService", clazz, null))
        );

        new CallGraphPipeline(objectMapper, outputLayout).emitEdges(input);

        List<Edge> callEdges = readEdges(outputLayout, objectMapper);
        assertEquals(1, callEdges.size());

        Edge edge = callEdges.getFirst();
        assertEquals(Enums.EdgeType.CALLS, edge.type());
        assertTrue(edge.confidence() == Enums.Confidence.HIGH || edge.confidence() == Enums.Confidence.MEDIUM);
        assertEvidenceFileEndsWith(edge, "OrderService.java");
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
        CallGraphPipeline.Input input = new CallGraphPipeline.Input(
                List.of(new CallGraphPipeline.Input.ClassData("OrderService", clazz, null))
        );

        new CallGraphPipeline(objectMapper, outputLayout).emitEdges(input);

        List<Edge> callEdges = readEdges(outputLayout, objectMapper);
        assertEquals(1, callEdges.size());

        Edge edge = callEdges.getFirst();
        assertEquals(Enums.EdgeType.CALLS, edge.type());
        assertEquals(Enums.Confidence.HIGH, edge.confidence());
        assertEvidenceFileEndsWith(edge, "OrderService.java");

        var processMethod = clazz.getMethodsByName("process").getFirst();
        assertEquals(NodeIdGenerator.generateMethodNodeId("OrderService", processMethod), edge.fromId());
        assertEquals("OrderService#validate():void", edge.toId());
    }

    @Test
    void emitsCallEdgeForInjectedFieldMethodCall() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        OutputLayout outputLayout = createOutputLayout();

        Path serviceFile = tempDir.resolve("OrderService.java");
        Files.writeString(serviceFile, """
                class OrderService {
                    private final OrderRepository orderRepository;

                    OrderService(OrderRepository orderRepository) {
                        this.orderRepository = orderRepository;
                    }

                    void process() {
                        orderRepository.save();
                    }
                }
                """);

        Path repositoryFile = tempDir.resolve("OrderRepository.java");
        Files.writeString(repositoryFile, """
                class OrderRepository {
                    void save() {}
                }
                """);

        ParsePipeline parsePipeline = new ParsePipeline(JavaParserFactory.initialize(tempDir), objectMapper, outputLayout);
        AstIndex astIndex = parsePipeline.parseAll(Stream.of(serviceFile, repositoryFile)).astIndex();

        ClassOrInterfaceDeclaration serviceClass = findClass(astIndex, "OrderService").orElseThrow();
        ClassOrInterfaceDeclaration repositoryClass = findClass(astIndex, "OrderRepository").orElseThrow();
        CallGraphPipeline.Input input = new CallGraphPipeline.Input(
                List.of(
                        new CallGraphPipeline.Input.ClassData(
                                "OrderService",
                                serviceClass,
                                List.of(new CallGraphPipeline.Input.InjectedField("orderRepository", "OrderRepository"))
                        ),
                        new CallGraphPipeline.Input.ClassData("OrderRepository", repositoryClass, List.of())
                )
        );

        new CallGraphPipeline(objectMapper, outputLayout).emitEdges(input);

        List<Edge> callEdges = readEdges(outputLayout, objectMapper);
        assertEquals(1, callEdges.size());

        Edge edge = callEdges.getFirst();
        assertEquals(Enums.EdgeType.CALLS, edge.type());
        assertTrue(edge.confidence() == Enums.Confidence.HIGH || edge.confidence() == Enums.Confidence.MEDIUM);
        assertEvidenceFileEndsWith(edge, "OrderService.java");

        var processMethod = serviceClass.getMethodsByName("process").getFirst();
        var saveMethod = repositoryClass.getMethodsByName("save").getFirst();
        assertEquals(NodeIdGenerator.generateMethodNodeId("OrderService", processMethod), edge.fromId());
        assertEquals(NodeIdGenerator.generateMethodNodeId("OrderRepository", saveMethod), edge.toId());
    }

    @Test
    void emitsLowConfidenceUncertainEdgeWhenInjectedFieldTargetMethodCannotBeResolved() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        OutputLayout outputLayout = createOutputLayout();

        Path serviceFile = tempDir.resolve("OrderService.java");
        Files.writeString(serviceFile, """
                class OrderService {
                    private final OrderRepository orderRepository;

                    OrderService(OrderRepository orderRepository) {
                        this.orderRepository = orderRepository;
                    }

                    void process() {
                        orderRepository.missing();
                    }
                }
                """);

        Path repositoryFile = tempDir.resolve("OrderRepository.java");
        Files.writeString(repositoryFile, """
                class OrderRepository {
                    void save() {}
                }
                """);

        ParsePipeline parsePipeline = new ParsePipeline(JavaParserFactory.initialize(tempDir), objectMapper, outputLayout);
        AstIndex astIndex = parsePipeline.parseAll(Stream.of(serviceFile, repositoryFile)).astIndex();

        ClassOrInterfaceDeclaration serviceClass = findClass(astIndex, "OrderService").orElseThrow();
        ClassOrInterfaceDeclaration repositoryClass = findClass(astIndex, "OrderRepository").orElseThrow();
        CallGraphPipeline.Input input = new CallGraphPipeline.Input(
                List.of(
                        new CallGraphPipeline.Input.ClassData(
                                "OrderService",
                                serviceClass,
                                List.of(new CallGraphPipeline.Input.InjectedField("orderRepository", "OrderRepository"))
                        ),
                        new CallGraphPipeline.Input.ClassData("OrderRepository", repositoryClass, List.of())
                )
        );

        new CallGraphPipeline(objectMapper, outputLayout).emitEdges(input);

        List<Edge> callEdges = readEdges(outputLayout, objectMapper);
        assertEquals(1, callEdges.size());

        Edge edge = callEdges.getFirst();
        assertEquals(Enums.EdgeType.CALLS, edge.type());
        assertEquals(Enums.Confidence.LOW, edge.confidence());
        assertEquals("OrderRepository", edge.toId());
        assertEquals(true, edge.attributes().get("uncertainTargetMethod"));
        assertEquals("missing", edge.attributes().get("targetMethodName"));
        assertEquals(0, edge.attributes().get("targetMethodArity"));
        assertEquals("orderRepository", edge.attributes().get("injectedField"));
        assertTrue(edge.evidence() != null && !edge.evidence().isEmpty());
        assertEvidenceFileEndsWith(edge, "OrderService.java");
    }

    @Test
    void emitsPersistenceHitForSpringDataRepositoryCall() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        OutputLayout outputLayout = createOutputLayout();

        Path serviceFile = tempDir.resolve("OrderService.java");
        Files.writeString(serviceFile, """
                class OrderService {
                    private final OrderRepository orderRepository;

                    OrderService(OrderRepository orderRepository) {
                        this.orderRepository = orderRepository;
                    }

                    void process() {
                        orderRepository.findAll();
                    }
                }
                """);

        Path repositoryFile = tempDir.resolve("OrderRepository.java");
        Files.writeString(repositoryFile, """
                interface OrderRepository extends JpaRepository<Order, Long> {}
                class Order {}
                """);

        ParsePipeline parsePipeline = new ParsePipeline(JavaParserFactory.initialize(tempDir), objectMapper, outputLayout);
        AstIndex astIndex = parsePipeline.parseAll(Stream.of(serviceFile, repositoryFile)).astIndex();

        ClassOrInterfaceDeclaration serviceClass = findClass(astIndex, "OrderService").orElseThrow();
        ClassOrInterfaceDeclaration repositoryClass = findClass(astIndex, "OrderRepository").orElseThrow();
        CallGraphPipeline.Input input = new CallGraphPipeline.Input(
                List.of(
                        new CallGraphPipeline.Input.ClassData(
                                "OrderService",
                                serviceClass,
                                List.of(new CallGraphPipeline.Input.InjectedField("orderRepository", "OrderRepository"))
                        ),
                        new CallGraphPipeline.Input.ClassData("OrderRepository", repositoryClass, List.of())
                )
        );

        new CallGraphPipeline(objectMapper, outputLayout).emitEdges(input);

        List<Edge> edges = readEdges(outputLayout, objectMapper);
        assertTrue(edges.stream().anyMatch(edge -> edge.type() == Enums.EdgeType.PERSISTENCE_HIT && edge.toId().equals("OrderRepository")));
    }

    @Test
    void emitsPersistenceHitForJdbcTemplateCall() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        OutputLayout outputLayout = createOutputLayout();

        Path serviceFile = tempDir.resolve("OrderService.java");
        Files.writeString(serviceFile, """
                class OrderService {
                    private final JdbcTemplate jdbcTemplate;

                    OrderService(JdbcTemplate jdbcTemplate) {
                        this.jdbcTemplate = jdbcTemplate;
                    }

                    void process() {
                        jdbcTemplate.queryForList("select 1");
                    }
                }
                """);

        ParsePipeline parsePipeline = new ParsePipeline(JavaParserFactory.initialize(tempDir), objectMapper, outputLayout);
        AstIndex astIndex = parsePipeline.parseAll(Stream.of(serviceFile)).astIndex();

        ClassOrInterfaceDeclaration serviceClass = findClass(astIndex, "OrderService").orElseThrow();
        CallGraphPipeline.Input input = new CallGraphPipeline.Input(
                List.of(new CallGraphPipeline.Input.ClassData(
                        "OrderService",
                        serviceClass,
                        List.of(new CallGraphPipeline.Input.InjectedField("jdbcTemplate", "JdbcTemplate"))
                ))
        );

        new CallGraphPipeline(objectMapper, outputLayout).emitEdges(input);

        List<Edge> edges = readEdges(outputLayout, objectMapper);
        assertTrue(edges.stream().anyMatch(edge -> edge.type() == Enums.EdgeType.PERSISTENCE_HIT && edge.toId().equals("persistence:db")));
    }

    @Test
    void emitsPersistenceHitForEntityManagerCall() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        OutputLayout outputLayout = createOutputLayout();

        Path serviceFile = tempDir.resolve("OrderService.java");
        Files.writeString(serviceFile, """
                class OrderService {
                    private final EntityManager entityManager;

                    OrderService(EntityManager entityManager) {
                        this.entityManager = entityManager;
                    }

                    void process() {
                        entityManager.persist(new Order());
                    }
                }
                class Order {}
                """);

        ParsePipeline parsePipeline = new ParsePipeline(JavaParserFactory.initialize(tempDir), objectMapper, outputLayout);
        AstIndex astIndex = parsePipeline.parseAll(Stream.of(serviceFile)).astIndex();

        ClassOrInterfaceDeclaration serviceClass = findClass(astIndex, "OrderService").orElseThrow();
        CallGraphPipeline.Input input = new CallGraphPipeline.Input(
                List.of(new CallGraphPipeline.Input.ClassData(
                        "OrderService",
                        serviceClass,
                        List.of(new CallGraphPipeline.Input.InjectedField("entityManager", "EntityManager"))
                ))
        );

        new CallGraphPipeline(objectMapper, outputLayout).emitEdges(input);

        List<Edge> edges = readEdges(outputLayout, objectMapper);
        assertTrue(edges.stream().anyMatch(edge -> edge.type() == Enums.EdgeType.PERSISTENCE_HIT && edge.toId().equals("persistence:db")));
    }

    @Test
    void emitsOutboundCallForFeignClientCall() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        OutputLayout outputLayout = createOutputLayout();

        Path serviceFile = tempDir.resolve("OrderService.java");
        Files.writeString(serviceFile, """
                class OrderService {
                    private final PaymentsClient paymentsClient;

                    OrderService(PaymentsClient paymentsClient) {
                        this.paymentsClient = paymentsClient;
                    }

                    void process() {
                        paymentsClient.charge("42");
                    }
                }
                """);
        Path clientFile = tempDir.resolve("PaymentsClient.java");
        Files.writeString(clientFile, """
                @FeignClient(name = "payments-service")
                interface PaymentsClient {
                    String charge(String orderId);
                }
                """);

        ParsePipeline parsePipeline = new ParsePipeline(JavaParserFactory.initialize(tempDir), objectMapper, outputLayout);
        AstIndex astIndex = parsePipeline.parseAll(Stream.of(serviceFile, clientFile)).astIndex();

        ClassOrInterfaceDeclaration serviceClass = findClass(astIndex, "OrderService").orElseThrow();
        ClassOrInterfaceDeclaration clientClass = findClass(astIndex, "PaymentsClient").orElseThrow();
        CallGraphPipeline.Input input = new CallGraphPipeline.Input(
                List.of(
                        new CallGraphPipeline.Input.ClassData(
                                "OrderService",
                                serviceClass,
                                List.of(new CallGraphPipeline.Input.InjectedField("paymentsClient", "PaymentsClient"))
                        ),
                        new CallGraphPipeline.Input.ClassData("PaymentsClient", clientClass, List.of())
                )
        );

        new CallGraphPipeline(objectMapper, outputLayout).emitEdges(input);

        List<Edge> edges = readEdges(outputLayout, objectMapper);
        assertTrue(edges.stream().anyMatch(edge ->
                edge.type() == Enums.EdgeType.OUTBOUND_CALL
                        && edge.toId().equals("outbound:feign:payments-service")));
    }

    @Test
    void emitsOutboundCallForRestTemplateWithHints() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        OutputLayout outputLayout = createOutputLayout();

        Path serviceFile = tempDir.resolve("OrderService.java");
        Files.writeString(serviceFile, """
                class OrderService {
                    @Value("${payments.base-url}")
                    String paymentsBaseUrl;
                    private final RestTemplate restTemplate;

                    OrderService(RestTemplate restTemplate) {
                        this.restTemplate = restTemplate;
                    }

                    void process() {
                        restTemplate.getForObject("/payments", String.class);
                        restTemplate.getForObject(paymentsBaseUrl, String.class);
                    }
                }
                """);

        ParsePipeline parsePipeline = new ParsePipeline(JavaParserFactory.initialize(tempDir), objectMapper, outputLayout);
        AstIndex astIndex = parsePipeline.parseAll(Stream.of(serviceFile)).astIndex();

        ClassOrInterfaceDeclaration serviceClass = findClass(astIndex, "OrderService").orElseThrow();
        CallGraphPipeline.Input input = new CallGraphPipeline.Input(
                List.of(new CallGraphPipeline.Input.ClassData(
                        "OrderService",
                        serviceClass,
                        List.of(new CallGraphPipeline.Input.InjectedField("restTemplate", "RestTemplate"))
                ))
        );

        new CallGraphPipeline(objectMapper, outputLayout).emitEdges(input);

        List<Edge> edges = readEdges(outputLayout, objectMapper).stream()
                .filter(edge -> edge.type() == Enums.EdgeType.OUTBOUND_CALL && edge.toId().equals("outbound:http"))
                .toList();
        assertEquals(2, edges.size());
        assertTrue(edges.stream().anyMatch(edge -> "/payments".equals(edge.attributes().get("outbound.urlHint"))));
        assertTrue(edges.stream().anyMatch(edge -> "${payments.base-url}".equals(edge.attributes().get("outbound.hostHint"))));
    }

    @Test
    void emitsOutboundCallForWebClientUsage() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        OutputLayout outputLayout = createOutputLayout();

        Path serviceFile = tempDir.resolve("OrderService.java");
        Files.writeString(serviceFile, """
                class OrderService {
                    private final WebClient webClient;

                    OrderService(WebClient webClient) {
                        this.webClient = webClient;
                    }

                    void process() {
                        webClient.get();
                    }
                }
                """);

        ParsePipeline parsePipeline = new ParsePipeline(JavaParserFactory.initialize(tempDir), objectMapper, outputLayout);
        AstIndex astIndex = parsePipeline.parseAll(Stream.of(serviceFile)).astIndex();

        ClassOrInterfaceDeclaration serviceClass = findClass(astIndex, "OrderService").orElseThrow();
        CallGraphPipeline.Input input = new CallGraphPipeline.Input(
                List.of(new CallGraphPipeline.Input.ClassData(
                        "OrderService",
                        serviceClass,
                        List.of(new CallGraphPipeline.Input.InjectedField("webClient", "WebClient"))
                ))
        );

        new CallGraphPipeline(objectMapper, outputLayout).emitEdges(input);

        List<Edge> edges = readEdges(outputLayout, objectMapper);
        assertTrue(edges.stream().anyMatch(edge -> edge.type() == Enums.EdgeType.OUTBOUND_CALL && edge.toId().equals("outbound:http")));
    }

    @Test
    void emitsOutboundCallForRestClientUsage() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        OutputLayout outputLayout = createOutputLayout();

        Path serviceFile = tempDir.resolve("OrderService.java");
        Files.writeString(serviceFile, """
                class OrderService {
                    private final RestClient restClient;

                    OrderService(RestClient restClient) {
                        this.restClient = restClient;
                    }

                    void process() {
                        restClient.get();
                    }
                }
                """);

        ParsePipeline parsePipeline = new ParsePipeline(JavaParserFactory.initialize(tempDir), objectMapper, outputLayout);
        AstIndex astIndex = parsePipeline.parseAll(Stream.of(serviceFile)).astIndex();

        ClassOrInterfaceDeclaration serviceClass = findClass(astIndex, "OrderService").orElseThrow();
        CallGraphPipeline.Input input = new CallGraphPipeline.Input(
                List.of(new CallGraphPipeline.Input.ClassData(
                        "OrderService",
                        serviceClass,
                        List.of(new CallGraphPipeline.Input.InjectedField("restClient", "RestClient"))
                ))
        );

        new CallGraphPipeline(objectMapper, outputLayout).emitEdges(input);

        List<Edge> edges = readEdges(outputLayout, objectMapper);
        assertTrue(edges.stream().anyMatch(edge -> edge.type() == Enums.EdgeType.OUTBOUND_CALL && edge.toId().equals("outbound:http")));
    }

    private Optional<ClassOrInterfaceDeclaration> firstClass(AstIndex astIndex) {
        return astIndex.fileToCu().values().stream()
                .map(cu -> cu.findFirst(ClassOrInterfaceDeclaration.class))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    private Optional<ClassOrInterfaceDeclaration> findClass(AstIndex astIndex, String className) {
        return astIndex.fileToCu().values().stream()
                .flatMap(cu -> cu.findAll(ClassOrInterfaceDeclaration.class).stream())
                .filter(clazz -> clazz.getNameAsString().equals(className))
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

    private void assertEvidenceFileEndsWith(Edge edge, String expectedFileName) {
        assertNotNull(edge.evidence());
        assertFalse(edge.evidence().isEmpty());
        assertNotNull(edge.evidence().getFirst().file());
        assertTrue(edge.evidence().getFirst().file().endsWith(expectedFileName));
    }
}
