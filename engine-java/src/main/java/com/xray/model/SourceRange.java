package com.xray.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
public record SourceRange(
        String file,
        int startLine,
        int startCol,
        int endLine,
        int endCol
) {}
