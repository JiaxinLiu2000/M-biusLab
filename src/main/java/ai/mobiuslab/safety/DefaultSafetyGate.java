package ai.mobiuslab.safety;

import ai.mobiuslab.common.Actor;
import ai.mobiuslab.common.ErrorCode;
import ai.mobiuslab.common.Json;
import ai.mobiuslab.common.MobiusException;
import ai.mobiuslab.common.MobiusProperties;
import ai.mobiuslab.monitor.EventType;
import ai.mobiuslab.monitor.MonitorBus;
import ai.mobiuslab.monitor.SourceKind;
import ai.mobiuslab.monitor.Span;
import ai.mobiuslab.safety.SafetyVerdict.Decision;
import ai.mobiuslab.safety.review.JevSafetyScreen;
import ai.mobiuslab.safety.review.LlmSafetyReviewer;
import ai.mobiuslab.safety.rules.HardRuleEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * The safety gate: hard rules (DENY/ESCALATE short-circuit), then the Jev screen, then the LLM reviewer
 * for anything not clearly low-risk. Verdicts are idempotent by proposal id, persisted and reported as
 * SAFETY_VERDICT monitor events. Thread-safe; concurrent reviews of one proposal id share one result.
 */
@Service
public class DefaultSafetyGate implements SafetyGate {

    private static final Logger log = LoggerFactory.getLogger(DefaultSafetyGate.class);
    static final String HARD = "hard-rules";
    static final String JEV = "jev-screen";
    static final String LLM = "llm-review";

    private final HardRuleEngine engine = new HardRuleEngine();
    private final SafetyPolicyService policy;
    private final VerdictStore store;
    private final JevSafetyScreen jev;
    private final LlmSafetyReviewer llm;
    private final MonitorBus monitor;
    private final SafetyProperties props;
    private final Path home;
    private final Map<String, CompletableFuture<SafetyVerdict>> inFlight = new ConcurrentHashMap<>();

    public DefaultSafetyGate(SafetyPolicyService policy, VerdictStore store, JevSafetyScreen jev, LlmSafetyReviewer llm,
                             MonitorBus monitor, SafetyProperties props, MobiusProperties mobius) {
        this.policy = policy;
        this.store = store;
        this.jev = jev;
        this.llm = llm;
        this.monitor = monitor;
        this.props = props;
        this.home = mobius.homePath();
    }

    @Override
    public SafetyVerdict review(ActionProposal proposal) {
        Optional<SafetyVerdict> stored = findStored(proposal.id());
        if (stored.isPresent()) {
            log.debug("Returning stored verdict for {}", proposal.id());
            return stored.get();
        }
        CompletableFuture<SafetyVerdict> mine = new CompletableFuture<>();
        CompletableFuture<SafetyVerdict> existing = inFlight.putIfAbsent(proposal.id(), mine);
        if (existing != null) {
            return existing.join();
        }
        try {
            SafetyVerdict v = doReview(proposal);
            mine.complete(v);
            return v;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(proposal.id(), mine);
        }
    }

    @Override
    public List<SafetyVerdict> reviewAll(List<ActionProposal> proposals) {
        if (proposals.isEmpty()) {
            return List.of();
        }
        Semaphore permits = new Semaphore(props.maxConcurrentReviews());
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<SafetyVerdict>> futures = new ArrayList<>(proposals.size());
            for (ActionProposal p : proposals) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    permits.acquireUninterruptibly();
                    try {
                        return review(p);
                    } finally {
                        permits.release();
                    }
                }, exec));
            }
            List<SafetyVerdict> out = new ArrayList<>(futures.size());
            for (CompletableFuture<SafetyVerdict> f : futures) {
                out.add(f.join());
            }
            return out;
        }
    }

    @Override
    public SafetyVerdict require(ActionProposal proposal) {
        SafetyVerdict v = review(proposal);
        return switch (v.decision()) {
            case ALLOW -> v;
            case DENY -> throw new MobiusException(ErrorCode.SAFETY_DENIED,
                    proposal.kind() + " on " + proposal.target() + " denied (" + proposal.id() + "): " + String.join("; ", v.reasons()));
            case ESCALATE -> throw new MobiusException(ErrorCode.NEEDS_USER_DECISION,
                    proposal.kind() + " on " + proposal.target() + " needs a user decision (" + proposal.id() + "): "
                            + String.join("; ", v.reasons()));
        };
    }

    private SafetyVerdict doReview(ActionProposal p) {
        String agentId = p.actor().kind() == Actor.Kind.AGENT ? p.actor().id() : null;
        try (Span span = monitor.start(SourceKind.SAFETY, "safety.gate", agentId, "review " + p.kind() + " " + p.target())) {
            try {
                SafetyVerdict v = decide(p, span);
                SafetyVerdict saved = persist(p, v);
                span.event(EventType.SAFETY_VERDICT, p.id(), saved.decision() + " " + p.kind() + " " + p.target(),
                        Json.write(Map.of("verdict", saved, "kind", p.kind(), "actor", p.actor().asString())));
                if (saved.decision() == Decision.ESCALATE) {
                    span.event(EventType.USER_ESCALATION, p.id(), "User decision needed: " + p.kind() + " " + p.target(), null);
                }
                log.info("Safety {} {} {} by {} risk={} hits={}", saved.decision(), p.kind(), p.target(),
                        p.actor().asString(), String.format(Locale.ROOT, "%.2f", saved.riskScore()), saved.ruleHits());
                span.ok(saved.decision().name());
                return saved;
            } catch (RuntimeException e) {
                span.fail(e);
                throw e;
            }
        }
    }

    private SafetyVerdict decide(ActionProposal p, Span span) {
        SafetySettings settings = policy.settings();
        HardRuleEngine.Outcome hard = engine.evaluate(p, policy.rules(),
                new HardRuleEngine.Options(home, settings.allowNetwork(), settings.requireUserForAgentDelete()));
        if (!hard.passed()) {
            double risk = hard.decision() == Decision.DENY ? 1.0 : 0.5;
            return new SafetyVerdict(p.id(), hard.decision(), risk, hard.ruleHits(), hard.reasons(), List.of(HARD));
        }
        List<String> reviewers = new ArrayList<>(List.of(HARD));
        double estimatedRisk = 0.5;
        String screenNote = null;
        try {
            JevSafetyScreen.Screen s = jev.screen(p);
            reviewers.add(JEV);
            estimatedRisk = Math.max(s.risk(), s.harmful() > props.harmfulMax() ? s.harmful() : 0);
            screenNote = String.format(Locale.ROOT, "harmful=%.2f risk=%.2f confidence=%.2f category=%s",
                    s.harmful(), s.risk(), s.confidence(), s.category());
            span.event(EventType.DECISION, p.id(), "jev screen: " + screenNote, null);
            if (s.harmful() <= props.harmfulMax() && s.risk() < settings.llmReviewThreshold()
                    && s.confidence() >= props.jevConfidenceMin()) {
                return new SafetyVerdict(p.id(), Decision.ALLOW, s.risk(), List.of(),
                        List.of("Low risk per Jev screen (" + screenNote + ")"), reviewers);
            }
        } catch (RuntimeException e) {
            log.warn("Jev safety screen unavailable for {}: {}", p.id(), e.getMessage());
        }
        try {
            LlmSafetyReviewer.Review r = llm.review(p, policy.policy().content(), screenNote);
            reviewers.add(LLM);
            span.event(EventType.DECISION, p.id(), "llm review: " + r.decision() + " risk=" + r.risk(), null);
            return new SafetyVerdict(p.id(), r.decision(), r.risk(), List.of(), r.reasons(), reviewers);
        } catch (RuntimeException e) {
            Decision d = estimatedRisk >= settings.highRiskThreshold() ? Decision.DENY : Decision.ESCALATE;
            log.warn("LLM safety review unavailable for {} ({}); failing closed with {}", p.id(), e.getMessage(), d);
            String code = e instanceof MobiusException se ? se.code().toString() : e.getClass().getSimpleName();
            return new SafetyVerdict(p.id(), d, estimatedRisk, List.of(),
                    List.of("LLM review unavailable (" + code + "); failing closed"
                            + (screenNote == null ? "" : " after Jev screen " + screenNote)), reviewers);
        }
    }

    private Optional<SafetyVerdict> findStored(String proposalId) {
        try {
            return store.find(proposalId);
        } catch (RuntimeException e) {
            log.warn("Verdict lookup failed for {}: {}", proposalId, e.getMessage());
            return Optional.empty();
        }
    }

    private SafetyVerdict persist(ActionProposal p, SafetyVerdict v) {
        try {
            return store.saveIfAbsent(p, v);
        } catch (RuntimeException e) {
            log.error("Failed to persist safety verdict {} ({}); verdict still enforced", p.id(), e.getMessage());
            return v;
        }
    }
}
