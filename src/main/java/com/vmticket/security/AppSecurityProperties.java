package com.vmticket.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app")
public class AppSecurityProperties {

    private final Security security = new Security();
    private final Auth auth = new Auth();

    public Security getSecurity() {
        return security;
    }

    public Auth getAuth() {
        return auth;
    }

    public static class Security {
        private boolean enabled = true;
        private boolean exposeApiDocs = true;
        private boolean exposeH2Console = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isExposeApiDocs() {
            return exposeApiDocs;
        }

        public void setExposeApiDocs(boolean exposeApiDocs) {
            this.exposeApiDocs = exposeApiDocs;
        }

        public boolean isExposeH2Console() {
            return exposeH2Console;
        }

        public void setExposeH2Console(boolean exposeH2Console) {
            this.exposeH2Console = exposeH2Console;
        }
    }

    public static class Auth {
        private Duration tokenTtl = Duration.ofHours(8);
        private int loginMaxFailures = 5;
        private Duration loginWindow = Duration.ofMinutes(15);

        public Duration getTokenTtl() {
            return tokenTtl;
        }

        public void setTokenTtl(Duration tokenTtl) {
            this.tokenTtl = tokenTtl;
        }

        public int getLoginMaxFailures() {
            return loginMaxFailures;
        }

        public void setLoginMaxFailures(int loginMaxFailures) {
            this.loginMaxFailures = loginMaxFailures;
        }

        public Duration getLoginWindow() {
            return loginWindow;
        }

        public void setLoginWindow(Duration loginWindow) {
            this.loginWindow = loginWindow;
        }
    }
}
