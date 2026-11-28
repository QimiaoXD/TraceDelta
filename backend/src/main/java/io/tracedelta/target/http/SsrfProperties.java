package io.tracedelta.target.http;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "tracedelta.security.ssrf")
public class SsrfProperties {

    private boolean blockPrivateAddresses = true;
    private List<String> allowedHosts = new ArrayList<>();

    public boolean isBlockPrivateAddresses() {
        return blockPrivateAddresses;
    }

    public void setBlockPrivateAddresses(boolean blockPrivateAddresses) {
        this.blockPrivateAddresses = blockPrivateAddresses;
    }

    public List<String> getAllowedHosts() {
        return allowedHosts;
    }

    public void setAllowedHosts(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts == null ? new ArrayList<>() : new ArrayList<>(allowedHosts);
    }
}
