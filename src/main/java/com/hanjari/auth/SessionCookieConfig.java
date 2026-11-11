package com.hanjari.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * 세션 쿠키 정책(로컬 HTTP 전제): HttpOnly, SameSite=Lax, host-only(Domain 없음), Path=/.
 * 로컬 HTTP에서 쓰므로 Secure를 붙이지 않는다. 외부 공개 전 Secure 등 정책을 다시 검토한다.
 */
@Configuration
public class SessionCookieConfig {

    public static final String COOKIE_NAME = "HANJARI_SESSION";

    @Bean
    public CookieSerializer cookieSerializer() {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName(COOKIE_NAME);
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");
        serializer.setUseSecureCookie(false);
        serializer.setCookiePath("/");
        return serializer;
    }
}
