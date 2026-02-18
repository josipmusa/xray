package com.xray.parse;

import com.github.javaparser.ast.CompilationUnit;
import com.xray.model.Enums;
import com.xray.model.SourceRange;

import java.nio.file.Path;
import java.util.*;

public final class AstIndex {

    // Raw parsed ASTs (so later phases can walk them without reparsing)
    private final Map<Path, CompilationUnit> fileToCu;

    // Type inventory / resolution helpers
    private final Map<String, String> fqcnToTypeId;       // "com.a.Foo" -> nodeId
    private final Map<String, List<String>> simpleNameToFqcns; // "Foo" -> ["com.a.Foo", "com.b.Foo"]

    // Method inventory / lookup
    private final Map<String, String> methodKeyToMethodId; // "com.a.Foo#bar(java.lang.String):void" -> nodeId

    // Node source lookup (for building nodes later without holding Node objects)
    private final Map<String, NodeDraft> nodeDrafts; // nodeId -> draft data


    public AstIndex() {
        this.fileToCu = new HashMap<>();
        this.fqcnToTypeId = new HashMap<>();
        this.simpleNameToFqcns = new HashMap<>();
        this.methodKeyToMethodId = new HashMap<>();
        this.nodeDrafts = new HashMap<>();
    }

    public Map<Path, CompilationUnit> fileToCu() { return Collections.unmodifiableMap(fileToCu); }
    public Map<String, String> fqcnToTypeId() { return Collections.unmodifiableMap(fqcnToTypeId); }
    public Map<String, List<String>> simpleNameToFqcns() { return Collections.unmodifiableMap(simpleNameToFqcns); }
    public Map<String, String> methodKeyToMethodId() { return Collections.unmodifiableMap(methodKeyToMethodId); }
    public Map<String, NodeDraft> nodeDrafts() { return Collections.unmodifiableMap(nodeDrafts); }

    // Mutators used by ParsePipeline only:
    void putCompilationUnit(Path file, CompilationUnit cu) { fileToCu.put(file, cu); }

    void indexType(String fqcn, String simpleName, String nodeId, NodeDraft draft) {
        fqcnToTypeId.put(fqcn, nodeId);
        simpleNameToFqcns.computeIfAbsent(simpleName, k -> new ArrayList<>()).add(fqcn);
        nodeDrafts.put(nodeId, draft);
    }

    void indexMethod(String methodKey, String nodeId, NodeDraft draft) {
        methodKeyToMethodId.put(methodKey, nodeId);
        nodeDrafts.put(nodeId, draft);
    }

    void updateDraft(String nodeId, NodeDraft updated) {
        nodeDrafts.put(nodeId, updated);
    }


    public record NodeDraft(
            String id,
            Enums.NodeKind kind,
            String name,
            String fqcn,
            String signature,
            String ownerId,
            SourceRange source,
            List<String> annotations,
            List<String> modifiers,
            List<String> tags,
            Map<String, Object> attributes
    ) {}
}
