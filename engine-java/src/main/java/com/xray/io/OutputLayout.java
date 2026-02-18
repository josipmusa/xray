package com.xray.io;

import lombok.Getter;

import java.nio.file.Path;

@Getter
public final class OutputLayout {
    private final Path root;         // .xray/
    private final Path nodes;    // .xray/nodes.jsonl
    private final Path edges;    // .xray/edges.jsonl
    private final Path entrypoints;   // .xray/entrypoints.jsonl
    private final Path flowsDir;      // .xray/flows/
    private final Path indexDir;      // .xray/index/
    private final Path meta;      // .xray/meta.json
    private final Path parseProblems; // .xray/parse_problems.jsonl

    public OutputLayout(Path root) {
        this.root = root;
        nodes = root.resolve("nodes.jsonl");
        edges = root.resolve("edges.jsonl");
        entrypoints = root.resolve("entrypoints.jsonl");
        flowsDir = root.resolve("flows");
        indexDir = root.resolve("index");
        meta = root.resolve("meta.json");
        parseProblems = root.resolve("parse_problems.jsonl");
    }
}
