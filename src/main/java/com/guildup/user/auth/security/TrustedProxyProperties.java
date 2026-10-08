package com.guildup.user.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "security.proxy")
public class TrustedProxyProperties {
    public enum ClientIpHeader { X_FORWARDED_FOR, FORWARDED, X_REAL_IP }
    private List<String> trustedProxies = List.of();
    private ClientIpHeader clientIpHeader = ClientIpHeader.X_FORWARDED_FOR;
    public List<String> getTrustedProxies() { return trustedProxies; }
    public void setTrustedProxies(List<String> value) { trustedProxies = value; }
    public ClientIpHeader getClientIpHeader() { return clientIpHeader; }
    public void setClientIpHeader(ClientIpHeader value) { clientIpHeader = value; }
}
