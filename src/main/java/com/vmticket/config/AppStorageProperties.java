package com.vmticket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppStorageProperties {

    private final Storage storage = new Storage();

    public Storage getStorage() {
        return storage;
    }

    public static class Storage {
        private String location;
        private long maxFileBytes = 10L * 1024 * 1024;

        public String getLocation() {
            return location;
        }

        public void setLocation(String location) {
            this.location = location;
        }

        public long getMaxFileBytes() {
            return maxFileBytes;
        }

        public void setMaxFileBytes(long maxFileBytes) {
            this.maxFileBytes = maxFileBytes;
        }
    }
}
