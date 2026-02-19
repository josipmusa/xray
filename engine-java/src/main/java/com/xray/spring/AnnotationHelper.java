package com.xray.spring;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class AnnotationHelper {

    private AnnotationHelper() {}

    static Optional<AnnotationExpr> findAnnotation(List<AnnotationExpr> annotations, String annotationName) {
        for (AnnotationExpr annotation : annotations) {
            String foundName = annotation.getNameAsString();
            if (foundName.equals(annotationName) || foundName.endsWith("." + annotationName)) {
                return Optional.of(annotation);
            }
        }
        return Optional.empty();
    }

    static List<String> extractRequestMappingMethods(AnnotationExpr annotation) {
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

    static List<String> extractRequestMappingPaths(AnnotationExpr annotation) {
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

    static void extractFromExpression(Expression expr, List<String> out) {

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


}
