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
package com.datapipe.jenkins.vault.backend;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;

import com.bettercloud.vault.VaultException;
import com.datapipe.jenkins.vault.log.MaskingConsoleLogFilter;

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

import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.console.ConsoleLogFilter;
import hudson.model.Run;
import hudson.model.TaskListener;;

public class VaultBuildStep extends Step {
    @DataBoundSetter
    public String vaultUrl;

    @DataBoundSetter
    public String credentialsId;

    @DataBoundSetter
    public List<VaultSecret> vaultSecrets;

    @DataBoundConstructor
    public VaultBuildStep() {
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
            PrintStream logger = getContext().get(TaskListener.class).getLogger();

            VaultBackend vaultBackend = null;
            try {
                vaultBackend = VaultBackendFactory.get(
                    step.vaultUrl,
                    step.credentialsId,
                    step.vaultSecrets,
                    getContext());

                Map<String, String> vaultSecretsMap = vaultBackend.getEnvVarSecretsMap();

                EnvironmentExpander envExpander = EnvironmentExpander.merge(
                    getContext().get(EnvironmentExpander.class),
                    new VaultExpander(vaultSecretsMap));

                List<String> valuesToMask = new ArrayList<String>(vaultSecretsMap.values());

                MaskingConsoleLogFilter maskingLogFilter = new MaskingConsoleLogFilter(getContext().get(Run.class).getCharset().name(), valuesToMask);

                getContext().newBodyInvoker()
                    .withContext(envExpander)
                    .withContext(BodyInvoker.mergeConsoleLogFilters(getContext().get(ConsoleLogFilter.class), maskingLogFilter))
                    .withCallback(new Callback(vaultBackend))
                    .start();

            } catch (VaultException e) {
                e.printStackTrace(logger);
                throw new AbortException(e.getMessage());
            } finally {
                if (vaultBackend != null) {
                    try {
                        vaultBackend.cleanUp();
                    } catch(Exception e) {
                        logger.println(e.getMessage());
                    }
                }
            }

            return false;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void stop(Throwable cause) throws Exception {
            getContext().onFailure(cause);
        }
    }

    private static final class VaultExpander extends EnvironmentExpander {

        private static final long serialVersionUID = 1;

        private final Map<String,String> overrides;

        VaultExpander(Map<String,String> overrides) {
            this.overrides = overrides;
        }

        @Override
        public void expand(EnvVars env) throws IOException, InterruptedException {
            env.overrideAll(overrides);
        }
    }

    private static final class Callback extends BodyExecutionCallback.TailCall {
        private static final long serialVersionUID = 1;
        private final VaultBackend vaultBackend;

        Callback(VaultBackend vaultBackend) {
            this.vaultBackend = vaultBackend;
        }

        @Override
        protected void finished(StepContext context) throws Exception {
            try {
                vaultBackend.cleanUp();
            } catch (VaultException e) {
                e.printStackTrace(context.get(TaskListener.class).getLogger());
                throw new AbortException("Failed to clean up vault secrets: " + e.getMessage());
            }

            context.get(TaskListener.class).getLogger().println("Vault secrets cleaned up");
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
        public Set<Class<?>> getRequiredContext() {
            return new HashSet<>();
        }
    }
}