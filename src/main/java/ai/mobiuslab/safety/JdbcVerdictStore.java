package ai.mobiuslab.safety;

import ai.mobiuslab.common.Actor;
import ai.mobiuslab.common.Json;
import ai.mobiuslab.common.audit.RevisionLog;
import com.fasterxml.jackson.core.type.TypeReference;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** MySQL-backed {@link VerdictStore} (table {@code safety_verdict}) with a Caffeine cache of recent verdicts. */
@Repository
public class JdbcVerdictStore implements VerdictStore {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final RevisionLog revisions;
    private final Cache<String, SafetyVerdict> cache = Caffeine.newBuilder().maximumSize(10_000).build();

    public JdbcVerdictStore(JdbcClient jdbc, RevisionLog revisions) {
        this.jdbc = jdbc;
        this.revisions = revisions;
    }

    @Override
    public Optional<SafetyVerdict> find(String proposalId) {
        SafetyVerdict hit = cache.getIfPresent(proposalId);
        if (hit != null) {
            return Optional.of(hit);
        }
        Optional<SafetyVerdict> v = jdbc.sql("""
                        SELECT proposal_id, decision, risk_score, rule_hits, reasons, reviewers
                        FROM safety_verdict WHERE proposal_id = :id AND deleted = 0""")
                .param("id", proposalId)
                .query((rs, i) -> map(rs)).optional();
        v.ifPresent(x -> cache.put(proposalId, x));
        return v;
    }

    @Override
    public SafetyVerdict saveIfAbsent(ActionProposal p, SafetyVerdict v) {
        try {
            jdbc.sql("""
                            INSERT INTO safety_verdict (proposal_id, actor, kind, target, decision, risk_score, rule_hits,
                              reasons, reviewers, proposal_json, created_by, updated_by)
                            VALUES (:pid, :actor, :kind, :target, :decision, :risk, :hits, :reasons, :reviewers, :proposal,
                              'SYSTEM:safety', 'SYSTEM:safety')""")
                    .param("pid", p.id())
                    .param("actor", p.actor().asString())
                    .param("kind", p.kind().name())
                    .param("target", p.target() == null ? null : truncate(p.target(), 1000))
                    .param("decision", v.decision().name())
                    .param("risk", v.riskScore())
                    .param("hits", Json.write(v.ruleHits()))
                    .param("reasons", Json.write(v.reasons()))
                    .param("reviewers", Json.write(v.reviewers()))
                    .param("proposal", truncate(Json.write(p), 60_000))
                    .update();
        } catch (DuplicateKeyException e) {
            return find(p.id()).orElse(v);
        }
        revisions.record("safety_verdict", p.id(), "INSERT", new Actor(Actor.Kind.SYSTEM, "safety"),
                "safety review of " + p.kind(), null, v);
        cache.put(p.id(), v);
        return v;
    }

    @Override
    public List<StoredVerdict> page(Long cursor, int limit) {
        return jdbc.sql("""
                        SELECT id, actor, kind, target, created_at, proposal_id, decision, risk_score, rule_hits, reasons, reviewers
                        FROM safety_verdict WHERE deleted = 0 AND id < :cursor ORDER BY id DESC LIMIT :limit""")
                .param("cursor", cursor == null ? Long.MAX_VALUE : cursor)
                .param("limit", limit)
                .query((rs, i) -> new StoredVerdict(rs.getLong("id"), rs.getString("actor"),
                        ActionKind.valueOf(rs.getString("kind")), rs.getString("target"), map(rs),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    private static SafetyVerdict map(ResultSet rs) throws SQLException {
        return new SafetyVerdict(rs.getString("proposal_id"), SafetyVerdict.Decision.valueOf(rs.getString("decision")),
                rs.getDouble("risk_score"), Json.read(rs.getString("rule_hits"), STRINGS),
                Json.read(rs.getString("reasons"), STRINGS), Json.read(rs.getString("reviewers"), STRINGS));
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
