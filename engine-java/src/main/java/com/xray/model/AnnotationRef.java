package com.xray.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Represents an annotation with best-effort extracted values.
 * Example:
 *  @GetMapping("/discount") -> name="GetMapping", values={"value":"/discount"}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnnotationRef(
        String name,                 // simple name or FQCN if you have it
        Map<String, Object> values   // primitives/strings/arrays/maps best-effort
) {}
