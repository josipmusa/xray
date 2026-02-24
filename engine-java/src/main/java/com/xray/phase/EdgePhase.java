package com.xray.phase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xray.io.JsonlWriter;
import com.xray.io.OutputLayout;
import com.xray.model.Edge;
import com.xray.model.Enums;
import com.xray.parse.AstIndex;

import java.io.IOException;

public final class EdgePhase {

    private final ObjectMapper objectMapper;
    private final OutputLayout outputLayout;

    public EdgePhase(ObjectMapper objectMapper, OutputLayout outputLayout) {
        this.objectMapper = objectMapper;
        this.outputLayout = outputLayout;
    }

    public void processEdges(AstIndex astIndex) throws IOException {
        try (JsonlWriter edgeWriter = new JsonlWriter(outputLayout.getEdges(), objectMapper)) {
            for (AstIndex.NodeDraft nodeDraft : astIndex.nodeDrafts().values()) {
                if (nodeDraft.kind() == Enums.NodeKind.METHOD) {
                    Edge edge = Edge.v1(
                            nodeDraft.ownerId(),
                            nodeDraft.id(),
                            Enums.EdgeType.CONTAINS,
                            Enums.Confidence.HIGH //deterministic
                    );
                    edgeWriter.writeObject(edge);
                } else if (nodeDraft.kind() == Enums.NodeKind.CLASS) {

                }
            }
        }
    }
}
