package com.xray.parse;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;

import java.nio.file.Path;
import java.util.*;

public final class EntrypointDetector {

    private EntrypointDetector() {}

    private static final String REST_CONTROLLER_ANNOTATION = "RestController";
    private static final String CONTROLLER_ANNOTATION = "Controller";
    private static final String REQUEST_MAPPING_ANNOTATION = "RequestMapping";
    private static final String GET_MAPPING_ANNOTATION = "GetMapping";
    private static final String POST_MAPPING_ANNOTATION = "PostMapping";
    private static final String PUT_MAPPING_ANNOTATION = "PutMapping";
    private static final String DELETE_MAPPING_ANNOTATION = "DeleteMapping";
    private static final String PATCH_MAPPING_ANNOTATION = "PatchMapping";

    private static final String SPRING_ENTRYPOINT_TAG = "spring.entrypoint.http";
    private static final String SPRING_REQUEST_MAPPING_TAG = "spring.request_mapping";

    private static final String HTTP_GET_TAG = "http.GET";
    private static final String HTTP_POST_TAG = "http.POST";
    private static final String HTTP_PUT_TAG = "http.PUT";
    private static final String HTTP_DELETE_TAG = "http.DELETE";
    private static final String HTTP_PATCH_TAG = "http.PATCH";
    private static final String HTTP_ANY_TAG = "http.ANY";

    private static final Map<String, String> HTTP_MAPPING_ANNOTATIONS = Map.of(
            GET_MAPPING_ANNOTATION, HTTP_GET_TAG,
            POST_MAPPING_ANNOTATION, HTTP_POST_TAG,
            PUT_MAPPING_ANNOTATION, HTTP_PUT_TAG,
            DELETE_MAPPING_ANNOTATION, HTTP_DELETE_TAG,
            PATCH_MAPPING_ANNOTATION, HTTP_PATCH_TAG
    );

    public static AstIndex annotateEntrypoints(AstIndex astIndex) {
        for (Map.Entry<Path, CompilationUnit> pathToCompilationUnit : astIndex.fileToCu().entrySet()) {
            CompilationUnit compilationUnit = pathToCompilationUnit.getValue();
            for (ClassOrInterfaceDeclaration clazz : compilationUnit.findAll(ClassOrInterfaceDeclaration.class)) {
                Optional<ControllerContext> controllerContext = buildControllerContext(clazz);
                if (controllerContext.isEmpty()) {
                    continue;
                }
                annotateClassMethods(astIndex, clazz, controllerContext.get());
            }
        }
        return astIndex;
    }

    private static Optional<ControllerContext> buildControllerContext(ClassOrInterfaceDeclaration clazz) {
        Optional<AnnotationExpr> restControllerAnnotation = findAnnotation(clazz.getAnnotations(), REST_CONTROLLER_ANNOTATION);
        Optional<AnnotationExpr> controllerAnnotation = findAnnotation(clazz.getAnnotations(), CONTROLLER_ANNOTATION);
        if (restControllerAnnotation.isEmpty() && controllerAnnotation.isEmpty()) {
            return Optional.empty();
        }

        String classFqcn = clazz.getFullyQualifiedName().orElse(clazz.getNameAsString());
        List<String> classPaths = findAnnotation(clazz.getAnnotations(), REQUEST_MAPPING_ANNOTATION)
                .map(EntrypointDetector::extractRequestMappingPaths)
                .filter(paths -> !paths.isEmpty())
                .orElse(List.of(""));

        return Optional.of(new ControllerContext(
                classFqcn,
                restControllerAnnotation.isPresent(),
                classPaths
        ));
    }

    private static void annotateClassMethods(AstIndex astIndex, ClassOrInterfaceDeclaration clazz, ControllerContext controllerContext) {
        for (MethodDeclaration method : clazz.getMethods()) {
            Optional<MethodEntrypointData> methodEntrypointData = detectMethodEntrypoint(method);
            if (methodEntrypointData.isEmpty()) {
                continue;
            }
            updateMethodDraft(astIndex, method, controllerContext, methodEntrypointData.get());
        }
    }

    private static Optional<MethodEntrypointData> detectMethodEntrypoint(MethodDeclaration method) {
        Set<String> endpointTags = new LinkedHashSet<>();
        Set<String> httpTags = new LinkedHashSet<>();
        List<String> methodPaths = new ArrayList<>();

        Optional<AnnotationExpr> requestMappingMethodAnnotation = findAnnotation(method.getAnnotations(), REQUEST_MAPPING_ANNOTATION);
        if (requestMappingMethodAnnotation.isPresent()) {
            endpointTags.add(SPRING_REQUEST_MAPPING_TAG);
            addMethodPaths(requestMappingMethodAnnotation.get(), methodPaths);

            List<String> requestMethods = extractRequestMappingMethods(requestMappingMethodAnnotation.get());
            if (requestMethods.isEmpty()) {
                httpTags.add(HTTP_ANY_TAG);
            } else {
                for (String requestMethod : requestMethods) {
                    httpTags.add("http." + requestMethod);
                }
            }
        }

        for (Map.Entry<String, String> httpMappingEntry : HTTP_MAPPING_ANNOTATIONS.entrySet()) {
            Optional<AnnotationExpr> mappingAnnotation = findAnnotation(method.getAnnotations(), httpMappingEntry.getKey());
            if (mappingAnnotation.isEmpty()) {
                continue;
            }
            httpTags.add(httpMappingEntry.getValue());
            addMethodPaths(mappingAnnotation.get(), methodPaths);
        }

        if (endpointTags.isEmpty() && httpTags.isEmpty()) {
            return Optional.empty();
        }
        if (methodPaths.isEmpty()) {
            methodPaths.add("");
        }

        return Optional.of(new MethodEntrypointData(endpointTags, httpTags, methodPaths));
    }

    private static void addMethodPaths(AnnotationExpr annotation, List<String> methodPaths) {
        List<String> extracted = extractRequestMappingPaths(annotation);
        if (extracted.isEmpty()) {
            methodPaths.add("");
        } else {
            methodPaths.addAll(extracted);
        }
    }

    /**
     * Mutates node drafts in AstIndex
     */
    private static void updateMethodDraft(
            AstIndex astIndex,
            MethodDeclaration method,
            ControllerContext controllerContext,
            MethodEntrypointData methodEntrypointData
    ) {
        String nodeId = MethodKeys.keyFor(controllerContext.classFqcn(), method);
        AstIndex.NodeDraft existingMethodDraft = astIndex.nodeDrafts().get(nodeId);
        if (existingMethodDraft == null) {
            return;
        }

        Set<String> mergedTags = mergeTags(existingMethodDraft, methodEntrypointData);
        Map<String, Object> mergedAttributes = mergeAttributes(existingMethodDraft, controllerContext, methodEntrypointData);

        AstIndex.NodeDraft updatedMethodDraft = new AstIndex.NodeDraft(
                existingMethodDraft.id(),
                existingMethodDraft.kind(),
                existingMethodDraft.name(),
                existingMethodDraft.fqcn(),
                existingMethodDraft.signature(),
                existingMethodDraft.ownerId(),
                existingMethodDraft.source(),
                existingMethodDraft.annotations(),
                existingMethodDraft.modifiers(),
                List.copyOf(mergedTags),
                Map.copyOf(mergedAttributes)
        );
        astIndex.updateDraft(nodeId, updatedMethodDraft);
    }

    private static Set<String> mergeTags(AstIndex.NodeDraft existingMethodDraft, MethodEntrypointData methodEntrypointData) {
        Set<String> mergedTags = new LinkedHashSet<>();
        if (existingMethodDraft.tags() != null) {
            mergedTags.addAll(existingMethodDraft.tags());
        }
        mergedTags.add(SPRING_ENTRYPOINT_TAG);
        mergedTags.addAll(methodEntrypointData.endpointTags());
        mergedTags.addAll(methodEntrypointData.httpTags());
        return mergedTags;
    }

    private static Map<String, Object> mergeAttributes(
            AstIndex.NodeDraft existingMethodDraft,
            ControllerContext controllerContext,
            MethodEntrypointData methodEntrypointData
    ) {
        Map<String, Object> mergedAttributes = new LinkedHashMap<>();
        if (existingMethodDraft.attributes() != null) {
            mergedAttributes.putAll(existingMethodDraft.attributes());
        }

        List<String> normalizedClassPaths = normalizePaths(controllerContext.classPaths());
        List<String> normalizedMethodPaths = normalizePaths(methodEntrypointData.methodPaths());
        List<String> fullPaths = combinePaths(normalizedClassPaths, normalizedMethodPaths);

        mergedAttributes.put("entrypoint.kind", "HTTP");
        mergedAttributes.put("entrypoint.paths", fullPaths);
        mergedAttributes.put("entrypoint.classPaths", normalizedClassPaths);
        mergedAttributes.put("entrypoint.methodPaths", normalizedMethodPaths);
        mergedAttributes.put("entrypoint.httpMethods", httpMethodsFromTags(methodEntrypointData.httpTags()));
        mergedAttributes.put("entrypoint.controllerType", controllerContext.restController() ? "REST_CONTROLLER" : "CONTROLLER");
        return mergedAttributes;
    }

    private static List<String> extractRequestMappingPaths(AnnotationExpr annotation) {
        List<String> paths = new ArrayList<>();

        // @RequestMapping("/users")
        if (annotation.isSingleMemberAnnotationExpr()) {
            extractFromExpression(
                    annotation.asSingleMemberAnnotationExpr().getMemberValue(),
                    paths
            );
        }

        // @RequestMapping(value = "/users") or path = "/users"
        if (annotation.isNormalAnnotationExpr()) {
            for (MemberValuePair pair :
                    annotation.asNormalAnnotationExpr().getPairs()) {

                String name = pair.getNameAsString();
                if (name.equals("value") || name.equals("path")) {
                    extractFromExpression(pair.getValue(), paths);
                }
            }
        }

        return paths;
    }

    private static void extractFromExpression(Expression expr, List<String> out) {

        // "/users"
        if (expr.isStringLiteralExpr()) {
            out.add(expr.asStringLiteralExpr().getValue());
            return;
        }

        // {"/a", "/b"}
        if (expr.isArrayInitializerExpr()) {
            for (Expression e : expr.asArrayInitializerExpr().getValues()) {
                extractFromExpression(e, out);
            }
        }
    }

    private static Optional<AnnotationExpr> findAnnotation(List<AnnotationExpr> annotations, String annotationName) {
        for (AnnotationExpr annotation : annotations) {
            String foundName = annotation.getNameAsString();
            if (foundName.equals(annotationName) || foundName.endsWith("." + annotationName)) {
                return Optional.of(annotation);
            }
        }
        return Optional.empty();
    }

    private static List<String> extractRequestMappingMethods(AnnotationExpr annotation) {
        if (!annotation.isNormalAnnotationExpr()) {
            return List.of();
        }

        List<String> methods = new ArrayList<>();
        for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
            if (!pair.getNameAsString().equals("method")) {
                continue;
            }
            extractRequestMethodsFromExpression(pair.getValue(), methods);
        }

        return methods.stream()
                .map(String::toUpperCase)
                .filter(method -> method.equals("GET")
                        || method.equals("POST")
                        || method.equals("PUT")
                        || method.equals("DELETE")
                        || method.equals("PATCH")
                        || method.equals("HEAD")
                        || method.equals("OPTIONS")
                        || method.equals("TRACE"))
                .distinct()
                .toList();
    }

    private static void extractRequestMethodsFromExpression(Expression expr, List<String> out) {
        if (expr.isArrayInitializerExpr()) {
            for (Expression child : expr.asArrayInitializerExpr().getValues()) {
                extractRequestMethodsFromExpression(child, out);
            }
            return;
        }

        if (expr.isFieldAccessExpr()) {
            out.add(expr.asFieldAccessExpr().getNameAsString());
            return;
        }

        if (expr.isNameExpr()) {
            out.add(expr.asNameExpr().getNameAsString());
        }
    }

    private static List<String> normalizePaths(List<String> paths) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String path : paths) {
            normalized.add(normalizePath(path));
        }
        return List.copyOf(normalized);
    }

    private static List<String> combinePaths(List<String> classPaths, List<String> methodPaths) {
        LinkedHashSet<String> combined = new LinkedHashSet<>();
        for (String classPath : classPaths) {
            for (String methodPath : methodPaths) {
                combined.add(normalizePath(joinPaths(classPath, methodPath)));
            }
        }
        return List.copyOf(combined);
    }

    private static String joinPaths(String left, String right) {
        if (left.equals("/")) {
            return right;
        }
        if (right.equals("/")) {
            return left;
        }
        if (left.endsWith("/") && right.startsWith("/")) {
            return left + right.substring(1);
        }
        if (!left.endsWith("/") && !right.startsWith("/")) {
            return left + "/" + right;
        }
        return left + right;
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String trimmed = path.trim();
        String withLeadingSlash = trimmed.startsWith("/") ? trimmed : "/" + trimmed;
        String collapsed = withLeadingSlash.replaceAll("/{2,}", "/");
        if (collapsed.length() > 1 && collapsed.endsWith("/")) {
            return collapsed.substring(0, collapsed.length() - 1);
        }
        return collapsed;
    }

    private static List<String> httpMethodsFromTags(Set<String> httpTags) {
        return httpTags.stream()
                .map(tag -> tag.startsWith("http.") ? tag.substring("http.".length()) : tag)
                .toList();
    }

    private record ControllerContext(String classFqcn, boolean restController, List<String> classPaths) {}

    private record MethodEntrypointData(Set<String> endpointTags, Set<String> httpTags, List<String> methodPaths) {}
}
