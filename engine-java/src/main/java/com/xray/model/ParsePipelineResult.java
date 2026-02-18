package com.xray.model;

import com.xray.parse.AstIndex;

import java.util.List;

public record ParsePipelineResult(
        AstIndex astIndex,
        long javaFilesFound,
        long filesParsedOk,
        long filesParsedFailed,
        List<ParseProblem> parseProblems) {
}
