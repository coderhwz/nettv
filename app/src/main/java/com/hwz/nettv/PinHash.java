package com.hwz.nettv;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.params.KeyParameter;
import java.nio.charset.StandardCharsets;

final class PinHash {
    static boolean valid(String pin) { return pin.matches("[0-9]{4}"); }
    static byte[] derive(String pin, byte[] salt) throws GeneralSecurityException {
        PKCS5S2ParametersGenerator generator = new PKCS5S2ParametersGenerator(new SHA256Digest());
        generator.init(pin.getBytes(StandardCharsets.UTF_8), salt, 120000);
        return ((KeyParameter)generator.generateDerivedParameters(256)).getKey();
    }
    static boolean matches(String pin, byte[] salt, byte[] hash) throws GeneralSecurityException {
        return MessageDigest.isEqual(derive(pin, salt), hash);
    }
}
