package com.hkg.dfs.lease;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtentServiceTest {

    @Test
    void openStartsAtZero() {
        ExtentService s = new ExtentService();
        Extent e = s.open("e1");
        assertThat(e.length()).isZero();
        assertThat(e.status()).isEqualTo(ExtentStatus.OPEN);
    }

    @Test
    void openDuplicateFails() {
        ExtentService s = new ExtentService();
        s.open("e1");
        assertThatThrownBy(() -> s.open("e1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void appendGrowsLength() {
        ExtentService s = new ExtentService();
        s.open("e1");
        Extent after = s.append("e1", new byte[]{1, 2, 3});
        assertThat(after.length()).isEqualTo(3);
    }

    @Test
    void sealMakesExtentSealed() {
        ExtentService s = new ExtentService();
        s.open("e1");
        Extent sealed = s.seal("e1");
        assertThat(sealed.status()).isEqualTo(ExtentStatus.SEALED);
    }

    @Test
    void sealedExtentRejectsAppend() {
        ExtentService s = new ExtentService();
        s.open("e1");
        s.seal("e1");
        assertThatThrownBy(() -> s.append("e1", new byte[]{1}))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sealedLengthMatchesAppends() {
        ExtentService s = new ExtentService();
        s.open("e1");
        s.append("e1", new byte[]{1, 2, 3, 4});
        s.append("e1", new byte[]{5});
        s.seal("e1");
        assertThat(s.sealedLength("e1")).isEqualTo(5);
    }

    @Test
    void sealedLengthOnOpenFails() {
        ExtentService s = new ExtentService();
        s.open("e1");
        assertThatThrownBy(() -> s.sealedLength("e1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void appendOnMissingFails() {
        ExtentService s = new ExtentService();
        assertThatThrownBy(() -> s.append("nope", new byte[]{1}))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sealOnMissingFails() {
        ExtentService s = new ExtentService();
        assertThatThrownBy(() -> s.seal("nope")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void extentRecordRejectsBlankId() {
        assertThatThrownBy(() -> new Extent("", ExtentStatus.OPEN, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extentRecordRejectsNegativeLength() {
        assertThatThrownBy(() -> new Extent("e", ExtentStatus.OPEN, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getReturnsCurrentExtent() {
        ExtentService s = new ExtentService();
        s.open("e1");
        s.append("e1", new byte[]{1, 2});
        assertThat(s.get("e1").length()).isEqualTo(2);
    }

    @Test
    void primaryFailureRevokesAndAllowsSealing() {
        ExtentService s = new ExtentService();
        s.open("e1");
        s.append("e1", new byte[]{1, 2, 3});
        // Simulate primary failure: monitor seals the extent so no further appends
        s.seal("e1");
        assertThat(s.sealedLength("e1")).isEqualTo(3);
        assertThatThrownBy(() -> s.append("e1", new byte[]{4}))
                .isInstanceOf(IllegalStateException.class);
    }
}
