package com.xray.spring;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.xray.model.SpringBeanAnnotationAttributes;

import java.beans.Introspector;
import java.util.*;
import java.util.function.Supplier;

public final class AnnotationHelper {

    public static final String VALUE_ATTRIBUTE_NAME = "value";
    @SuppressWarnings("StaticCollection")
    private static final Set<String> HTTP_METHODS = Set.of(
            "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE"
    );

    private AnnotationHelper() {
    }

    // ---------- Public-ish helpers ----------

    static Optional<AnnotationExpr> findAnnotation(List<AnnotationExpr> annotations, String annotationSimpleName) {
        return annotations.stream()
                .filter(a -> simpleName(a).equals(annotationSimpleName))
                .findFirst();
    }

    static List<String> extractRequestMappingPaths(AnnotationExpr requestMapping) {
        // Spring supports both "value" and "path". If both exist, treat them as additive.
        List<String> out = new ArrayList<>();

        out.addAll(extractAttributeValues(requestMapping, VALUE_ATTRIBUTE_NAME, List::of));
        out.addAll(extractAttributeValues(requestMapping, "path", List::of));

        // @RequestMapping("/users") (single-member) is already handled by "value" above.
        // But our extractAttributeValues only treats single-member as "value", so we're good.

        // Remove blanks/dupes while preserving order.
        return out.stream()
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .toList();
    }

    static List<String> extractRequestMappingMethods(AnnotationExpr requestMapping) {
        // Typically: method = RequestMethod.GET or method = {RequestMethod.GET, ...}
        List<String> raw = extractAttributeValues(requestMapping, "method", List::of);

        return raw.stream()
                .map(AnnotationHelper::extractEnumConstantName) // "RequestMethod.GET" -> "GET"
                .map(String::toUpperCase)
                .filter(HTTP_METHODS::contains)
                .distinct()
                .toList();
    }

    static SpringBeanAnnotationAttributes extractSpringBeanClassAttributes(ClassOrInterfaceDeclaration clazz, AnnotationExpr componentAnnotation) {
        String declaredType = clazz.getFullyQualifiedName().orElse(clazz.getNameAsString());

        SpringBeanAnnotationAttributes.SpringBeanAnnotationAttributesBuilder b =
                SpringBeanAnnotationAttributes.builder()
                        .source("component")
                        .declaredType(declaredType)
                        .beanName(beanNameFromStereotype(clazz, componentAnnotation));

        for (AnnotationExpr a : clazz.getAnnotations()) {
            switch (simpleName(a)) {
                case "Primary" -> // @Primary has no meaningful value; treat presence as true
                        b.primary(true);
                case "Qualifier" -> b.qualifier(extractAttributeValues(a, VALUE_ATTRIBUTE_NAME, List::of));
                case "Scope" -> b.scope(firstAttr(a, VALUE_ATTRIBUTE_NAME));
                case "Profile" -> b.profile(extractAttributeValues(a, VALUE_ATTRIBUTE_NAME, List::of));
                case "Conditional" -> b.conditional(true);
                default -> { /* ignore */ }
            }
        }

        return b.build();
    }

    // ---------- Core extraction logic ----------

    private static List<String> extractAttributeValues(
            AnnotationExpr annotation,
            String attributeName,
            Supplier<List<String>> inferredDefault
    ) {
        Objects.requireNonNull(annotation, "annotation");
        Objects.requireNonNull(attributeName, "attributeName");
        Objects.requireNonNull(inferredDefault, "inferredDefault");

        Optional<Expression> attrExpr = Optional.empty();

        if (annotation.isSingleMemberAnnotationExpr() && VALUE_ATTRIBUTE_NAME.equals(attributeName)) {
            attrExpr = Optional.of(annotation.asSingleMemberAnnotationExpr().getMemberValue());
        } else if (annotation.isNormalAnnotationExpr()) {
            attrExpr = annotation.asNormalAnnotationExpr().getPairs().stream()
                    .filter(p -> attributeName.equals(p.getNameAsString()))
                    .map(MemberValuePair::getValue)
                    .findFirst();
        }

        return attrExpr.map(expr -> toStringList(expr, inferredDefault))
                .orElseGet(inferredDefault);
    }

    // ---------- Private helpers ----------

    private static String beanNameFromStereotype(ClassOrInterfaceDeclaration clazz, AnnotationExpr stereotype) {
        // stereotype value="" is treated as "no explicit name" -> inferred
        List<String> v = extractAttributeValues(stereotype, VALUE_ATTRIBUTE_NAME,
                () -> List.of(Introspector.decapitalize(clazz.getNameAsString()))
        );

        // Ensure we still fall back if the explicit value was "" or blank
        String first = v.isEmpty() ? null : v.getFirst();
        if (first == null || first.isBlank()) {
            return Introspector.decapitalize(clazz.getNameAsString());
        }
        return first;
    }

    private static String firstAttr(AnnotationExpr a, String attr) {
        List<String> v = extractAttributeValues(a, attr, Collections::emptyList);
        if (v.isEmpty()) return null;
        String first = v.getFirst();
        return (first == null || first.isBlank()) ? null : first;
    }

    private static List<String> toStringList(Expression expr, Supplier<List<String>> inferredDefault) {
        if (expr == null) return inferredDefault.get();

        if (expr.isArrayInitializerExpr()) {
            List<String> out = expr.asArrayInitializerExpr().getValues().stream()
                    .map(AnnotationHelper::asBestEffortString)
                    .filter(Objects::nonNull)
                    .toList();
            return out.isEmpty() ? inferredDefault.get() : out;
        }

        String single = asBestEffortString(expr);
        if (single == null || single.isBlank()) return inferredDefault.get();
        return List.of(single);
    }

    private static String asBestEffortString(Expression e) {
        if (e == null) return null;
        if (e.isStringLiteralExpr()) return e.asStringLiteralExpr().getValue();

        // Common for RequestMethod.X, etc. Keep as source text for later resolution.
        if (e.isFieldAccessExpr()) return e.asFieldAccessExpr().toString();
        if (e.isNameExpr()) return e.asNameExpr().getNameAsString();

        return e.toString();
    }

    private static String extractEnumConstantName(String raw) {
        if (raw == null) return "";
        int idx = raw.lastIndexOf('.');
        return idx >= 0 ? raw.substring(idx + 1) : raw;
    }

    private static String simpleName(AnnotationExpr a) {
        String name = a.getNameAsString();
        int idx = name.lastIndexOf('.');
        return idx >= 0 ? name.substring(idx + 1) : name;
    }
}
