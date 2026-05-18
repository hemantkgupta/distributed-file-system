package com.hkg.dfs.security;

import com.hkg.dfs.common.ObjectId;
import com.hkg.dfs.common.TenantId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KmsTest {

    @Test
    void generateDukRegistersKey() {
        Kms k = new Kms();
        DukId id = k.generateDuk(TenantId.of("t1"), ObjectId.of("o1"));
        assertThat(k.hasKey(id)).isTrue();
    }

    @Test
    void encryptDecryptRoundTrip() {
        Kms k = new Kms();
        DukId id = k.generateDuk(TenantId.of("t1"), ObjectId.of("o1"));
        byte[] pt = "secret".getBytes();
        byte[] ct = k.encrypt(id, pt);
        assertThat(k.decrypt(id, ct)).isEqualTo(pt);
    }

    @Test
    void destroyedKeyDecryptThrows() {
        Kms k = new Kms();
        DukId id = k.generateDuk(TenantId.of("t1"), ObjectId.of("o1"));
        byte[] ct = k.encrypt(id, "secret".getBytes());
        k.destroyDuk(id);
        assertThatThrownBy(() -> k.decrypt(id, ct))
                .isInstanceOf(KeyDestroyedException.class);
    }

    @Test
    void destroyedKeyEncryptThrows() {
        Kms k = new Kms();
        DukId id = k.generateDuk(TenantId.of("t1"), ObjectId.of("o1"));
        k.destroyDuk(id);
        assertThatThrownBy(() -> k.encrypt(id, new byte[0]))
                .isInstanceOf(KeyDestroyedException.class);
    }

    @Test
    void tenantIsolation() {
        Kms k = new Kms();
        DukId a = k.generateDuk(TenantId.of("tA"), ObjectId.of("o"));
        DukId b = k.generateDuk(TenantId.of("tB"), ObjectId.of("o"));
        byte[] ct = k.encrypt(a, "x".getBytes());
        assertThatThrownBy(() -> k.decrypt(b, ct))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void encryptYieldsDifferentCiphertextsEachCall() {
        Kms k = new Kms();
        DukId id = k.generateDuk(TenantId.of("t"), ObjectId.of("o"));
        byte[] a = k.encrypt(id, "data".getBytes());
        byte[] b = k.encrypt(id, "data".getBytes());
        // GCM IV randomisation
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void cipherTextLongerThanPlaintext() {
        Kms k = new Kms();
        DukId id = k.generateDuk(TenantId.of("t"), ObjectId.of("o"));
        byte[] ct = k.encrypt(id, "x".getBytes());
        assertThat(ct.length).isGreaterThan(1);
    }

    @Test
    void rejectsNullPlaintext() {
        Kms k = new Kms();
        DukId id = k.generateDuk(TenantId.of("t"), ObjectId.of("o"));
        assertThatThrownBy(() -> k.encrypt(id, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hasKeyReturnsFalseAfterDestroy() {
        Kms k = new Kms();
        DukId id = k.generateDuk(TenantId.of("t"), ObjectId.of("o"));
        k.destroyDuk(id);
        assertThat(k.hasKey(id)).isFalse();
    }

    @Test
    void dukIdRejectsNulls() {
        assertThatThrownBy(() -> new DukId(null, ObjectId.of("o")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DukId(TenantId.of("t"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
