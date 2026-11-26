package io.tracedelta.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class AesGcmEncryptorTest {

    private static final String VALID_KEY =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    @Test
    void encryptThenDecryptRoundTrips() {
        AesGcmEncryptor encryptor = new AesGcmEncryptor(VALID_KEY);
        String ciphertext = encryptor.encrypt("sk-abc123");
        assertThat(encryptor.decrypt(ciphertext)).isEqualTo("sk-abc123");
    }

    @Test
    void sameInputProducesDifferentCiphertext() {
        AesGcmEncryptor encryptor = new AesGcmEncryptor(VALID_KEY);
        String first = encryptor.encrypt("sk-abc123");
        String second = encryptor.encrypt("sk-abc123");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void tamperedCiphertextIsRejected() {
        AesGcmEncryptor encryptor = new AesGcmEncryptor(VALID_KEY);
        String ciphertext = encryptor.encrypt("sk-abc123");
        char last = ciphertext.charAt(ciphertext.length() - 1);
        char replacement = (last == 'A') ? 'B' : 'A';
        String tampered = ciphertext.substring(0, ciphertext.length() - 1) + replacement;

        assertThatThrownBy(() -> encryptor.decrypt(tampered))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidMasterKeys")
    void rejectsMissingOrInvalidMasterKey(String badKey) {
        assertThatThrownBy(() -> new AesGcmEncryptor(badKey))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(ex -> {
                    if (badKey != null && !badKey.isBlank()) {
                        assertThat(ex.getMessage()).doesNotContain(badKey);
                    }
                });
    }

    static Stream<String> invalidMasterKeys() {
        String key16Bytes = Base64.getEncoder().encodeToString("0123456789abcdef".getBytes());
        return Stream.of(null, "", "   ", "not-base64!!", key16Bytes);
    }
}
