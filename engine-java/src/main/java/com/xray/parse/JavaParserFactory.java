package com.xray.parse;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;

import java.nio.charset.StandardCharsets;

public final class JavaParserFactory {

    public static JavaParser initialize() {
        return new JavaParser(new ParserConfiguration()
                .setCharacterEncoding(StandardCharsets.UTF_8)
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
        );
    }
}
