package ai.mobiuslab.genome;

import ai.mobiuslab.common.Actor;
import ai.mobiuslab.common.Ids;
import ai.mobiuslab.common.audit.RevisionLog;
import ai.mobiuslab.runtime.RuntimeFakes;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Requires the local MySQL (localhost:3307/mobiuslab) with migrations applied. */
@Tag("integration")
class GenomeServiceIntegrationTest {

    static DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(
                "jdbc:mysql://localhost:3307/mobiuslab?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "mobiuslab", "mobiuslab");
    }

    private final List<Object> events = new CopyOnWriteArrayList<>();

    private JdbcGenomeService service(JdbcClient jdbc, DriverManagerDataSource ds) {
        return new JdbcGenomeService(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)),
                new RevisionLog(jdbc), RuntimeFakes.monitor(), events::add,
                new org.springframework.beans.factory.support.StaticListableBeanFactory()
                        .getBeanProvider(ai.mobiuslab.prompt.PromptLibrary.class));
    }

    @Test
    void saveDedupesActivateAndRollback() {
        DriverManagerDataSource ds = dataSource();
        JdbcClient jdbc = JdbcClient.create(ds);
        JdbcGenomeService svc = service(jdbc, ds);
        String agentId = Ids.newId("agt");
        jdbc.sql("INSERT INTO agent (id, role, kind, lifecycle, created_by, updated_by) VALUES (:id, 'r', 'SUB', 'ACTIVE', 'SYSTEM:test', 'SYSTEM:test')")
                .param("id", agentId).update();

        Genome draft = GenomeTemplates.singleLlmAgent(agentId, "researcher", "finds things", "p.research", "m1");
        Genome g1 = svc.save(draft, Actor.SYSTEM, "first");
        Genome again = svc.save(draft, Actor.SYSTEM, "same content");
        assertEquals(g1.id(), again.id());
        assertEquals(1, svc.history(agentId).size());

        svc.activate(g1.id(), Actor.SYSTEM, "go");
        assertEquals(g1.id(), svc.active(agentId).id());

        Genome child = new Genome(null, agentId, null, g1.id(), 0, "researcher", "finds more things", g1.models(),
                g1.nodes(), g1.edges(), null, g1.permissions(), null, null, Map.of("temperature", 0.2), null);
        Genome g2 = svc.save(child, Actor.SYSTEM, "mutation");
        assertEquals(1, g2.generation());
        assertEquals(g1.lineageId(), g2.lineageId());
        svc.activate(g2.id(), Actor.SYSTEM, "try child");
        assertEquals(g2.id(), svc.active(agentId).id());
        assertEquals(LifecycleState.RETAINED, svc.get(g1.id()).state());
        assertEquals(g2.id(), jdbc.sql("SELECT active_genome_id FROM agent WHERE id = :id").param("id", agentId)
                .query(String.class).single());

        Genome back = svc.rollback(agentId, Actor.SYSTEM, "worse");
        assertEquals(g1.id(), back.id());
        assertEquals(g1.id(), svc.active(agentId).id());
        assertEquals(LifecycleState.ROLLED_BACK, svc.get(g2.id()).state());
        assertEquals(2, svc.lineage(g1.lineageId()).size());
        assertEquals(3, events.size());

        svc.recordScore(g1.id(), "obj1", 0.5);
        svc.recordScore(g1.id(), "obj1", 1.0);
        @SuppressWarnings("unchecked")
        Map<String, Object> s = (Map<String, Object>) svc.scoreSummary(g1.id()).get("obj1");
        assertEquals(0.75, ((Number) s.get("avg")).doubleValue(), 1e-9);
    }
}
