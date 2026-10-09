package com.openpilot.c3logo;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 * 完整的 BouncyCastle provider，但**改名**为 "BCFULL"。
 *
 * 背景：Android 系统自带一个残缺的 "BC" provider（AndroidOpenSSL/Conscrypt 暴露的
 * org.bouncycastle 子集，缺少 EC、X25519 等算法）。
 * Security.addProvider 遇到同名 "BC" 会直接跳过，导致 SSHJ 一直用残缺版，
 * 从而报 "no such algorithm: EC for provider BC" / X25519。
 *
 * 这里继承 BouncyCastleProvider 并覆盖 getName() 返回 "BCFULL"，
 * 就能与系统的 "BC" 共存，SSHJ 通过 SecurityUtils.setSecurityProvider("BCFULL")
 * 使用我们这份功能完整的 BC。
 */
public class FullBCProvider extends BouncyCastleProvider {
    public static final String PROVIDER_NAME = "BCFULL";

    @Override
    public String getName() {
        return PROVIDER_NAME;
    }

    @Override
    public String toString() {
        return PROVIDER_NAME + " (full BouncyCastle) v" + getVersionStr();
    }

    private String getVersionStr() {
        try {
            return String.valueOf(getVersion());
        } catch (Throwable t) {
            return "?";
        }
    }
}
