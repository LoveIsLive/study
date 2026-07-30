package com.kwang.study.mathvision.workflow.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenEstimatorTest {

    @Test
    void estimatesAsciiAndCjkText() {
        assertTrue(TokenEstimator.estimateTokens("GeoGebra command script") > 0);
        assertTrue(TokenEstimator.estimateTokens("几何教学内容生成") > 0);
    }
}
