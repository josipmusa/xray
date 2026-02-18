package com.xray.model;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

import static com.xray.model.Enums.*;

/**
 * V1 Node contract.
 * <p>
 * id:
 * - CLASS: fqcn (e.g. "com.acme.DiscountService")
 * - METHOD: "fqcn#method(arg1,arg2):return" or your chosen stable key
 * - ENTRYPOINT: can be same as METHOD id or "entry:http:METHOD_ID"
 * <p>
 * tags:
 * stable queryable labels (spring.controller, spring.service, spring.repo, http.POST, ...)
 * <p>
 * attributes:
 * flexible structured metadata (httpPath, beanName, outboundHost, sqlHint, ...)
 * <p>
 * hash:
 * stable-ish content hash used for incremental rebuilds + summary caching
 */
public record Node(
        String id,
        NodeKind kind,
        String name,
        String fqcn,
        @Nullable String signature,
        String ownerId,
        SourceRange source,
        List<String> modifiers,
        List<AnnotationRef> annotations,
        List<String> tags,
        Map<String, Object> attributes,
        String hash,
        int version) {

    public static Node v1(
            String id,
            NodeKind kind,
            String name,
            String fqcn,
            String signature,
            String ownerId,
            SourceRange source,
            List<String> modifiers,
            List<String> annotations,
            List<String> tags,
            Map<String, Object> attributes) {
        List<AnnotationRef> annotationRefs = annotations.stream()
                .map(a -> new AnnotationRef(a, null))
                .toList();
        return new Node(
                id, kind, name, fqcn, signature, ownerId, source,
                modifiers, annotationRefs, tags, attributes,
                null,
                SchemaVersion.V1
        );
    }
}
