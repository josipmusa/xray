package com.xray.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.xray.util.Hashing;

import java.util.List;
import java.util.Map;

import static com.xray.model.Enums.*;

/**
 * V1 Edge contract.
 * <p>
 * confidence:
 *  - HIGH: resolved precisely (symbol solver success, unambiguous bean match, etc.)
 *  - MEDIUM: good heuristic (common constructor injection patterns, name matching, etc.)
 *  - LOW: guessy (unresolved interface dispatch, unknown target, etc.)
 * <p>
 * evidence:
 *  explain why the edge exists, with at least file+line whenever possible.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Edge(
        String id,               // unique edge id; can be generated (e.g. "e:<uuid>" or hash)
        String fromId,
        String toId,
        EdgeType type,
        Confidence confidence,
        List<Evidence> evidence,
        Map<String, Object> attributes,
        int version
) {
    public static Edge v1(
            String fromId,
            String toId,
            EdgeType type,
            Confidence confidence
    ) {
        String canonical =
                "edge:v1:" +
                        fromId + "|" +
                        toId + "|" +
                        type.name() + "|" +
                        confidence.name();
        String id = "e" + Hashing.sha256Hex(canonical);
        return new Edge(id, fromId, toId, type, confidence, List.of(), Map.of(), SchemaVersion.V1);
    }
}
