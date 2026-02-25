package com.xray.phase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xray.io.IndexWriter;
import com.xray.io.JsonlWriter;
import com.xray.io.OutputLayout;
import com.xray.model.EntrypointIndex;
import com.xray.model.Node;
import com.xray.parse.AstIndex;
import com.xray.spring.BeanDetector;
import com.xray.spring.EntrypointDetector;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Slf4j
public final class NodePhase {

    private final IndexWriter indexWriter;
    private final ObjectMapper objectMapper;
    private final OutputLayout outputLayout;

    public NodePhase(ObjectMapper objectMapper, OutputLayout outputLayout) {
        this.objectMapper = objectMapper;
        this.indexWriter = new IndexWriter(objectMapper);
        this.outputLayout = outputLayout;
    }

    public Result executePhase(AstIndex astIndex) throws IOException {
        EntrypointIndex entrypointIndex = EntrypointDetector.annotateEntrypoints(astIndex);
        BeanDetector.annotateBeans(astIndex);

        long nodesWritten = writeNodes(astIndex);
        indexWriter.writeEntrypoints(outputLayout, entrypointIndex);

        return new Result(nodesWritten);
    }

    private long writeNodes(AstIndex astIndex) throws IOException {
        Map<String, List<String>> nameToIds = new HashMap<>();
        Map<String, List<String>> fileToIds = new HashMap<>();
        try (JsonlWriter nodeWriter = new JsonlWriter(outputLayout.getNodes(), objectMapper)) {
            long nodesWritten = buildNodes(astIndex)
                    .map(node -> {
                        try {
                            nodeWriter.writeObject(node);
                            nameToIds.computeIfAbsent(node.name(), k -> new ArrayList<>()).add(node.id());
                            nameToIds.computeIfAbsent(node.name().toLowerCase(), k -> new ArrayList<>()).add(node.id());
                            nameToIds.computeIfAbsent(node.fqcn(), k -> new ArrayList<>()).add(node.id());
                            fileToIds.computeIfAbsent(node.source().file(), k -> new ArrayList<>()).add(node.id());
                            return true;
                        } catch (IOException e) {
                            log.error("Error writing node, skipping", e);
                            return false;
                        }
                    })
                    .filter(Boolean::booleanValue)
                    .count();

            indexWriter.writeNameToIds(outputLayout, nameToIds);
            indexWriter.writeFileToIds(outputLayout, fileToIds);

            return nodesWritten;
        }
    }

    private static Stream<Node> buildNodes(AstIndex astIndex) {
        return astIndex.nodeDrafts().values().stream()
                .map(nodeDraft -> Node.v1(
                        nodeDraft.id(),
                        nodeDraft.kind(),
                        nodeDraft.name(),
                        nodeDraft.fqcn(),
                        nodeDraft.signature(),
                        nodeDraft.ownerId(),
                        nodeDraft.source(),
                        nodeDraft.modifiers(),
                        nodeDraft.annotations(),
                        nodeDraft.tags(),
                        nodeDraft.attributes()
                ));
    }

    public record Result(long nodesWritten) {}
}
