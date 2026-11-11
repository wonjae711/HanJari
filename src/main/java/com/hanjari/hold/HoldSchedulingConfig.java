package com.hanjari.hold;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 만료 작업자({@link HoldExpiryWorker}) 주기 실행을 켠다. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class HoldSchedulingConfig {
}
