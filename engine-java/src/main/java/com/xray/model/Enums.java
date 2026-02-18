package com.xray.model;

public final class Enums {

    private Enums() {}

    public enum NodeKind {
        CLASS,
        METHOD,
        ENTRYPOINT,
        BEAN,
        REPOSITORY,
        PERSISTENCE,
        OUTBOUND,
        CONFIG,
        RESOURCE
    }

    public enum EdgeType {
        CONTAINS,              // class -> method
        DI,                    // bean -> bean dependency
        CALL,                  // method -> method
        ENTRYPOINT_TO_METHOD,  // entrypoint -> method
        PERSISTENCE_HIT,       // method/bean -> persistence node
        OUTBOUND_CALL,         // method/bean -> outbound node
        OVERRIDE,              // method -> overridden method
        IMPLEMENTS             // class -> interface
    }

    public enum Confidence { HIGH, MEDIUM, LOW }

    public enum EntrypointKind { HTTP, KAFKA, RABBIT, SCHEDULED }

    public enum SegmentKind { SEGMENT, NOTE }
}
