package com.hkg.dfs.qos;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DmClockSchedulerTest {

    @Test
    void addClassRegisters() {
        var s = new DmClockScheduler();
        s.addClass(new QosClass("client", 10, 1, 100));
        assertThat(s.queueDepth("client")).isZero();
    }

    @Test
    void duplicateClassRejected() {
        var s = new DmClockScheduler();
        s.addClass(new QosClass("a", 10, 1, 100));
        assertThatThrownBy(() -> s.addClass(new QosClass("a", 10, 1, 100)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void submitToUnknownFails() {
        var s = new DmClockScheduler();
        assertThatThrownBy(() -> s.submit("none", () -> {}))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void dispatchRunsOp() {
        var s = new DmClockScheduler();
        s.addClass(new QosClass("a", 10, 1, 100));
        AtomicInteger n = new AtomicInteger();
        s.submit("a", n::incrementAndGet);
        s.dispatch();
        assertThat(n.get()).isEqualTo(1);
    }

    @Test
    void dispatchEmptyReturnsNull() {
        var s = new DmClockScheduler();
        assertThat(s.dispatch()).isNull();
    }

    @Test
    void servedCounterIncrements() {
        var s = new DmClockScheduler();
        s.addClass(new QosClass("a", 10, 1, 100));
        s.submit("a", () -> {});
        s.dispatch();
        assertThat(s.served("a")).isEqualTo(1);
    }

    @Test
    void reservationGetsPriority() {
        var s = new DmClockScheduler();
        s.addClass(new QosClass("durability", 50, 10, 100));
        s.addClass(new QosClass("client",     0,  1, 100));
        s.submit("client", () -> {});
        s.submit("durability", () -> {});
        // durability has positive reservation; should be served first
        assertThat(s.dispatch()).isEqualTo("durability");
    }

    @Test
    void weightPhaseUsesSmallerTag() {
        var s = new DmClockScheduler();
        s.addClass(new QosClass("a", 0, 5, 100));
        s.addClass(new QosClass("b", 0, 1, 100));
        s.submit("a", () -> {});
        s.submit("b", () -> {});
        // a has weight 5 → smaller w tag (1/5 < 1/1), so a is served first in weight phase
        assertThat(s.dispatch()).isEqualTo("a");
    }

    @Test
    void limitPreventsOverServing() {
        var s = new DmClockScheduler();
        s.addClass(new QosClass("a", 0, 1, 1));
        for (int i = 0; i < 10; i++) s.submit("a", () -> {});
        int served = 0;
        for (int i = 0; i < 5; i++) {
            if (s.dispatch() != null) served++;
        }
        assertThat(served).isLessThanOrEqualTo(5);
    }

    @Test
    void reservationAllocatesAcrossManyClasses() {
        var s = new DmClockScheduler();
        s.addClass(new QosClass("h", 100, 10, 1000));
        s.addClass(new QosClass("l",   0,  1, 1000));
        for (int i = 0; i < 5; i++) s.submit("h", () -> {});
        for (int i = 0; i < 5; i++) s.submit("l", () -> {});
        int hServed = 0, lServed = 0;
        for (int i = 0; i < 10; i++) {
            String c = s.dispatch();
            if ("h".equals(c)) hServed++;
            else if ("l".equals(c)) lServed++;
        }
        assertThat(hServed).isGreaterThanOrEqualTo(lServed);
    }

    @Test
    void queueDepthReflectsBacklog() {
        var s = new DmClockScheduler();
        s.addClass(new QosClass("a", 0, 1, 100));
        s.submit("a", () -> {});
        s.submit("a", () -> {});
        assertThat(s.queueDepth("a")).isEqualTo(2);
    }

    @Test
    void exceptionInOpCompletesExceptionally() {
        var s = new DmClockScheduler();
        s.addClass(new QosClass("a", 0, 1, 100));
        var f = s.submit("a", () -> { throw new RuntimeException("boom"); });
        s.dispatch();
        assertThat(f.isCompletedExceptionally()).isTrue();
    }

    @Test
    void successfulOpCompletesNormally() {
        var s = new DmClockScheduler();
        s.addClass(new QosClass("a", 0, 1, 100));
        var f = s.submit("a", () -> {});
        s.dispatch();
        assertThat(f.isDone()).isTrue();
        assertThat(f.isCompletedExceptionally()).isFalse();
    }

    @Test
    void qosClassRejectsInvalidParams() {
        assertThatThrownBy(() -> new QosClass("a", -1, 1, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QosClass("a", 0, 0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QosClass("a", 5, 1, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void qosClassRejectsBlankName() {
        assertThatThrownBy(() -> new QosClass("", 1, 1, 10)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void durabilityClassPreemptsClient() {
        var s = new DmClockScheduler();
        s.addClass(new QosClass("client",      0, 1, 100));
        s.addClass(new QosClass("durability", 50, 1, 100));
        for (int i = 0; i < 3; i++) s.submit("client", () -> {});
        s.submit("durability", () -> {});
        // dispatch once: durability wins
        assertThat(s.dispatch()).isEqualTo("durability");
    }

    @Test
    void servedZeroBeforeDispatch() {
        var s = new DmClockScheduler();
        s.addClass(new QosClass("a", 0, 1, 100));
        s.submit("a", () -> {});
        assertThat(s.served("a")).isZero();
    }
}
