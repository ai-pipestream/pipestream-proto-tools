package ai.pipestream.proto.cel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CelEvaluatorTest {
    @Test
    void evaluatesBooleanTrueAndFalse() {
        CelEvaluator evaluator = new CelEvaluator();
        assertTrue(evaluator.evaluateBoolean("true", Map.of()));
        assertFalse(evaluator.evaluateBoolean("false", Map.of()));
    }
    @Test void evaluateBooleanFailsSoftly() {
        assertFalse(new CelEvaluator().evaluateBoolean("not valid(", Map.of()));
    }
    @Test void evaluateBooleanOrFailRejectsNonBoolean() {
        assertThrows(CelEvaluationException.class, () -> new CelEvaluator().evaluateBooleanOrFail("'value'", Map.of()));
    }
    @Test void evaluatesArithmeticAndStrings() {
        CelEvaluator evaluator = new CelEvaluator();
        assertEquals(3L, evaluator.evaluateValue("1 + 2", Map.of()));
        assertEquals("ab", evaluator.evaluateValue("'a' + 'b'", Map.of()));
    }
    @Test void warmupCachesExpressionsWithVirtualThreads() {
        CelEvaluator evaluator = new CelEvaluator();
        evaluator.warmup(List.of("1 + 1", "2 + 2", "true"));
        assertEquals(3, evaluator.cacheSize());
    }
    @Test void clearCacheRemovesPrograms() {
        CelEvaluator evaluator = new CelEvaluator();
        evaluator.evaluateValue("1", Map.of()); evaluator.clearCache();
        assertEquals(0, evaluator.cacheSize());
    }
    @Test void blankExpressionThrows() {
        assertThrows(CelEvaluationException.class, () -> new CelEvaluator().evaluateValue(" ", Map.of()));
    }
}
