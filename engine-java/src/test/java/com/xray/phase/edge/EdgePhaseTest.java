package com.xray.phase.edge;

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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdgePhaseTest {

    @TempDir
    Path tempDir;

    @Test
    void executePhaseProducesCallEdgesEndToEnd() throws IOException {
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

        new EdgePhase(objectMapper, outputLayout).executePhase(astIndex);

        List<Edge> edges = readEdges(outputLayout, objectMapper);
        assertFalse(edges.isEmpty());
        assertTrue(edges.stream().anyMatch(edge -> edge.type() == Enums.EdgeType.CALLS));
    }

    @Test
    void executePhaseProducesPersistenceHitEdgeForRepositoryCall() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        OutputLayout outputLayout = createOutputLayout();

        Path serviceFile = tempDir.resolve("OrderService.java");
        Files.writeString(serviceFile, """
                @Service
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

        new EdgePhase(objectMapper, outputLayout).executePhase(astIndex);

        List<Edge> edges = readEdges(outputLayout, objectMapper);
        assertTrue(edges.stream().anyMatch(edge -> edge.type() == Enums.EdgeType.PERSISTENCE_HIT && edge.toId().equals("OrderRepository")));
    }

    @Test
    void executePhaseProducesOutboundCallEdgeForFeignCall() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        OutputLayout outputLayout = createOutputLayout();

        Path serviceFile = tempDir.resolve("OrderService.java");
        Files.writeString(serviceFile, """
                @Service
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

        new EdgePhase(objectMapper, outputLayout).executePhase(astIndex);

        List<Edge> edges = readEdges(outputLayout, objectMapper);
        assertTrue(edges.stream().anyMatch(edge -> edge.type() == Enums.EdgeType.OUTBOUND_CALL && edge.toId().equals("outbound:feign:payments-service")));
    }

    @Test
    void executePhaseProducesOutboundCallEdgeForRestClientCall() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        OutputLayout outputLayout = createOutputLayout();

        Path serviceFile = tempDir.resolve("OrderService.java");
        Files.writeString(serviceFile, """
                @Service
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

        new EdgePhase(objectMapper, outputLayout).executePhase(astIndex);

        List<Edge> edges = readEdges(outputLayout, objectMapper);
        assertTrue(edges.stream().anyMatch(edge -> edge.type() == Enums.EdgeType.OUTBOUND_CALL && edge.toId().equals("outbound:http")));
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
