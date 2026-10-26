package ai.mobiuslab.genome;

import ai.mobiuslab.common.Actor;
import ai.mobiuslab.common.MobiusException;
import ai.mobiuslab.llm.ChatMessage;
import ai.mobiuslab.prompt.PromptLibrary;
import ai.mobiuslab.prompt.PromptSegment;
import ai.mobiuslab.prompt.PromptTemplate;
import ai.mobiuslab.runtime.RuntimeFakes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Review finding #4: unpinned prompt versions are pinned to the latest version at save time. */
class PromptPinningTest {

    private static final PromptLibrary LIB = new PromptLibrary() {
        @Override
        public PromptTemplate latest(String key) {
            if (!key.equals("known")) {
                throw MobiusException.invalid("unknown prompt " + key);
            }
            return new PromptTemplate(key, 7, null, List.of(), List.of(), null, 0, "test", Instant.now());
        }

        @Override
        public PromptTemplate get(String key, int version) {
            return latest(key);
        }

        @Override
        public List<ChatMessage> render(String key, int version, Map<String, Object> variables) {
            return List.of();
        }

        @Override
        public PromptTemplate propose(String key, int baseVersion, List<PromptSegment> segments, Actor actor, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PromptTemplate> history(String key) {
            return List.of();
        }
    };

    private static JdbcGenomeService service() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("prompts", LIB);
        return new JdbcGenomeService(null, null, null, RuntimeFakes.monitor(), e -> {
        }, beans.getBeanProvider(PromptLibrary.class));
    }

    @Test
    void pinsLatestVersionAndLeavesUnknownOrPinnedAlone() {
        Genome g = GenomeTemplates.graph("agt_pin", "r", "m-chat", List.of(
                GenomeTemplates.node("a", NodeKind.LLM, "known", Map.of()),
                GenomeTemplates.node("b", NodeKind.LLM, "missing", Map.of()),
                new NodeGene("c", "builtin.llm", 1, NodeKind.LLM, "known", 2, Map.of()),
                GenomeTemplates.node("d", NodeKind.CODE, null, Map.of())), List.of());
        Genome pinned = service().pinPrompts(g);
        assertEquals(7, pinned.node("a").promptVersion());
        assertNull(pinned.node("b").promptVersion());
        assertEquals(2, pinned.node("c").promptVersion());
        assertNull(pinned.node("d").promptVersion());
        assertNotEquals(g.contentHash(), pinned.contentHash());
    }
}
