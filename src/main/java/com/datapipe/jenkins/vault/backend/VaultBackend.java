package com.datapipe.jenkins.vault.backend;

import java.io.PrintStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import com.bettercloud.vault.Vault;
import com.bettercloud.vault.VaultConfig;
import com.bettercloud.vault.VaultException;
import com.bettercloud.vault.json.Json;
import com.bettercloud.vault.json.JsonObject;
import com.bettercloud.vault.json.JsonValue;
import com.datapipe.jenkins.vault.credentials.VaultCredential;

import hudson.AbortException;

public class VaultBackend {

    private final String vaultUrl;
    private final VaultCredential vaultCredential;
    private final List<VaultSecret> vaultSecrets;
    private final PrintStream logger;
    private Vault vault;

    public VaultBackend(@Nonnull String vaultUrl, @Nonnull VaultCredential vaultCredential,
                        List<VaultSecret> vaultSecrets, @Nonnull PrintStream logger) throws VaultException {
        this.vaultUrl = vaultUrl;
        this.vaultCredential = vaultCredential;
        this.vaultSecrets = vaultSecrets;
        this.logger = logger;

        this.vault = initVault(vaultUrl, vaultCredential);
    }

    //Vault parameter should be a pre-initialized and authenticated vault instance
    public VaultBackend(@Nonnull Vault vault, List<VaultSecret> vaultSecrets,
                        @Nonnull PrintStream logger)
                        throws VaultException {
        this.vaultUrl = null;
        this.vaultCredential = null;
        this.logger = logger;
        this.vaultSecrets = vaultSecrets;
        this.vault = vault;
    }

    private Vault initVault(String vaultUrl,
                            VaultCredential vaultCredential) throws VaultException {
        VaultConfig config = new VaultConfig().address(vaultUrl).build();
        Vault vault = new Vault(config);
        return vaultCredential.authorizeWithVault(vault, config);
    }

    public Map<String, String> getEnvVarSecretsMap() throws VaultException, AbortException {
        Map<String, String> secretsMap = new HashMap<String, String>();

        for (VaultSecret vaultSecret : this.vaultSecrets) {
            String path = vaultSecret.getPath();
            String key = vaultSecret.getKey();
            String envVar = vaultSecret.getEnvVar();
            String envVarPrefix = vaultSecret.getEnvVar();
            Boolean upcase = vaultSecret.getUpcase();

            Map<String, String> data = getSecretValues(path);
            if (envVar != null) {
                if (key != null) {
                    if (data.containsKey(key)) {
                        secretsMap.put(upcase ? envVar.toUpperCase() : envVar, data.get(key));
                        continue;
                    } else {
                        throw new AbortException(String.format("Key `%s` not found at path `%s`",
                                                                key, path));
                    }
                } else {
                    // If envVar is provided and there is only one key-value pair at that prefix,
                    // we assign the key-value pair to the specified envVar
                    if (data.size() == 1) {
                        secretsMap.put(upcase ? envVar.toUpperCase() : envVar,
                                       data.values().iterator().next());
                        continue;
                    }
                    this.logger.println("[WARN] envVar was provided without a key, "+
                        "however more than one key-value pair was found in Vault. "+
                        "envVar will be ignored in favor of the default prefix.");
                }
            }
            //TODO: Case where key is present, but envVar is not
            for (Map.Entry<String, String> entry : data.entrySet()) {
                String envVarKey = envVarPrefix.concat(entry.getKey());
                secretsMap.put(upcase ? envVarKey.toUpperCase() : envVarKey, entry.getValue());
            }
        }

        return secretsMap;
    }

    public Map<String, String> getSecretValues(String path) throws VaultException {
        Map<String, String> values = this.vault.logical().read(path).getData();

        // If the data has two fields: `metadata: {}` and `data: {}`,
        // we know we have a versioned secret
        if (values.size() == 2 && values.containsKey("metadata") && values.containsKey("data")) {
            try {
                final JsonValue metadata = Json.parse(values.get("metadata")).asObject();
                final JsonObject data = Json.parse(values.get("data")).asObject();

                Map<String, String> versionedValues = new HashMap<String,String>();
                for (final JsonObject.Member member : data) {
                    final JsonValue jsonValue = member.getValue();
                    if (jsonValue == null || jsonValue.isNull()) {
                        continue;
                    } else if (jsonValue.isString()) {
                        versionedValues.put(member.getName(), jsonValue.asString());
                    } else {
                        versionedValues.put(member.getName(), jsonValue.toString());
                    }
                }
                return versionedValues;
            } catch(Exception e) {
                this.logger.printf("[WARN] Unable to parse versioned secret: %s", e.getMessage());
                return values;
            }
        }

        return values;
    }

    public void cleanUp() throws VaultException {
        this.vault.auth().revokeSelf();
    }
}
