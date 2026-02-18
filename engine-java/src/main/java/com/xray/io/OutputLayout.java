package com.xray.io;

import lombok.Getter;

import java.nio.file.Path;

@Getter
public final class OutputLayout {
    private final Path root;         // .xray/
    private final Path nodesJsonl;    // .xray/nodes.jsonl
    private final Path edgesJsonl;    // .xray/edges.jsonl
    private final Path entrypoints;   // .xray/entrypoints.jsonl
    private final Path flowsDir;      // .xray/flows/
    private final Path indexDir;      // .xray/index/
    private final Path metaJson;      // .xray/meta.json

    public OutputLayout(Path root) {
        this.root = root;
        nodesJsonl = root.resolve("nodes.jsonl");
        edgesJsonl = root.resolve("edges.jsonl");
        entrypoints = root.resolve("entrypoints.jsonl");
        flowsDir = root.resolve("flows.jsonl");
        indexDir = root.resolve("index");
        metaJson = root.resolve("meta.json");
    }
}
