package de.schildbach.wallet.crypto;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.ByteString;

import org.bitcoinj.crypto.EncryptedData;
import org.bitcoinj.crypto.KeyCrypterException;
import org.bitcoinj.crypto.KeyCrypterScrypt;
import org.bitcoinj.utils.ContextPropagatingThreadFactory;
import org.bitcoinj.wallet.Protos.Wallet.EncryptionType;
import org.bouncycastle.crypto.params.KeyParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Security;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import static de.schildbach.wallet.Constants.KEY_STORE_KEY_REF;
import static de.schildbach.wallet.Constants.KEY_STORE_PROVIDER;
import static de.schildbach.wallet.Constants.KEY_STORE_TRANSFORMATION;

import androidx.annotation.AnyThread;
import androidx.biometric.BiometricPrompt;
import androidx.fragment.app.FragmentActivity;

public class KeyStoreKeyCrypter extends KeyCrypterScrypt {

    private static final Logger log = LoggerFactory.getLogger(KeyStoreKeyCrypter.class);
    private final Context context;
    private BiometricPrompt encryptBiometricPrompt;
    private BiometricPrompt decryptBiometricPrompt;
    private BiometricPrompt.PromptInfo promptInfo;

    // Synchronization lock and results
    private final Object encryptLock = new Object();
    private final Object decryptLock = new Object();
    private EncryptedData encryptedDataResult;
    private byte[] decryptedDataResult;

    private byte[] currentPlainBytes;
    private EncryptedData currentEncryptedData;

    public KeyStoreKeyCrypter(Context context) {
        this.context = context;
        promptInfo = createPromptInfo();
        encryptBiometricPrompt = createBiometricPrompt(encryptLock, true);
        decryptBiometricPrompt = createBiometricPrompt(decryptLock, false);
    }

    /**
     * Generates a key in the android key store
     *
     * @return AesKey
     * @throws KeyCrypterException
     */
    @Override
    public KeyParameter deriveKey(CharSequence unusedPassword) throws KeyCrypterException {
        KeyStore keyStore;
        try {
            log.info("Available KeyStore providers: {}", Arrays.toString(Security.getProviders()));
            keyStore = KeyStore.getInstance(KEY_STORE_PROVIDER);
            keyStore.load(null);
            if (!keyStore.containsAlias(KEY_STORE_KEY_REF)) {
                KeyGenerator keyGenerator;
                try {
                    keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEY_STORE_PROVIDER);
                    KeyGenParameterSpec keyGenParameterSpec;
                    // If else block to indicate a preference to use the embedded Secure Element over other hardware security modules like e.g. the TEE
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)) {
                            keyGenParameterSpec = new KeyGenParameterSpec.Builder(KEY_STORE_KEY_REF,
                                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                                    .setKeySize(256)
                                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                    .setUserAuthenticationRequired(true)
                                    .setInvalidatedByBiometricEnrollment(true)
                                    //.setUserAuthenticationValidityDurationSeconds(5) // in case of multiple actions?
                                    .setIsStrongBoxBacked(true)
                                    .build();
                        } else {
                            keyGenParameterSpec = new KeyGenParameterSpec.Builder(KEY_STORE_KEY_REF,
                                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                                    .setKeySize(256)
                                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                    .setUserAuthenticationRequired(true)
                                    .setInvalidatedByBiometricEnrollment(true)
                                    .setIsStrongBoxBacked(false)
                                    .build();
                        }
                        log.info("Using SE: " + keyGenParameterSpec.isStrongBoxBacked());
                    } else {
                        log.info("Android version 28 or higher is required for KeyStore encryption");
                        throw new KeyCrypterException("Android version 28 or higher is required for KeyStore encryption");
                    }
                    keyGenerator.init(keyGenParameterSpec);
                } catch (NoSuchAlgorithmException | NoSuchProviderException | InvalidAlgorithmParameterException e) {
                    log.info("Exception was " + e.getClass());
                    throw new KeyCrypterException("Exception: ", e);
                }

                keyGenerator.generateKey();
            }
        } catch (NoSuchAlgorithmException | IOException | KeyStoreException | CertificateException e) {
            throw new RuntimeException(e);
        }

        // Unused return
        return new KeyParameter(new byte[BLOCK_LENGTH]);
    }

    /**
     * Decrypt bytes previously encrypted with this class.
     *
     * @param encryptedDataToDecrypt    IV and data to decrypt
     * @param unusedAesKey              Required only by the interface. The key doesn't actually get used.
     * @return                          The decrypted bytes
     * @throws                          KeyCrypterException if bytes could not be decrypted
     */
    @Override
    public byte[] decrypt(EncryptedData encryptedDataToDecrypt, KeyParameter unusedAesKey) throws KeyCrypterException {
        synchronized (decryptLock) {
            this.currentEncryptedData = encryptedDataToDecrypt;  // Set the currentEncryptedData before authentication
            decryptedDataResult = null;  // Reset previous result
            // Start authentication
            Cipher cipher;
            try {
                KeyStore keyStore = KeyStore.getInstance(KEY_STORE_PROVIDER);
                keyStore.load(null);
                SecretKey secretKey = (SecretKey) keyStore.getKey(KEY_STORE_KEY_REF, null);
                cipher = Cipher.getInstance(KEY_STORE_TRANSFORMATION);
                GCMParameterSpec spec = new GCMParameterSpec(128, encryptedDataToDecrypt.initialisationVector);
                cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
                decryptBiometricPrompt.authenticate(promptInfo, new BiometricPrompt.CryptoObject(cipher));
            } catch (Exception e) {
                throw new KeyCrypterException("Failed to initialize decryption", e);
            }

            try {
                decryptLock.wait(); // Wait for authentication to complete
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore the interrupted status
                throw new KeyCrypterException("Decryption interrupted.", e);
            }

            if (decryptedDataResult == null) {
                throw new KeyCrypterException("Decryption failed or was canceled.");
            }
            return decryptedDataResult;
        }
    }


    /**
     * Encrypts data with an AES key stored in the Android key store
     *
     * @param plainBytes    data to be encrypted
     * @param unusedAesKey  Required only by the interface. The key doesn't actually get used.
     * @return              IV and encrypted data in an EncryptedData object
     * @throws              KeyCrypterException if bytes could not be decrypted
     */
    @Override
    public EncryptedData encrypt(byte[] plainBytes, KeyParameter unusedAesKey) throws KeyCrypterException {
        synchronized (encryptLock) {
            this.currentPlainBytes = plainBytes;  // Set the currentPlainBytes before authentication
            encryptedDataResult = null; // Reset previous result
            // Start authentication
            Cipher cipher;
            try {
                KeyStore keyStore = KeyStore.getInstance(KEY_STORE_PROVIDER);
                keyStore.load(null);
                SecretKey secretKey = (SecretKey) keyStore.getKey(KEY_STORE_KEY_REF, null);
                cipher = Cipher.getInstance(KEY_STORE_TRANSFORMATION);
                cipher.init(Cipher.ENCRYPT_MODE, secretKey);
                encryptBiometricPrompt.authenticate(promptInfo, new BiometricPrompt.CryptoObject(cipher));
            } catch (Exception e) {
                throw new KeyCrypterException("Failed to initialize encryption", e);
            }

            try {
                encryptLock.wait(); // Wait for authentication to complete
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore the interrupted status
                throw new KeyCrypterException("Encryption interrupted.", e);
            }

            if (encryptedDataResult == null) {
                throw new KeyCrypterException("Encryption failed or was canceled.");
            }
            return encryptedDataResult;
        }
    }

    /**
     * Return the EncryptionType enum value which denotes the type of encryption/ decryption that this KeyCrypter
     * can understand.
     * For the Bitcoinj KeyCrypter interface only the Enum values of ENCRYPTED_SCRYPT_AES and UNENCRYPTED are supported.
     * As none match fully, we need to make due with what we got. We can take the ENCRYPTED_SCRYPT_AES enum even though
     * we don't use a passphrase based KDF of scrypt, as we are using the secure element of the Android device.
     */

    @Override
    public EncryptionType getUnderstoodEncryptionType() {
        return EncryptionType.ENCRYPTED_SCRYPT_AES;
    }

    private BiometricPrompt.PromptInfo createPromptInfo() {
        BiometricPrompt.PromptInfo.Builder promptInfoBuilder = new BiometricPrompt.PromptInfo.Builder()
                // e.g. "Sign in"
                .setTitle("Test Title")
                // e.g. "Biometric for My App"
                .setSubtitle("Test Subtitle")
                // e.g. "Confirm biometric to continue"
                .setDescription("Test Description")
                .setConfirmationRequired(false)
                .setNegativeButtonText("Test Negative Button");

        BiometricPrompt.PromptInfo promptInfo = promptInfoBuilder.build();
        return promptInfo;
    }

    private BiometricPrompt createBiometricPrompt(final Object lock, boolean isEncrypt) {
        Executor executor = Executors.newSingleThreadExecutor();
        BiometricPrompt.AuthenticationCallback callback = new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationError(int errorCode, CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
                synchronized (lock) {
                    if (isEncrypt) {
                        encryptedDataResult = null;
                    } else {
                        decryptedDataResult = null;
                    }
                    lock.notify(); // Notify that authentication failed
                }
            }

            @Override
            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                try {
                    Cipher cipher = result.getCryptoObject().getCipher();
                    if (isEncrypt) {
                        byte[] encryptedData = cipher.doFinal(currentPlainBytes ); // This should be set outside
                        synchronized (lock) {
                            encryptedDataResult = new EncryptedData(cipher.getIV(), encryptedData);
                            lock.notify(); // Notify that encryption is done
                        }
                    } else {
                        synchronized (lock) {
                            decryptedDataResult = cipher.doFinal(currentEncryptedData.encryptedBytes);
                            lock.notify(); // Notify that decryption is done
                        }
                    }
                } catch (Exception e) {
                    synchronized (lock) {
                        if (isEncrypt) {
                            encryptedDataResult = null;
                        } else {
                            decryptedDataResult = null;
                        }
                        lock.notify(); // Notify that there was an error
                    }
                }
            }
        };

        return new BiometricPrompt((FragmentActivity) context, executor, callback);
    }

}