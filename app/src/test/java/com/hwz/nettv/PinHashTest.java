package com.hwz.nettv;
import org.junit.Test;
import static org.junit.Assert.*;
public class PinHashTest {
    @Test public void acceptsOnlyFourAsciiDigits() {
        assertTrue(PinHash.valid("0123")); assertFalse(PinHash.valid("12345678"));
        assertFalse(PinHash.valid("123")); assertFalse(PinHash.valid("12345")); assertFalse(PinHash.valid("123456789"));
        assertFalse(PinHash.valid("abcd")); assertFalse(PinHash.valid(" 1234"));
    }
    @Test public void remainsCompatibleWithInstalledPins() throws Exception {
        byte[] salt=new byte[16];
        byte[] expected=javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new javax.crypto.spec.PBEKeySpec("0123".toCharArray(),salt,120000,256)).getEncoded();
        assertArrayEquals(expected,PinHash.derive("0123",salt));
    }
    @Test public void verifiesPinAndRejectsWrongPinAndSalt() throws Exception {
        byte[] salt = new byte[16]; byte[] hash = PinHash.derive("0123",salt);
        assertTrue(PinHash.matches("0123",salt,hash)); assertFalse(PinHash.matches("0124",salt,hash));
        salt[0] = 1; assertFalse(PinHash.matches("0123",salt,hash));
    }
}
