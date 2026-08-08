/*
 * ML-KEM (FIPS 203, formerly Kyber) hybrid key exchange for the Noise protocol.
 *
 * This implements the southernstorm "hybrid DH" contract (DHStateHybrid) so that ML-KEM-768 can
 * be plugged into any of the "hfs" handshake patterns (e.g. Noise_XXhfs_25519+MLKEM768_...).
 *
 * Roles mirror NewHopeDHState:
 *   - "Alice" is the party that sends the first hybrid token (the encapsulation key, ek) and
 *     later decapsulates the responder's ciphertext.
 *   - "Bob" receives the encapsulation key and responds with a ciphertext (ct) while keeping
 *     the shared secret; the FF token mixes that secret into the Noise chaining key.
 *
 * Both ciphertext and encapsulation key travel in the clear (only hashed into the handshake);
 * the shared secret is what enters the key schedule, exactly like the New Hope hybrid.
 */

package com.bitchat.android.noise.southernstorm.protocol;

import java.security.SecureRandom;
import java.util.Arrays;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.SecretWithEncapsulation;
import org.bouncycastle.crypto.generators.MLKEMKeyPairGenerator;
import org.bouncycastle.crypto.kems.MLKEMExtractor;
import org.bouncycastle.crypto.kems.MLKEMGenerator;
import org.bouncycastle.crypto.params.MLKEMKeyGenerationParameters;
import org.bouncycastle.crypto.params.MLKEMParameters;
import org.bouncycastle.crypto.params.MLKEMPrivateKeyParameters;
import org.bouncycastle.crypto.params.MLKEMPublicKeyParameters;

/**
 * Implementation of the ML-KEM-768 post-quantum key encapsulation mechanism for the Noise
 * protocol, using the NIST FIPS 203 final standard via Bouncy Castle.
 */
final class MLKEMDHState implements DHStateHybrid {

    enum KeyType {
        None,
        AlicePrivate,
        AlicePublic,
        BobPrivate,
        BobPublic,
        BobCalculated
    }

    /** ML-KEM-768 encapsulation key (ek) length in bytes. */
    static final int PUBLIC_KEY_LENGTH = 1184;

    /** ML-KEM-768 ciphertext (ct) length in bytes. */
    static final int CIPHERTEXT_LENGTH = 1088;

    /** ML-KEM shared secret length in bytes. */
    static final int SECRET_LENGTH = 32;

    /** ML-KEM-768 expanded decapsulation key (dk) length in bytes. */
    private static final int PRIVATE_KEY_LENGTH = 1632;

    private static final MLKEMParameters PARAMS = MLKEMParameters.ml_kem_768;

    private byte[] publicKey;
    private byte[] privateKey;
    private KeyType keyType;

    /**
     * Constructs a new key exchange object for ML-KEM-768.
     */
    public MLKEMDHState() {
        publicKey = null;
        privateKey = null;
        keyType = KeyType.None;
    }

    private boolean isAlice() {
        return keyType == KeyType.AlicePrivate || keyType == KeyType.AlicePublic;
    }

    @Override
    public void destroy() {
        clearKey();
    }

    @Override
    public String getDHName() {
        return "MLKEM768";
    }

    @Override
    public int getPublicKeyLength() {
        if (isAlice())
            return PUBLIC_KEY_LENGTH;
        else
            return CIPHERTEXT_LENGTH;
    }

    @Override
    public int getPrivateKeyLength() {
        // Alice stores the full decapsulation key; Bob stores the shared secret that was
        // computed when the ciphertext was generated.
        if (isAlice())
            return PRIVATE_KEY_LENGTH;
        else
            return SECRET_LENGTH;
    }

    @Override
    public int getSharedKeyLength() {
        return SECRET_LENGTH;
    }

    @Override
    public void generateKeyPair() {
        clearKey();
        keyType = KeyType.AlicePrivate;
        MLKEMKeyPairGenerator keyGen = new MLKEMKeyPairGenerator();
        keyGen.init(new MLKEMKeyGenerationParameters(new SecureRandom(), PARAMS));
        AsymmetricCipherKeyPair kp = keyGen.generateKeyPair();
        publicKey = ((MLKEMPublicKeyParameters) kp.getPublic()).getEncoded();
        // Force the expanded decapsulation key (dk) encoding so getPrivateKeyLength() is always
        // truthful (1632 bytes for ML-KEM-768) regardless of BC's preferred default format.
        privateKey = ((MLKEMPrivateKeyParameters) kp.getPrivate())
            .getParametersWithFormat(MLKEMPrivateKeyParameters.EXPANDED_KEY)
            .getEncoded();
    }

    @Override
    public void generateKeyPair(DHState remote) {
        if (remote == null) {
            // No remote public key, so always generate in Alice mode.
            generateKeyPair();
            return;
        } else if (!(remote instanceof MLKEMDHState)) {
            throw new IllegalStateException("Mismatched DH objects");
        }
        MLKEMDHState r = (MLKEMDHState) remote;
        if (r.isAlice() && r.publicKey != null) {
            // We have the remote Alice encapsulation key, so generate in Bob mode: encapsulate
            // and remember both the ciphertext (to send) and the shared secret (to mix).
            clearKey();
            keyType = KeyType.BobCalculated;
            MLKEMPublicKeyParameters ek = new MLKEMPublicKeyParameters(PARAMS, r.publicKey);
            SecretWithEncapsulation enc =
                new MLKEMGenerator(new SecureRandom()).generateEncapsulated(ek);
            try {
                publicKey = enc.getEncapsulation().clone();
                privateKey = enc.getSecret().clone();
            } finally {
                try {
                    enc.destroy();
                } catch (Exception ignored) {
                    // The secrets were cloned above; nothing else to wipe.
                }
            }
        } else {
            generateKeyPair();
        }
    }

    @Override
    public void getPublicKey(byte[] key, int offset) {
        if (publicKey != null)
            System.arraycopy(publicKey, 0, key, offset, getPublicKeyLength());
        else
            Arrays.fill(key, offset, offset + getPublicKeyLength(), (byte) 0);
    }

    @Override
    public void setPublicKey(byte[] key, int offset) {
        if (publicKey != null)
            Noise.destroy(publicKey);
        publicKey = new byte[getPublicKeyLength()];
        System.arraycopy(key, 0, publicKey, 0, publicKey.length);
    }

    @Override
    public void getPrivateKey(byte[] key, int offset) {
        if (privateKey != null)
            System.arraycopy(privateKey, 0, key, offset, getPrivateKeyLength());
        else
            Arrays.fill(key, offset, offset + getPrivateKeyLength(), (byte) 0);
    }

    @Override
    public void setPrivateKey(byte[] key, int offset) {
        clearKey();
        // Guess the key type from the length of the test data.
        if (offset == 0 && key.length == PRIVATE_KEY_LENGTH)
            keyType = KeyType.AlicePrivate;
        else
            keyType = KeyType.BobPrivate;
        privateKey = new byte[getPrivateKeyLength()];
        System.arraycopy(key, 0, privateKey, 0, privateKey.length);
        if (keyType == KeyType.AlicePrivate) {
            // Derive the encapsulation key so this object can be used to write an F token.
            publicKey = new MLKEMPrivateKeyParameters(PARAMS, privateKey)
                .getPublicKeyParameters()
                .getEncoded();
        }
    }

    @Override
    public void setToNullPublicKey() {
        // Null public keys are not supported by ML-KEM.
        // Destroy the current values but otherwise ignore.
        clearKey();
    }

    @Override
    public void clearKey() {
        if (publicKey != null) {
            Noise.destroy(publicKey);
            publicKey = null;
        }
        if (privateKey != null) {
            Noise.destroy(privateKey);
            privateKey = null;
        }
        keyType = KeyType.None;
    }

    @Override
    public boolean hasPublicKey() {
        return publicKey != null;
    }

    @Override
    public boolean hasPrivateKey() {
        return privateKey != null;
    }

    @Override
    public boolean isNullPublicKey() {
        return false;
    }

    @Override
    public void calculate(byte[] sharedKey, int offset, DHState publicDH) {
        if (!(publicDH instanceof MLKEMDHState))
            throw new IllegalArgumentException("Incompatible DH algorithms");
        MLKEMDHState other = (MLKEMDHState) publicDH;
        byte[] secret;
        if (keyType == KeyType.AlicePrivate) {
            // Alice decapsulates the ciphertext that Bob sent as his F token.
            MLKEMPrivateKeyParameters dk = new MLKEMPrivateKeyParameters(PARAMS, privateKey);
            secret = new MLKEMExtractor(dk).extractSecret(other.publicKey);
        } else if (keyType == KeyType.BobCalculated) {
            // The shared secret for Bob was already computed when the key was generated.
            secret = privateKey.clone();
        } else {
            throw new IllegalStateException("Cannot calculate with this DH object");
        }
        try {
            System.arraycopy(secret, 0, sharedKey, offset, SECRET_LENGTH);
        } finally {
            Noise.destroy(secret);
        }
    }

    @Override
    public void copyFrom(DHState other) {
        if (!(other instanceof MLKEMDHState))
            throw new IllegalStateException("Mismatched DH key objects");
        if (other == this)
            return;
        MLKEMDHState dh = (MLKEMDHState) other;
        clearKey();
        switch (dh.keyType) {
            case None:
                break;

            case AlicePrivate:
                if (dh.privateKey != null) {
                    keyType = KeyType.AlicePrivate;
                    privateKey = dh.privateKey.clone();
                    publicKey = new MLKEMPrivateKeyParameters(PARAMS, privateKey)
                        .getPublicKeyParameters()
                        .getEncoded();
                } else {
                    throw new IllegalStateException("Cannot copy generated key for Alice");
                }
                break;

            case AlicePublic:
            case BobPublic:
                keyType = dh.keyType;
                publicKey = dh.publicKey.clone();
                break;

            case BobPrivate:
            case BobCalculated:
                throw new IllegalStateException(
                    "Cannot copy private key for Bob without public key for Alice");
        }
    }

    /**
     * Forces a private key encoding into the expanded (dk) format so length-based
     * key-type detection and getPrivateKeyLength() stay consistent.
     */
    private static byte[] expandedPrivateKey(byte[] encoded) {
        return new MLKEMPrivateKeyParameters(PARAMS, encoded)
            .getParametersWithFormat(MLKEMPrivateKeyParameters.EXPANDED_KEY)
            .getEncoded();
    }

    @Override
    public void copyFrom(DHState other, DHState remote) {
        if (remote == null) {
            copyFrom(other);
            return;
        }
        if (!(other instanceof MLKEMDHState) || !(remote instanceof MLKEMDHState))
            throw new IllegalStateException("Mismatched DH key objects");
        if (other == this)
            return;
        MLKEMDHState dh = (MLKEMDHState) other;
        MLKEMDHState remotedh = (MLKEMDHState) remote;
        clearKey();
        switch (dh.keyType) {
            case None:
                break;

            case AlicePrivate:
                if (dh.privateKey != null) {
                    // Regenerate Alice's encapsulation key from the fixed decapsulation key.
                    keyType = KeyType.AlicePrivate;
                    privateKey = expandedPrivateKey(dh.privateKey);
                    publicKey = new MLKEMPrivateKeyParameters(PARAMS, privateKey)
                        .getPublicKeyParameters()
                        .getEncoded();
                } else {
                    throw new IllegalStateException("Cannot copy generated key for Alice");
                }
                break;

            case BobPrivate:
            case BobCalculated:
                // The fixed-vector Bob path needs a deterministic encapsulation which Bouncy
                // Castle does not expose; it is never used outside the library's own test suite.
                throw new IllegalStateException("Cannot copy generated key for Bob");

            case AlicePublic:
            case BobPublic:
                keyType = dh.keyType;
                publicKey = dh.publicKey.clone();
                break;
        }
    }

    @Override
    public void specifyPeer(DHState local) {
        if (!(local instanceof MLKEMDHState))
            return;
        clearKey();
        if (((MLKEMDHState) local).keyType == KeyType.AlicePrivate)
            keyType = KeyType.BobPublic;
        else
            keyType = KeyType.AlicePublic;
    }
}
