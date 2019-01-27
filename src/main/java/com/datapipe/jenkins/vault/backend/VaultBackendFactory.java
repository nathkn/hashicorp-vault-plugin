package com.datapipe.jenkins.vault.backend;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;

import com.bettercloud.vault.VaultException;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsUnavailableException;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.matchers.IdMatcher;
import com.datapipe.jenkins.vault.configuration.VaultConfigResolver;
import com.datapipe.jenkins.vault.configuration.VaultConfiguration;
import com.datapipe.jenkins.vault.credentials.VaultCredential;

import org.jenkinsci.plugins.workflow.steps.StepContext;

import hudson.AbortException;
import hudson.ExtensionList;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;

public abstract class VaultBackendFactory {
    public static VaultBackend get(String vaultUrl,
                                   String credentialsId,
                                   List<VaultSecret> vaultSecrets,
                                   @Nonnull PrintStream logger,
                                   @Nonnull Run<?, ?> build) throws AbortException, VaultException
    {
        VaultConfiguration vaultConfiguration = getVaultConfiguration(vaultUrl, credentialsId, build);
        VaultCredential vaultCredential = getVaultCredential(vaultConfiguration.getVaultCredentialId(), build);
        return new VaultBackend(vaultConfiguration.getVaultUrl(),
                                vaultCredential, vaultSecrets, logger);
    }

    public static VaultBackend get(String vaultUrl,
                                   String credentialsId,
                                   List<VaultSecret> vaultSecrets,
                                   StepContext context) throws IOException, InterruptedException, AbortException, VaultException {
        Run<?, ?> run = context.get(Run.class);
        VaultConfiguration vaultConfiguration = getVaultConfiguration(vaultUrl, credentialsId, run);
        VaultCredential vaultCredential = getVaultCredential(vaultConfiguration.getVaultCredentialId(), run);

        PrintStream logger = context.get(TaskListener.class).getLogger();
        return new VaultBackend(vaultConfiguration.getVaultUrl(),
                                vaultCredential, vaultSecrets, logger);
    }

    private static VaultCredential getVaultCredential(String credentialsId, Run<?, ?> build) throws CredentialsUnavailableException {
        List<VaultCredential> credentials = CredentialsProvider.lookupCredentials(VaultCredential.class,
                                                                                  build.getParent(),
                                                                                  ACL.SYSTEM,
                                                                                  Collections.<DomainRequirement>emptyList());
        VaultCredential credential = CredentialsMatchers.firstOrNull(credentials, new IdMatcher(credentialsId));

        if (credential == null) {
            throw new CredentialsUnavailableException(credentialsId);
        }
        return credential;
    }

    private static VaultConfiguration getVaultConfiguration(String vaultUrl, String credentialsId, Run<?, ?> build) throws AbortException {
        VaultConfiguration configuration = new VaultConfiguration(vaultUrl, credentialsId);
        for (VaultConfigResolver resolver : ExtensionList.lookup(VaultConfigResolver.class)) {
            if (configuration.getVaultUrl() != null || configuration.getVaultCredentialId() != null) {
                configuration = configuration.mergeWithParent(resolver.forJob(build.getParent()));
            } else {
                configuration = resolver.forJob(build.getParent());
            }
        }

        if (configuration == null) {
            throw new AbortException("No configuration found - please configure the VaultPlugin.");
        } else if (configuration.getVaultCredentialId() == null) {
            throw new AbortException("No credential ID configured for Vault - please provide the Vault credential ID");
        } else if (configuration.getVaultUrl() == null ) {
            throw new AbortException("No Vault URL configured - please provide the Vault URL");
        }

        return configuration;
    }
}