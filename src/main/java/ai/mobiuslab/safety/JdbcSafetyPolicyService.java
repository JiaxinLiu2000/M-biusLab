package ai.mobiuslab.safety;

import ai.mobiuslab.common.Actor;
import ai.mobiuslab.common.ErrorCode;
import ai.mobiuslab.common.Json;
import ai.mobiuslab.common.MobiusException;
import ai.mobiuslab.common.MobiusProperties;
import ai.mobiuslab.common.audit.RevisionLog;
import ai.mobiuslab.monitor.EventType;
import ai.mobiuslab.monitor.MonitorBus;
import ai.mobiuslab.monitor.MonitorEvent;
import ai.mobiuslab.monitor.SourceKind;
import ai.mobiuslab.safety.rules.RuleSet;
import ai.mobiuslab.safety.rules.RuleSetLoader;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Policy document on disk ({@code ${mobius.home}/safety/SAFETY_POLICY.md}) with every version stored in
 * {@code safety_policy_revision}; settings in {@code safety_setting}. Edits made directly to the file
 * are picked up (hot reload) on the next read and recorded as a new revision.
 */
@Service
public class JdbcSafetyPolicyService implements SafetyPolicyService {

    private static final Logger log = LoggerFactory.getLogger(JdbcSafetyPolicyService.class);
    private static final String DEFAULT_POLICY = "/safety/default-policy.md";
    private static final String SETTINGS_KEY = "settings";

    private final JdbcClient jdbc;
    private final RevisionLog revisions;
    private final MonitorBus monitor;
    private final TransactionTemplate tx;
    private final Path policyFile;
    private final RuleSet baseRules = RuleSetLoader.loadDefault();
    private final ReentrantLock lock = new ReentrantLock();

    private volatile Snapshot snapshot;
    private volatile SafetySettings settings;

    private record Snapshot(PolicyDocument doc, FileTime mtime, RuleSet rules) {
    }

    public JdbcSafetyPolicyService(JdbcClient jdbc, RevisionLog revisions, MonitorBus monitor, TransactionTemplate tx,
                                   MobiusProperties props) {
        this.jdbc = jdbc;
        this.revisions = revisions;
        this.monitor = monitor;
        this.tx = tx;
        this.policyFile = props.homePath().resolve("safety").resolve("SAFETY_POLICY.md");
    }

    /** Copies the default policy to MOBIUS_HOME on first start and loads it. */
    @PostConstruct
    void init() {
        try {
            if (!Files.exists(policyFile)) {
                Files.createDirectories(policyFile.getParent());
                try (InputStream in = getClass().getResourceAsStream(DEFAULT_POLICY)) {
                    if (in == null) {
                        throw new MobiusException(ErrorCode.INTERNAL, "Missing " + DEFAULT_POLICY);
                    }
                    Files.copy(in, policyFile);
                }
                log.info("Safety policy initialised at {}", policyFile);
            }
        } catch (IOException e) {
            throw new MobiusException(ErrorCode.INTERNAL, "Cannot initialise safety policy at " + policyFile, e);
        }
        policy();
    }

    @Override
    public PolicyDocument policy() {
        Snapshot s = snapshot;
        FileTime mtime = mtime();
        if (s != null && s.mtime().equals(mtime)) {
            return s.doc();
        }
        lock.lock();
        try {
            s = snapshot;
            mtime = mtime();
            if (s != null && s.mtime().equals(mtime)) {
                return s.doc();
            }
            String text = Files.readString(policyFile, StandardCharsets.UTF_8);
            String sha = Json.sha256Hex(text);
            PolicyDocument latest = latestRevision().orElse(null);
            PolicyDocument doc;
            if (latest != null && latest.sha256().equals(sha)) {
                doc = latest;
            } else {
                doc = insertRevision(text, sha, new Actor(Actor.Kind.USER, "file"),
                        latest == null ? "initial policy" : "policy file edited on disk", latest);
                if (latest != null) {
                    log.info("Safety policy hot-reloaded from disk (v{})", doc.version());
                }
            }
            RuleSet rules = RuleSetLoader.effective(baseRules, text);
            snapshot = new Snapshot(doc, mtime, rules);
            return doc;
        } catch (IOException e) {
            throw new MobiusException(ErrorCode.INTERNAL, "Cannot read safety policy " + policyFile, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public PolicyDocument update(String content, Actor actor, String reason) {
        if (actor.kind() != Actor.Kind.USER) {
            throw new MobiusException(ErrorCode.FORBIDDEN, "Only the user may change the safety policy (actor " + actor.asString() + ")");
        }
        if (content == null || content.isBlank()) {
            throw MobiusException.invalid("Policy content must not be empty");
        }
        RuleSet rules = RuleSetLoader.effective(baseRules, content); // validates the override block
        lock.lock();
        try {
            PolicyDocument before = policy();
            Path tmp = policyFile.resolveSibling("SAFETY_POLICY.md.tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, policyFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            PolicyDocument doc = insertRevision(content, Json.sha256Hex(content), actor, reason, before);
            snapshot = new Snapshot(doc, mtime(), rules);
            monitor.emit(MonitorEvent.of(SourceKind.SAFETY, "safety.policy", null, EventType.STATE_CHANGE,
                    "Safety policy updated to v" + doc.version() + " by " + actor.asString()));
            log.info("Safety policy updated to v{} by {}", doc.version(), actor.asString());
            return doc;
        } catch (IOException e) {
            throw new MobiusException(ErrorCode.INTERNAL, "Cannot write safety policy " + policyFile, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public RuleSet rules() {
        policy();
        return snapshot.rules();
    }

    @Override
    public SafetySettings settings() {
        SafetySettings s = settings;
        if (s != null) {
            return s;
        }
        s = jdbc.sql("SELECT value_json FROM safety_setting WHERE setting_key = :k AND deleted = 0")
                .param("k", SETTINGS_KEY).query(String.class).optional()
                .map(j -> Json.read(j, SafetySettings.class)).orElse(SafetySettings.defaults());
        settings = s;
        return s;
    }

    @Override
    public SafetySettings updateSettings(SafetySettings patch, Actor actor) {
        if (actor.kind() != Actor.Kind.USER) {
            throw new MobiusException(ErrorCode.FORBIDDEN, "Only the user may change safety settings");
        }
        lock.lock();
        try {
            SafetySettings before = settings();
            SafetySettings after = before.merge(patch);
            tx.executeWithoutResult(st -> {
                jdbc.sql("""
                                INSERT INTO safety_setting (setting_key, value_json, created_by, updated_by)
                                VALUES (:k, :v, :a, :a)
                                ON DUPLICATE KEY UPDATE value_json = VALUES(value_json), updated_by = VALUES(updated_by),
                                  row_version = row_version + 1""")
                        .param("k", SETTINGS_KEY).param("v", Json.write(after)).param("a", actor.asString()).update();
                revisions.record("safety_setting", SETTINGS_KEY, "UPDATE", actor, "settings update", before, after);
            });
            settings = after;
            monitor.emit(MonitorEvent.of(SourceKind.SAFETY, "safety.settings", null, EventType.STATE_CHANGE,
                    "Safety settings updated by " + actor.asString()).withPayload(Json.write(after)));
            return after;
        } finally {
            lock.unlock();
        }
    }

    private PolicyDocument insertRevision(String text, String sha, Actor actor, String reason, PolicyDocument before) {
        return tx.execute(st -> {
            int version = jdbc.sql("SELECT COALESCE(MAX(version), 0) + 1 FROM safety_policy_revision")
                    .query(Integer.class).single();
            jdbc.sql("""
                            INSERT INTO safety_policy_revision (version, content, content_sha, reason, created_by, updated_by)
                            VALUES (:v, :c, :sha, :r, :a, :a)""")
                    .param("v", version).param("c", text).param("sha", sha).param("r", reason)
                    .param("a", actor.asString()).update();
            revisions.record("safety_policy", "SAFETY_POLICY.md", before == null ? "INSERT" : "UPDATE", actor, reason,
                    before == null ? null : Map.of("version", before.version(), "sha256", before.sha256()),
                    Map.of("version", version, "sha256", sha));
            return new PolicyDocument(version, text, sha, Instant.now(), actor.asString());
        });
    }

    private Optional<PolicyDocument> latestRevision() {
        return jdbc.sql("""
                        SELECT version, content, content_sha, updated_at, updated_by FROM safety_policy_revision
                        WHERE deleted = 0 ORDER BY version DESC LIMIT 1""")
                .query((rs, i) -> new PolicyDocument(rs.getInt(1), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant(), rs.getString(5)))
                .optional();
    }

    private FileTime mtime() {
        try {
            return Files.getLastModifiedTime(policyFile);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }
}
