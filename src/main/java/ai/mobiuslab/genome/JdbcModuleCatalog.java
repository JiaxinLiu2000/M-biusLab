package ai.mobiuslab.genome;

import ai.mobiuslab.common.Actor;
import ai.mobiuslab.common.Json;
import ai.mobiuslab.common.MobiusException;
import ai.mobiuslab.common.audit.RevisionLog;
import com.fasterxml.jackson.core.type.TypeReference;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** MySQL-backed {@link ModuleCatalog} with a Caffeine cache for module lookups. */
@Service
public class JdbcModuleCatalog implements ModuleCatalog {

    private static final Logger log = LoggerFactory.getLogger(JdbcModuleCatalog.class);
    private static final String COLS = "module_id, version, kind, name, description, config, origin_genome_id, reuse_count, avg_score";

    private final JdbcClient jdbc;
    private final RevisionLog revisions;
    private final Cache<String, ModuleDef> cache = Caffeine.newBuilder().maximumSize(2000)
            .expireAfterWrite(Duration.ofMinutes(5)).build();

    public JdbcModuleCatalog(JdbcClient jdbc, RevisionLog revisions) {
        this.jdbc = jdbc;
        this.revisions = revisions;
    }

    /** {@inheritDoc} */
    @Override
    public ModuleDef register(ModuleDef def, Actor actor) {
        if (def == null || def.moduleId() == null || def.kind() == null || def.name() == null) {
            throw MobiusException.invalid("module needs moduleId, kind and name");
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            int version = def.version() > 0 ? def.version()
                    : jdbc.sql("SELECT COALESCE(MAX(version), 0) + 1 FROM module_def WHERE module_id = :m")
                    .param("m", def.moduleId()).query(Integer.class).single();
            ModuleDef row = new ModuleDef(def.moduleId(), version, def.kind(), def.name(), def.description(),
                    def.config(), def.originGenomeId(), 0, 0);
            try {
                jdbc.sql("""
                                INSERT INTO module_def (module_id, version, kind, name, description, config, origin_genome_id,
                                                        created_by, updated_by)
                                VALUES (:m, :v, :k, :n, :d, :c, :o, :by, :by)""")
                        .param("m", row.moduleId()).param("v", version).param("k", row.kind().name())
                        .param("n", row.name()).param("d", row.description()).param("c", Json.write(row.config()))
                        .param("o", row.originGenomeId()).param("by", actor.asString()).update();
                revisions.record("module_def", row.moduleId() + "@" + version, "INSERT", actor, "register", null, row);
                log.info("module registered {}@{}", row.moduleId(), version);
                return row;
            } catch (DuplicateKeyException e) {
                if (def.version() > 0) {
                    return get(def.moduleId(), def.version());
                }
            }
        }
        throw new MobiusException(ai.mobiuslab.common.ErrorCode.CONFLICT, "could not allocate version for module " + def.moduleId(), true, null);
    }

    /** {@inheritDoc} */
    @Override
    public ModuleDef get(String moduleId, int version) {
        return cache.get(moduleId + "@" + version, k -> jdbc.sql("SELECT " + COLS + " FROM module_def WHERE module_id = :m AND version = :v AND deleted = 0")
                .param("m", moduleId).param("v", version).query(this::map).optional()
                .orElseThrow(() -> MobiusException.notFound("module", k)));
    }

    /** {@inheritDoc} */
    @Override
    public List<ModuleDef> search(NodeKind kind, String text, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM module_def WHERE deleted = 0");
        if (kind != null) {
            sql.append(" AND kind = :k");
        }
        if (text != null && !text.isBlank()) {
            sql.append(" AND (name LIKE :t OR description LIKE :t OR module_id LIKE :t)");
        }
        sql.append(" ORDER BY reuse_count DESC, module_id, version DESC LIMIT :lim");
        var spec = jdbc.sql(sql.toString()).param("lim", Math.max(1, Math.min(limit, 500)));
        if (kind != null) {
            spec = spec.param("k", kind.name());
        }
        if (text != null && !text.isBlank()) {
            spec = spec.param("t", "%" + text.trim() + "%");
        }
        return spec.query(this::map).list();
    }

    /** {@inheritDoc} */
    @Override
    public void markReused(String moduleId, int version) {
        int n = jdbc.sql("UPDATE module_def SET reuse_count = reuse_count + 1, row_version = row_version + 1, updated_by = 'SYSTEM:reuse' WHERE module_id = :m AND version = :v AND deleted = 0")
                .param("m", moduleId).param("v", version).update();
        if (n == 0) {
            throw MobiusException.notFound("module", moduleId + "@" + version);
        }
        revisions.record("module_def", moduleId + "@" + version, "REUSE", Actor.SYSTEM, "markReused", null, null);
        cache.invalidate(moduleId + "@" + version);
    }

    private ModuleDef map(ResultSet rs, int i) throws SQLException {
        String cfg = rs.getString("config");
        Map<String, Object> config = cfg == null ? Map.of() : Json.read(cfg, new TypeReference<Map<String, Object>>() {
        });
        return new ModuleDef(rs.getString("module_id"), rs.getInt("version"), NodeKind.valueOf(rs.getString("kind")),
                rs.getString("name"), rs.getString("description"), config, rs.getString("origin_genome_id"),
                rs.getLong("reuse_count"), rs.getDouble("avg_score"));
    }
}
