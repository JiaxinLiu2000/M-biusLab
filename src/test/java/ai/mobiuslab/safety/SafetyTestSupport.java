package ai.mobiuslab.safety;

import ai.mobiuslab.common.Actor;
import ai.mobiuslab.common.ErrorCode;
import ai.mobiuslab.common.MobiusException;
import ai.mobiuslab.common.MobiusProperties;
import ai.mobiuslab.llm.ChatRequest;
import ai.mobiuslab.llm.ChatResult;
import ai.mobiuslab.llm.JevAnswer;
import ai.mobiuslab.llm.JevRequest;
import ai.mobiuslab.llm.JevResult;
import ai.mobiuslab.llm.LlmGateway;
import ai.mobiuslab.llm.TokenUsage;
import ai.mobiuslab.monitor.AsyncMonitorBus;
import ai.mobiuslab.monitor.MonitorBus;
import ai.mobiuslab.monitor.MonitorEvent;
import ai.mobiuslab.monitor.MonitorRepository;
import ai.mobiuslab.safety.rules.RuleSet;
import ai.mobiuslab.safety.rules.RuleSetLoader;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Shared fakes for safety and referee unit tests. */
public final class SafetyTestSupport {

    private SafetyTestSupport() {
    }

    /** MobiusProperties rooted at {@code home}. */
    public static MobiusProperties props(Path home) {
        return new MobiusProperties("test", home.toString(), null, null, null, null,
                new MobiusProperties.Monitor(10_000, 100, Duration.ofMillis(200), 1000), null);
    }

    /** Real monitor bus whose persistence is a no-op. */
    public static MonitorBus monitor(Path home) {
        MonitorRepository noop = new MonitorRepository(new JdbcTemplate()) {
            @Override
            public void insertBatch(List<MonitorEvent> events) {
            }
        };
        return new AsyncMonitorBus(noop, props(home));
    }

    /** Gateway stub: Jev answers from a function, structured chat from another; counts calls. */
    public static final class StubGateway implements LlmGateway {
        public final AtomicInteger decideCalls = new AtomicInteger();
        public final AtomicInteger structuredCalls = new AtomicInteger();
        public volatile Function<JevRequest, JevResult> jev = r -> { throw new MobiusException(ErrorCode.LLM_UNAVAILABLE, "no jev"); };
        public volatile Function<ChatRequest, Object> structured = r -> { throw new MobiusException(ErrorCode.LLM_UNAVAILABLE, "no llm"); };

        @Override
        public ChatResult chat(ChatRequest request) {
            throw new MobiusException(ErrorCode.LLM_UNAVAILABLE, "no chat");
        }

        @Override
        public <T> T chatStructured(ChatRequest request, String jsonSchema, Class<T> type) {
            structuredCalls.incrementAndGet();
            return ai.mobiuslab.common.Json.convert(structured.apply(request), type);
        }

        @Override
        public JevResult decide(JevRequest request) {
            decideCalls.incrementAndGet();
            return jev.apply(request);
        }

        /** Total model calls. */
        public int calls() {
            return decideCalls.get() + structuredCalls.get();
        }
    }

    /** Jev result for the safety screen with the given harmful probability and risk level (0..4). */
    public static JevResult screen(double harmful, double riskLevel, double confidence) {
        return new JevResult("jev-test", Map.of(
                "harmful", new JevAnswer("noul", harmful, null, null, null, confidence),
                "risk", new JevAnswer("score", null, null, riskLevel, null, confidence),
                "category", new JevAnswer("choice", null, "benign", null, null, confidence)),
                TokenUsage.ZERO, 1, 0, "req");
    }

    /** In-memory verdict store. */
    public static final class MemoryVerdictStore implements VerdictStore {
        public final Map<String, SafetyVerdict> map = new ConcurrentHashMap<>();

        @Override
        public Optional<SafetyVerdict> find(String proposalId) {
            return Optional.ofNullable(map.get(proposalId));
        }

        @Override
        public SafetyVerdict saveIfAbsent(ActionProposal proposal, SafetyVerdict verdict) {
            SafetyVerdict prev = map.putIfAbsent(proposal.id(), verdict);
            return prev == null ? verdict : prev;
        }

        @Override
        public List<StoredVerdict> page(Long cursor, int limit) {
            return List.of();
        }
    }

    /** Fixed policy with built-in rules. */
    public static final class StaticPolicy implements SafetyPolicyService {
        public volatile SafetySettings settings = SafetySettings.defaults();
        private final RuleSet rules = RuleSetLoader.loadDefault();

        @Override
        public PolicyDocument policy() {
            return new PolicyDocument(1, "# policy", "sha", Instant.now(), "USER:user");
        }

        @Override
        public PolicyDocument update(String content, Actor actor, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RuleSet rules() {
            return rules;
        }

        @Override
        public SafetySettings settings() {
            return settings;
        }

        @Override
        public SafetySettings updateSettings(SafetySettings patch, Actor actor) {
            settings = settings.merge(patch);
            return settings;
        }
    }
}
