package io.tracedelta.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EncryptionConfig {

    @Bean
    public AesGcmEncryptor aesGcmEncryptor(
            @Value("${tracedelta.security.master-key:}") String masterKey) {
        return new AesGcmEncryptor(masterKey);
    }

    @Bean
    public JwtTokenService jwtTokenService(
            @Value("${tracedelta.security.jwt-secret:}") String jwtSecret,
            @Value("${tracedelta.security.jwt-ttl-minutes:720}") long ttlMinutes) {
        return new JwtTokenService(jwtSecret, ttlMinutes);
    }
}
