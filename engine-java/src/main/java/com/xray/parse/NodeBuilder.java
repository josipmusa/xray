package com.xray.parse;

import com.xray.model.Node;

import java.util.stream.Stream;

public final class NodeBuilder {

    public static Stream<Node> buildNodes(AstIndex astIndex) {
        return astIndex.nodeDrafts().values().stream()
                .map(NodeBuilder::toNode);
    }

    private static Node toNode(AstIndex.NodeDraft nodeDraft) {
        return Node.v1(
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
        );
    }
}
