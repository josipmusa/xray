package com.xray.model;

import com.fasterxml.jackson.annotation.JsonInclude;

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
            String id,
            String fromId,
            String toId,
            EdgeType type,
            Confidence confidence
    ) {
        //TODO id generation via hash
        return new Edge(id, fromId, toId, type, confidence, List.of(), Map.of(), SchemaVersion.V1);
    }
}
