package com.xray.model;


import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Evidence(
        String file,
        int line,
        String kind,     // "annotation", "constructor-param", "method-call", "heuristic", ...
        String snippet,  // short optional snippet
        String detail    // optional detail / resolution info
) {}
