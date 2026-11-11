package com.hanjari;

import com.hanjari.auth.SecurityConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

// 보안 정책을 명시적으로 import해 WebMvc 슬라이스 테스트에도 운영과 같은 필터 체인이 적용되게 한다.
@SpringBootApplication
@Import(SecurityConfig.class)
public class HanjariApplication {

    public static void main(String[] args) {
        SpringApplication.run(HanjariApplication.class, args);
    }
}
