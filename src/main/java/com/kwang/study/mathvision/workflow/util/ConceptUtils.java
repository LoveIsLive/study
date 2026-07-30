package com.kwang.study.mathvision.workflow.util;

import java.util.Locale;

public final class ConceptUtils {

    private ConceptUtils() {
    }

    public static String normalizeConcept(String concept) {
        return concept == null ? "" : concept.toLowerCase(Locale.ROOT).trim();
    }
}
