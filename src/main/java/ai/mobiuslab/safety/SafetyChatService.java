package ai.mobiuslab.safety;

import ai.mobiuslab.common.Actor;
import ai.mobiuslab.common.ErrorCode;
import ai.mobiuslab.common.Ids;
import ai.mobiuslab.common.MobiusException;
import ai.mobiuslab.common.audit.RevisionLog;
import ai.mobiuslab.llm.ChatMessage;
import ai.mobiuslab.llm.ChatRequest;
import ai.mobiuslab.llm.LlmGateway;
import ai.mobiuslab.monitor.EventType;
import ai.mobiuslab.monitor.MonitorBus;
import ai.mobiuslab.monitor.SourceKind;
import ai.mobiuslab.monitor.Span;
import ai.mobiuslab.safety.review.SafetyPrompts;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * User-only conversation with the safety officer. Has its own history table and shares no memory with
 * other agents. Policy changes are only proposed; {@link #applyProposal} applies one after user confirmation.
 */
@Service
public class SafetyChatService {

    private static final String SCHEMA = """
            {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","required":["reply"],
             "properties":{"reply":{"type":"string"},"proposedPolicy":{"type":["string","null"]}}}""";
    private static final int HISTORY = 20;

    private final JdbcClient jdbc;
    private final LlmGateway gateway;
    private final SafetyPolicyService policy;
    private final RevisionLog revisions;
    private final MonitorBus monitor;
    private final SafetyProperties props;

    public SafetyChatService(JdbcClient jdbc, LlmGateway gateway, SafetyPolicyService policy, RevisionLog revisions,
                             MonitorBus monitor, SafetyProperties props) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.policy = policy;
        this.revisions = revisions;
        this.monitor = monitor;
        this.props = props;
    }

    /** One stored chat message; {@code proposalStatus} is NONE, PENDING or APPLIED. */
    public record Message(String id, String role, String content, String proposedPolicy, String proposalStatus,
                          Instant createdAt) {
    }

    /** Structured model answer. */
    record Answer(String reply, String proposedPolicy) {
    }

    /** Sends a user message and returns the officer's reply (with an optional pending policy proposal). */
    public Message send(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            throw MobiusException.invalid("Message must not be empty");
        }
        try (Span span = monitor.start(SourceKind.SAFETY, "safety.chat", null, "safety officer chat")) {
            List<Message> history = recent(HISTORY);
            insert(Ids.newId("scm"), "USER", userMessage, null, "NONE");
            List<ChatMessage> msgs = new ArrayList<>();
            msgs.add(ChatMessage.system(SafetyPrompts.get("chat-system") + "\n\n" + policy.policy().content()));
            for (Message m : history) {
                msgs.add("USER".equals(m.role()) ? ChatMessage.user(m.content()) : ChatMessage.assistant(m.content()));
            }
            msgs.add(ChatMessage.user(userMessage));
            monitor.flow(SourceKind.SAFETY, "user", "safety.chat", null, null, Map.of("chars", userMessage.length()));
            Answer a;
            try {
                a = gateway.chatStructured(new ChatRequest("safety.chat", null, props.reviewModel(), msgs, 4096, 0.2,
                        null, null, null, props.llmTimeout()), SCHEMA, Answer.class);
            } catch (MobiusException e) {
                span.fail(e);
                throw e;
            }
            String proposal = a.proposedPolicy() == null || a.proposedPolicy().isBlank() ? null : a.proposedPolicy();
            Message reply = insert(Ids.newId("scm"), "ASSISTANT", a.reply(), proposal, proposal == null ? "NONE" : "PENDING");
            if (proposal != null) {
                span.event(EventType.USER_ESCALATION, reply.id(), "Safety policy change proposed; awaiting user confirmation", null);
            }
            span.ok(proposal == null ? "reply" : "reply with policy proposal");
            return reply;
        }
    }

    /** Applies a pending policy proposal from an assistant message (user confirmation endpoint). */
    public SafetyPolicyService.PolicyDocument applyProposal(String messageId, Actor actor) {
        Message m = jdbc.sql("""
                        SELECT id, role, content, proposed_policy, proposal_status, created_at
                        FROM safety_chat_message WHERE id = :id AND deleted = 0""")
                .param("id", messageId).query(this::map).optional()
                .orElseThrow(() -> MobiusException.notFound("safety chat message", messageId));
        if (!"PENDING".equals(m.proposalStatus()) || m.proposedPolicy() == null) {
            throw new MobiusException(ErrorCode.CONFLICT, "Message " + messageId + " has no pending policy proposal");
        }
        int changed = jdbc.sql("""
                        UPDATE safety_chat_message SET proposal_status = 'APPLIED', updated_by = :a, row_version = row_version + 1
                        WHERE id = :id AND proposal_status = 'PENDING'""")
                .param("a", actor.asString()).param("id", messageId).update();
        if (changed == 0) {
            throw new MobiusException(ErrorCode.CONFLICT, "Proposal " + messageId + " was already applied");
        }
        revisions.record("safety_chat_message", messageId, "UPDATE", actor, "apply policy proposal",
                Map.of("proposalStatus", "PENDING"), Map.of("proposalStatus", "APPLIED"));
        return policy.update(m.proposedPolicy(), actor, "applied safety-chat proposal " + messageId);
    }

    /** Most recent {@code limit} messages, oldest first. */
    public List<Message> recent(int limit) {
        List<Message> list = new ArrayList<>(jdbc.sql("""
                        SELECT id, role, content, proposed_policy, proposal_status, created_at
                        FROM safety_chat_message WHERE deleted = 0 ORDER BY seq DESC LIMIT :limit""")
                .param("limit", limit).query(this::map).list());
        Collections.reverse(list);
        return list;
    }

    private Message insert(String id, String role, String content, String proposal, String status) {
        jdbc.sql("""
                        INSERT INTO safety_chat_message (id, role, content, proposed_policy, proposal_status, created_by, updated_by)
                        VALUES (:id, :role, :content, :proposal, :status, :by, :by)""")
                .param("id", id).param("role", role).param("content", content).param("proposal", proposal)
                .param("status", status).param("by", "USER".equals(role) ? Actor.USER.asString() : "SYSTEM:safety")
                .update();
        Message m = new Message(id, role, content, proposal, status, Instant.now());
        revisions.record("safety_chat_message", id, "INSERT", "USER".equals(role) ? Actor.USER : new Actor(Actor.Kind.SYSTEM, "safety"),
                "safety chat", null, Map.of("role", role, "proposalStatus", status));
        return m;
    }

    private Message map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Message(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getTimestamp(6).toInstant());
    }
}
