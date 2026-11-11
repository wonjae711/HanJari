package com.hanjari.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Argon2id 해시. 결과 문자열에 salt·파라미터가 포함된다({@code $argon2id$v=19$m=..,t=..,p=..$salt$hash}).
 * 파라미터와 개발 환경 측정값은 docs/HJ-003-auth-session.md에 기록한다.
 */
@Configuration
public class PasswordConfig {

    public static final int SALT_LENGTH = 16;
    public static final int HASH_LENGTH = 32;
    public static final int PARALLELISM = 1;
    public static final int MEMORY_KIB = 1 << 14;
    public static final int ITERATIONS = 2;

    public static PasswordEncoder argon2id() {
        return new Argon2PasswordEncoder(SALT_LENGTH, HASH_LENGTH, PARALLELISM, MEMORY_KIB, ITERATIONS);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return argon2id();
    }
}
