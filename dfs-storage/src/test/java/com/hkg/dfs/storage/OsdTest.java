package com.hkg.dfs.storage;

import com.hkg.dfs.common.ObjectId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OsdTest {

    @Test
    void writeLargeThenReadRoundTrips() {
        Osd osd = new Osd();
        byte[] payload = "hello".getBytes();
        osd.writeLarge("e1", 0, payload);
        assertThat(osd.read("e1", 0, payload.length)).isEqualTo(payload);
    }

    @Test
    void readUnknownThrows() {
        Osd osd = new Osd();
        assertThatThrownBy(() -> osd.read("x", 0, 1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void writeLargeIsDefensive() {
        Osd osd = new Osd();
        byte[] payload = {1, 2, 3};
        osd.writeLarge("e1", 0, payload);
        payload[0] = 99;
        assertThat(osd.read("e1", 0, 3)).containsExactly(1, 2, 3);
    }

    @Test
    void writeSmallQueuesToWal() {
        Osd osd = new Osd();
        osd.writeSmall(ObjectId.of("o1"), new byte[]{1});
        assertThat(osd.walSize()).isEqualTo(1);
    }

    @Test
    void writeSmallTooLargeRejected() {
        Osd osd = new Osd();
        assertThatThrownBy(() -> osd.writeSmall(ObjectId.of("o"), new byte[8192]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void flushDeferredDrainsWal() {
        Osd osd = new Osd();
        osd.writeSmall(ObjectId.of("o1"), new byte[]{1, 2});
        osd.writeSmall(ObjectId.of("o2"), new byte[]{3, 4, 5});
        var flushed = osd.flushDeferred("e1", 0);
        assertThat(flushed.size()).isEqualTo(2);
        assertThat(osd.walSize()).isZero();
    }

    @Test
    void flushDeferredMakesDataReadable() {
        Osd osd = new Osd();
        osd.writeSmall(ObjectId.of("o1"), new byte[]{1, 2});
        osd.flushDeferred("e1", 0);
        assertThat(osd.read("e1", 0, 2)).containsExactly(1, 2);
    }

    @Test
    void crcMismatchDetected() {
        Osd osd = new Osd();
        osd.writeLarge("e1", 0, new byte[]{1, 2, 3});
        osd.corruptForTest("e1", 0);
        assertThatThrownBy(() -> osd.read("e1", 0, 3))
                .isInstanceOf(ChecksumMismatchException.class);
    }

    @Test
    void writeLargeCanOverwriteSameOffset() {
        Osd osd = new Osd();
        osd.writeLarge("e1", 0, new byte[]{1});
        osd.writeLarge("e1", 0, new byte[]{9});
        assertThat(osd.read("e1", 0, 1)).containsExactly(9);
    }

    @Test
    void differentOffsetsAreIndependent() {
        Osd osd = new Osd();
        osd.writeLarge("e1", 0, new byte[]{1});
        osd.writeLarge("e1", 128, new byte[]{2});
        assertThat(osd.read("e1", 0, 1)).containsExactly(1);
        assertThat(osd.read("e1", 128, 1)).containsExactly(2);
    }

    @Test
    void differentExtentsAreIndependent() {
        Osd osd = new Osd();
        osd.writeLarge("e1", 0, new byte[]{1});
        osd.writeLarge("e2", 0, new byte[]{2});
        assertThat(osd.read("e1", 0, 1)).containsExactly(1);
        assertThat(osd.read("e2", 0, 1)).containsExactly(2);
    }

    @Test
    void readShorterThanStoredReturnsPrefix() {
        Osd osd = new Osd();
        osd.writeLarge("e1", 0, new byte[]{1, 2, 3, 4});
        assertThat(osd.read("e1", 0, 2)).containsExactly(1, 2);
    }

    @Test
    void writeLargeRejectsNullBytes() {
        Osd osd = new Osd();
        assertThatThrownBy(() -> osd.writeLarge("e", 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writeSmallRejectsNullBytes() {
        Osd osd = new Osd();
        assertThatThrownBy(() -> osd.writeSmall(ObjectId.of("o"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void checksumMismatchExceptionExposesValues() {
        ChecksumMismatchException e = new ChecksumMismatchException("k", 1, 2);
        assertThat(e.expected()).isEqualTo(1);
        assertThat(e.actual()).isEqualTo(2);
    }

    @Test
    void writesAtScale() {
        Osd osd = new Osd();
        for (int i = 0; i < 100; i++) {
            osd.writeLarge("e1", i * 64L, new byte[]{(byte) i});
        }
        for (int i = 0; i < 100; i++) {
            assertThat(osd.read("e1", i * 64L, 1)).containsExactly(i);
        }
    }

    @Test
    void walEmptyInitially() {
        assertThat(new Osd().walSize()).isZero();
    }

    @Test
    void deferredFlushCorrectness() {
        Osd osd = new Osd();
        ObjectId o1 = ObjectId.of("o1");
        ObjectId o2 = ObjectId.of("o2");
        osd.writeSmall(o1, new byte[]{7});
        osd.writeSmall(o2, new byte[]{8});
        var flushed = osd.flushDeferred("ext", 100);
        // assert returned map
        assertThat(flushed.get(o1)).isEqualTo(new Osd.Location("ext", 100, 1));
        assertThat(flushed.get(o2)).isEqualTo(new Osd.Location("ext", 101, 1));
        // assert lookup
        assertThat(osd.lookup(o1)).contains(new Osd.Location("ext", 100, 1));
        assertThat(osd.lookup(o2)).contains(new Osd.Location("ext", 101, 1));
        // first entry at offset 100, second at 101
        assertThat(osd.read("ext", 100, 1)).containsExactly(7);
        assertThat(osd.read("ext", 101, 1)).containsExactly(8);
    }
}
