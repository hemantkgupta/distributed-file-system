package com.hkg.dfs.security;

import com.hkg.dfs.common.ObjectId;
import com.hkg.dfs.common.TenantId;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process KMS implementing crypto-shredding. Each (tenant, object)
 * pair gets its own Data-Unique-Key. Destroying the DUK makes any
 * ciphertext encrypted under it permanently undecryptable, the
 * cryptographic equivalent of a "delete-on-tier-zero" guarantee.
 */
public final class Kms {
    private static final int GCM_TAG_BITS = 128;
    private final ConcurrentHashMap<DukId, SecretKey> keys = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public DukId generateDuk(TenantId tenant, ObjectId obj) {
        DukId id = new DukId(tenant, obj);
        try {
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(128);
            keys.put(id, kg.generateKey());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return id;
    }

    public byte[] encrypt(DukId id, byte[] plaintext) {
        if (plaintext == null) throw new IllegalArgumentException("plaintext");
        SecretKey k = keys.get(id);
        if (k == null) throw new KeyDestroyedException(id);
        byte[] iv = new byte[12];
        random.nextBytes(iv);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, k, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] enc = c.doFinal(plaintext);
            byte[] out = new byte[iv.length + enc.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(enc, 0, out, iv.length, enc.length);
            return out;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public byte[] decrypt(DukId id, byte[] ciphertext) {
        SecretKey k = keys.get(id);
        if (k == null) throw new KeyDestroyedException(id);
        if (ciphertext == null || ciphertext.length < 12) {
            throw new IllegalArgumentException("ciphertext");
        }
        byte[] iv = Arrays.copyOfRange(ciphertext, 0, 12);
        byte[] enc = Arrays.copyOfRange(ciphertext, 12, ciphertext.length);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return c.doFinal(enc);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void destroyDuk(DukId id) {
        keys.remove(id);
    }

    public boolean hasKey(DukId id) {
        return keys.containsKey(id);
    }
}
