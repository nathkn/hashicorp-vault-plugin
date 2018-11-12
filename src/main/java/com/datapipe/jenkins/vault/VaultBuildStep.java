/**
 * The MIT License (MIT)
 * <p>
 * Copyright (c) 2016 Datapipe, Inc.
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.datapipe.jenkins.vault;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import hudson.tasks.BuildWrapperDescriptor;
import com.bettercloud.vault.response.LogicalResponse;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.BodyInvoker;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import groovy.util.logging.Log;

import com.bettercloud.vault.VaultException;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsUnavailableException;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.matchers.IdMatcher;
import com.datapipe.jenkins.vault.configuration.VaultConfigResolver;
import com.datapipe.jenkins.vault.configuration.VaultConfiguration;
import com.datapipe.jenkins.vault.credentials.VaultCredential;
import com.datapipe.jenkins.vault.exception.VaultPluginException;
import com.datapipe.jenkins.vault.log.MaskingConsoleLogFilter;
import com.datapipe.jenkins.vault.model.VaultSecret;
import com.datapipe.jenkins.vault.model.VaultSecretValue;
import com.google.common.annotations.VisibleForTesting;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.FilePath;
import hudson.Launcher;
import hudson.console.ConsoleLogFilter;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import jenkins.tasks.SimpleBuildStep;;

public class VaultBuildStep extends Step {

    private VaultConfiguration configuration;
    private List<VaultSecret> vaultSecrets;
    private List<String> valuesToMask = new ArrayList<>();
    private VaultAccessor vaultAccessor = new VaultAccessor();

    @DataBoundConstructor
    public VaultBuildStep(VaultConfiguration configuration, List<VaultSecret> vaultSecrets) {
        this.configuration = configuration;
        this.vaultSecrets = vaultSecrets;
    }

    @Override
    public final StepExecution start(StepContext context) throws Exception {
        return new ExecutionImpl(this, context);
    }

    public static class ExecutionImpl extends AbstractStepExecutionImpl {
        private static final long serialVersionUID = 1L;

        private transient VaultBuildStep step;

        ExecutionImpl(@Nonnull VaultBuildStep step, StepContext context) {
            super(context);
            this.step = step;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean start()
                throws IOException, InterruptedException {
            Run<?,?> build = getContext().get(Run.class);
            TaskListener listener = getContext().get(TaskListener.class);
            PrintStream logger = listener.getLogger();
            pullAndMergeConfiguration(build);

            if (null != step.vaultSecrets && !step.vaultSecrets.isEmpty()) {
                try {
                    Map<VaultSecret, LogicalResponse> responses = getResponses(build);
                    List<String> leaseIds = retrieveLeaseIds(responses.values());
                    Map<String, String> envVarMap = retriveEnvVarMap(responses);
                    getContext().newBodyInvoker()
                        .withContext(EnvironmentExpander.merge(getContext().get(EnvironmentExpander.class), new Overrider(envVarMap)))
                        .withContext(BodyInvoker.mergeConsoleLogFilters(getContext().get(ConsoleLogFilter.class), this.createLoggerDecorator(build)))
                        .withCallback(new Callback(step.configuration, retrieveVaultCredentials(build), leaseIds))
                        .start();

                } catch (VaultException e) {
                    e.printStackTrace(logger);
                    throw new AbortException(e.getMessage());
                }
            }
            return false;
        }

        private Map<VaultSecret, LogicalResponse> getResponses(Run<?, ?> build) throws VaultException {
            String url = step.configuration.getVaultUrl();
            if (StringUtils.isBlank(url)) {
                throw new VaultPluginException("The vault url was not configured - please specify the vault url to use.");
            }
            step.vaultAccessor.init(url);

            VaultCredential credential = retrieveVaultCredentials(build);
            step.vaultAccessor.auth(credential);

            Map<VaultSecret, LogicalResponse> responseMap = new HashMap<>();

            for (VaultSecret vaultSecret : step.vaultSecrets) {
                LogicalResponse response = step.vaultAccessor.read(vaultSecret.getPath());
                responseMap.put(vaultSecret, response);
            }

            return responseMap;
        }

        private List<String> retrieveLeaseIds(Collection<LogicalResponse> logicalResponses) {
            List<String> leaseIds = new ArrayList<>();
            for (LogicalResponse response : logicalResponses) {
                String leaseId = response.getLeaseId();
                if (leaseId != null && !leaseId.isEmpty()) {
                    leaseIds.add(leaseId);
                }
            }
            return leaseIds;
        }

        private Map<String, String> retriveEnvVarMap(Map<VaultSecret, LogicalResponse> responses) {
            Map<String, String> envVarMap = new HashMap<>();

            for (Map.Entry<VaultSecret, LogicalResponse> response: responses.entrySet()) {
                Map<String, String> values = response.getValue().getData();
                for (VaultSecretValue value : response.getKey().getSecretValues()) {
                    step.valuesToMask.add(values.get(value.getVaultKey()));
                    envVarMap.put(value.getEnvVar(), values.get(value.getVaultKey()));
                }
            }
            return envVarMap;
        }

        private VaultCredential retrieveVaultCredentials(Run<?, ?> build) {
            String id = step.configuration.getVaultCredentialId();
            if (StringUtils.isBlank(id)) {
                throw new VaultPluginException("The credential id was not configured - please specify the credentials to use.");
            }
            List<VaultCredential> credentials = CredentialsProvider.lookupCredentials(VaultCredential.class, build.getParent(), ACL.SYSTEM, Collections.<DomainRequirement>emptyList());
            VaultCredential credential = CredentialsMatchers.firstOrNull(credentials, new IdMatcher(id));

            if (credential == null) {
                throw new CredentialsUnavailableException(id);
            }

            return credential;
        }

        private void pullAndMergeConfiguration(Run<?, ?> build) {
            for (VaultConfigResolver resolver : ExtensionList.lookup(VaultConfigResolver.class)) {
                if (step.configuration != null) {
                    step.configuration = step.configuration.mergeWithParent(resolver.forJob(build.getParent()));
                } else {
                    step.configuration = resolver.forJob(build.getParent());
                }
            }
            if (step.configuration == null) {
                throw new VaultPluginException("No configuration found - please configure the VaultPlugin.");
            }
        }

        public ConsoleLogFilter createLoggerDecorator(
                @Nonnull final Run<?, ?> build) {
            return new MaskingConsoleLogFilter(build.getCharset().name(), step.valuesToMask);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void stop(Throwable cause) throws Exception {
            getContext().onFailure(cause);
        }
    }

    private static final class Overrider extends EnvironmentExpander {

        private static final long serialVersionUID = 1;

        private final Map<String,String> overrides = new HashMap<String,String>();

        Overrider(Map<String,String> overrides) {
            for (Map.Entry<String,String> override : overrides.entrySet()) {
                this.overrides.put(override.getKey(), override.getValue());
            }
        }

        @Override
        public void expand(EnvVars env) throws IOException, InterruptedException {
            for (Map.Entry<String,String> override : overrides.entrySet()) {
                env.override(override.getKey(), override.getValue());
            }
        }
    }

    private static final class Callback extends BodyExecutionCallback.TailCall {

        private static final long serialVersionUID = 1;

        private final VaultConfiguration vaultConfiguration;
        private final VaultCredential vaultCredential;
        private final List<String> leaseIds;

        Callback(VaultConfiguration vaultConfiguration, VaultCredential vaultCredential, List<String> leaseIds) {
            this.vaultConfiguration = vaultConfiguration;
            this.vaultCredential = vaultCredential;
            this.leaseIds = leaseIds;
        }

        @Override
        protected void finished(StepContext context) throws Exception {
            VaultAccessor vaultAccessor = new VaultAccessor();
            vaultAccessor.init(vaultConfiguration.getVaultUrl());
            vaultAccessor.auth(vaultCredential);
            for (String leaseId : leaseIds) {
                if (leaseId != null && !leaseId.isEmpty()) {
                    vaultAccessor.revoke(leaseId);
                }
            }
        }
    }

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {

        @Override public String getFunctionName() {
            return "withVault";
        }

        @Override public String getDisplayName() {
            return "Set Vault Secrets as Environment Variables";
        }

        @Override public boolean takesImplicitBlockArgument() {
            return true;
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(TaskListener.class, Run.class)));
        }
    }
}

