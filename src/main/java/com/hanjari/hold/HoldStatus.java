package com.hanjari.hold;

/** 확보 상태. ACTIVE만 진행 중이고 나머지는 종결 상태다(종결 후 다시 ACTIVE가 되지 않는다). */
public enum HoldStatus {
    ACTIVE,
    RELEASED,
    EXPIRED,
    CONSUMED;

    public boolean terminal() {
        return this != ACTIVE;
    }
}
