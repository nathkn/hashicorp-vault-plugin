package com.datapipe.jenkins.vault.backend;

import javax.annotation.Nonnull;

public class VaultSecret {

    private final String path;
    private final String key;
    private final String envVar;
    private final String envVarPrefix;
    private final Boolean upcase;

    public VaultSecret(@Nonnull String path, String key, String envVar, String envVarPrefix, Boolean upcase) {
        this.path = path;
        this.key = key;
        this.envVar = envVar;
        this.envVarPrefix = envVarPrefix;
        this.upcase = upcase;
        //TODO: put defaults in constructor
    }

    public String getPath() {
        return this.getPath();
    }

    public String getKey() {
        return this.key;
    }

    public String getEnvVar() {
        return this.envVar;
    }

    public String getEnvVarPrefix() {
        if (this.envVarPrefix == null) {
            return this.path.replaceFirst("/", "")
                            .replaceFirst("/$", "")
                            .replaceAll("/", "_")
                            .concat("_");
        }
        return this.envVarPrefix;
    }

    public Boolean getUpcase() {
        // The default value of upcase is true, unless envVar is set, in which case it is false
        if (this.upcase == null) {
            return this.envVar == null;
        }
        return this.upcase;
    }
}