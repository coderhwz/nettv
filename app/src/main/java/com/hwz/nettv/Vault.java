package com.hwz.nettv;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

final class Vault {
    private final SharedPreferences prefs;
    Vault(Context context) { prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE); }
    boolean hasPin() { return prefs.contains("hash"); }
    void setPin(String pin) throws Exception {
        byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
        SharedPreferences.Editor editor = prefs.edit().putString("salt", encode(salt)).putString("hash", encode(PinHash.derive(pin, salt)))
                .remove("attempts").remove("until");
        encrypt(editor, "pin_check", "pin_iv", pinCheck(pin, salt)).commit();
    }
    long remainingLockSeconds() { return Math.max(0, (prefs.getLong("until", 0) - System.currentTimeMillis() + 999) / 1000); }
    boolean unlock(String pin) throws Exception {
        if (remainingLockSeconds() > 0) return false;
        byte[] salt = decode(prefs.getString("salt", ""));
        boolean fast = prefs.contains("pin_check"), correct;
        try { correct = fast && MessageDigest.isEqual(pinCheck(pin, salt), decrypt("pin_check", "pin_iv")); }
        catch (GeneralSecurityException e) { fast = false; correct = false; }
        if (!fast) correct = PinHash.matches(pin, salt, decode(prefs.getString("hash", "")));
        if (correct) {
            SharedPreferences.Editor editor = prefs.edit().remove("attempts").remove("until");
            if (!fast) encrypt(editor, "pin_check", "pin_iv", pinCheck(pin, salt));
            editor.commit();
        }
        else {
            int attempts = prefs.getInt("attempts", 0) + 1;
            prefs.edit().putInt("attempts", attempts % 5)
                    .putLong("until", attempts >= 5 ? System.currentTimeMillis() + 30000 : 0).commit();
        }
        return correct;
    }
    private static byte[] pinCheck(String pin, byte[] salt) throws GeneralSecurityException {
        // This quick verifier is stored only inside Keystore-backed AES-GCM ciphertext.
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); digest.update(salt);
        return digest.digest(pin.getBytes(StandardCharsets.UTF_8));
    }
    private SecretKey key() throws Exception { return key("nettv-server"); }
    static synchronized SecretKey key(String alias) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(alias)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(alias, null);
    }
    void saveServer(JSONObject server) throws Exception {
        encrypt(prefs.edit(), "server", "iv", server.toString().getBytes(StandardCharsets.UTF_8)).commit();
    }
    JSONObject loadServer() throws Exception {
        if (!prefs.contains("server")) return null;
        return new JSONObject(new String(decrypt("server", "iv"), StandardCharsets.UTF_8));
    }
    private SharedPreferences.Editor encrypt(SharedPreferences.Editor editor, String name, String iv, byte[] value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        return editor.putString(iv, encode(cipher.getIV())).putString(name, encode(cipher.doFinal(value)));
    }
    private byte[] decrypt(String name, String iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, decode(prefs.getString(iv, ""))));
        return cipher.doFinal(decode(prefs.getString(name, "")));
    }
    private static String encode(byte[] bytes) { return Base64.encodeToString(bytes, Base64.NO_WRAP); }
    private static byte[] decode(String value) { return Base64.decode(value, Base64.NO_WRAP); }
}
