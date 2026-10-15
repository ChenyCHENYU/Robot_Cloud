package com.robot.auth.authn.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

class AuthTokenSecretsTest {
    private static final int SECRET_HEX_LENGTH = 64;
    private static final int HEX_BLOCK_REPEATS = 4;
    private static final int SHORT_SECRET_LENGTH = 63;
    private static final int OVERLONG_SECRET_LENGTH = 65;
    private static final int GENERATED_SECRET_SAMPLE_SIZE = 100;

    @Test
    void secretSyntaxAcceptsOnlyCanonical64LowercaseHexCharacters() {
        assertTrue(AuthTokenSecrets.validSecret("0".repeat(SECRET_HEX_LENGTH)));
        assertTrue(AuthTokenSecrets.validSecret("abcdef0123456789".repeat(HEX_BLOCK_REPEATS)));
        for (String invalid :
                new String[] {
                    null,
                    "",
                    " ",
                    "a".repeat(SHORT_SECRET_LENGTH),
                    "a".repeat(OVERLONG_SECRET_LENGTH),
                    "A".repeat(SECRET_HEX_LENGTH),
                    "g".repeat(SECRET_HEX_LENGTH),
                    "a".repeat(SHORT_SECRET_LENGTH) + "\n",
                    "０".repeat(SECRET_HEX_LENGTH)
                }) {
            assertFalse(AuthTokenSecrets.validSecret(invalid));
        }
    }

    @Test
    void randomSecretsAreCanonicalAndDigestUsesExactInputBytes() {
        var seen = new HashSet<String>();
        for (int i = 0; i < GENERATED_SECRET_SAMPLE_SIZE; i++) {
            String token = AuthTokenSecrets.randomToken();
            assertTrue(AuthTokenSecrets.validSecret(token));
            assertTrue(seen.add(token));
        }
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                AuthTokenSecrets.digest("abc"));
        assertNotEquals(AuthTokenSecrets.digest("abc"), AuthTokenSecrets.digest("abc "));
    }
}
