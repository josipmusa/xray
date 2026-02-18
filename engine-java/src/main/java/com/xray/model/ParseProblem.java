package com.xray.model;

public record ParseProblem(
        String file,
        ProblemSeverity severity,
        String errorMessage,
        Integer line,
        Integer column) {

    public static ParseProblem error(String file, String errorMessage) {
        return new ParseProblem(file, ProblemSeverity.ERROR, errorMessage, null, null);
    }

    public static ParseProblem error(String file, String errorMessage, Integer line, Integer column) {
        return new ParseProblem(file, ProblemSeverity.ERROR, errorMessage, line, column);
    }

    public static ParseProblem warn(String file, String errorMessage, Integer line, Integer column) {
        return new ParseProblem(file, ProblemSeverity.WARN, errorMessage, line, column);
    }

    public enum ProblemSeverity {
        ERROR,
        WARN
    }
}
