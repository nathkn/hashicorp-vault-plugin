package com.datapipe.jenkins.vault.backend;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import com.bettercloud.vault.Vault;
import com.bettercloud.vault.VaultConfig;
import com.bettercloud.vault.VaultException;
import com.bettercloud.vault.api.Logical;
import com.bettercloud.vault.response.LogicalResponse;

import org.junit.Before;
import org.junit.Test;

public class VaultBackendTest {
    private VaultBackend vaultBackend;

    private Vault vault;
    private Logical logical;
    private LogicalResponse logicalResponse;

    private Map<String, String> kv1Secrets = new HashMap<String, String>();
    private Map<String, String> kv2Secrets = new HashMap<String, String>();
    private String fakePath = "path";

    @Before
    public void setup() {
        vault = mock(Vault.class);
        logical = mock(Logical.class);
        logicalResponse = mock(LogicalResponse.class);

        kv1Secrets.put("foo", "bar");
        kv1Secrets.put("zip", "zam");

        kv2Secrets.put("metadata",
            "{"+
                "\"created_time\":\"2019-01-26T22:09:57.843952609Z\","+
                "\"deletion_time\":\"\","+
                "\"destroyed\":false,"+
                "\"version\":1"+
            "}");
        kv2Secrets.put("data", "{\"foo\":\"bar\",\"zip\":\"zam\"}");
    }

    @Test
    public void testGetSecretValuesKV1() throws VaultException {
        when(vault.logical()).thenReturn(logical);
        when(logical.read(fakePath)).thenReturn(logicalResponse);
        when(logicalResponse.getData()).thenReturn(kv1Secrets);

        vaultBackend = new VaultBackend(vault, null, System.out);

        Map<String, String> result = vaultBackend.getSecretValues(fakePath);

        assertTrue(result.equals(kv1Secrets));
    }

    @Test
    public void testGetSecretValuesKV2() throws VaultException {
        when(vault.logical()).thenReturn(logical);
        when(logical.read(fakePath)).thenReturn(logicalResponse);
        when(logicalResponse.getData()).thenReturn(kv2Secrets);

        vaultBackend = new VaultBackend(vault, null, System.out);

        Map<String, String> result = vaultBackend.getSecretValues(fakePath);
        for (Map.Entry<String, String> entry : result.entrySet()) {
            System.out.println(entry.getKey() + ":" + entry.getValue());
        }

        assertTrue(result.equals(kv1Secrets));
    }

    //TODO: Integration tests that mock the factory and pass in a mocked up Vault

}