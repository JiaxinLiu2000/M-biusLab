package ai.mobiuslab.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A typed Jev (TypeSafe System One) question. {@code instructions} and criteria values may be a
 * String or any JSON-serializable object/list.
 */
public sealed interface JevQuestion {

    /** Wire type name: "noul", "choice" or "score". */
    String type();

    /** The question text or structured instructions. */
    Object instructions();

    /** Yes/no question; criteria keys "true"/"false" (optional). Answer: probability of yes. */
    record Noul(Object instructions, Map<String, Object> criteria) implements JevQuestion {
        public String type() {
            return "noul";
        }
    }

    /** Pick one option (max 255); criteria maps option -> description (nullable values). */
    record Choice(Object instructions, LinkedHashMap<String, Object> criteria) implements JevQuestion {
        public String type() {
            return "choice";
        }
    }

    /** Ordered rubric levels (2..10); answer is a probability-weighted level index. */
    record Score(Object instructions, List<Object> criteria) implements JevQuestion {
        public String type() {
            return "score";
        }
    }
}
