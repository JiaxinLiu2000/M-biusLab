package ai.mobiuslab.prompt;

import ai.mobiuslab.common.ErrorCode;
import ai.mobiuslab.common.MobiusException;
import ai.mobiuslab.llm.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Rendering order, prefix stability, missing variables and concurrent counting. */
class PromptRenderingTest {

    private static final List<PromptSegment> SEGMENTS = List.of(
            new PromptSegment(ChatMessage.Role.USER, "Task: {{input}}", 9),
            new PromptSegment(ChatMessage.Role.SYSTEM, "You are {{role}}.", 1),
            new PromptSegment(ChatMessage.Role.SYSTEM, "Platform rules.", 0),
            new PromptSegment(ChatMessage.Role.USER, "Context: {{ctx}}", 5));

    @Test
    void volatileSegmentsLastAndSameRoleMerged() {
        List<ChatMessage> m = PromptRenderer.render("k", SEGMENTS, Map.of("input", "A", "role", "planner", "ctx", "C"));
        assertEquals(2, m.size());
        assertEquals(new ChatMessage(ChatMessage.Role.SYSTEM, "Platform rules.\n\nYou are planner."), m.get(0));
        assertEquals(new ChatMessage(ChatMessage.Role.USER, "Context: C\n\nTask: A"), m.get(1));
        assertEquals(List.of("input", "role", "ctx"), PromptRenderer.variables(SEGMENTS));
    }

    @Test
    void differentVariablesShareIdenticalPrefix() {
        List<ChatMessage> a = PromptRenderer.render("k", SEGMENTS, Map.of("input", "first", "role", "r", "ctx", "same"));
        List<ChatMessage> b = PromptRenderer.render("k", SEGMENTS, Map.of("input", "second", "role", "r", "ctx", "same"));
        assertEquals(a.get(0), b.get(0));
        String prefix = "Context: same\n\nTask: ";
        assertEquals(prefix, a.get(1).content().substring(0, prefix.length()));
        assertEquals(prefix, b.get(1).content().substring(0, prefix.length()));
    }

    @Test
    void missingVariableIsValidationError() {
        MobiusException e = assertThrows(MobiusException.class, () -> PromptRenderer.render("k", SEGMENTS, Map.of("input", "x")));
        assertEquals(ErrorCode.VALIDATION, e.code());
    }

    @Test
    void concurrentCountingIsExact() throws InterruptedException {
        InvocationCounter counter = new InvocationCounter();
        AtomicLong flushed = new AtomicLong();
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 32; t++) {
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < 10_000; i++) {
                    counter.increment("k", 1);
                }
            }));
        }
        Thread flusher = Thread.ofPlatform().start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                counter.drain().values().forEach(flushed::addAndGet);
            }
        });
        go.countDown();
        for (Thread t : threads) {
            t.join();
        }
        flusher.interrupt();
        flusher.join();
        counter.drain().values().forEach(flushed::addAndGet);
        assertEquals(320_000, flushed.get());
    }
}
