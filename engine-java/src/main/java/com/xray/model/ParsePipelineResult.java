package com.xray.model;

import com.xray.parse.AstIndex;

public record ParsePipelineResult(
        AstIndex astIndex,
        long javaFilesFound,
        long filesParsedOk,
        long filesParsedFailed) {
}
