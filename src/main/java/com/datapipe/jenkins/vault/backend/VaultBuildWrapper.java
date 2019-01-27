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
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import com.bettercloud.vault.VaultException;
import com.datapipe.jenkins.vault.log.MaskingConsoleLogFilter;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.console.ConsoleLogFilter;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildWrapperDescriptor;
import jenkins.tasks.SimpleBuildWrapper;

public class VaultBuildWrapper extends SimpleBuildWrapper {
    private List<String> valuesToMask = new ArrayList<String>();

    @DataBoundSetter
    public String vaultUrl;

    @DataBoundSetter
    public String credentialsId;

    @DataBoundSetter
    public List<VaultSecret> vaultSecrets;

    @DataBoundConstructor
    public VaultBuildWrapper() {
    }

    @Override
    public void setUp(Context context, Run<?, ?> build,
                      FilePath workspace,
                      Launcher launcher, TaskListener listener,
                      EnvVars initialEnvironment) throws IOException, InterruptedException {
        PrintStream logger = listener.getLogger();

        VaultBackend vaultBackend = null;
        try {
             vaultBackend = VaultBackendFactory.get(
                this.vaultUrl,
                this.credentialsId,
                this.vaultSecrets,
                logger,
                build);

            Map<String, String> vaultSecretsMap = vaultBackend.getEnvVarSecretsMap();
            for (Map.Entry<String, String> entry : vaultSecretsMap.entrySet()) {
                context.env(entry.getKey(), entry.getValue());
                valuesToMask.add(entry.getValue());
            }

            context.setDisposer(new VaultDisposer(vaultBackend));
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
    }

    @Override
    public ConsoleLogFilter createLoggerDecorator(
            @Nonnull final Run<?, ?> build) {
        return new MaskingConsoleLogFilter(build.getCharset().name(), valuesToMask);
    }


    /**
     * Descriptor for {@link VaultBuildWrapper}. Used as a singleton. The class is marked as public so
     * that it can be accessed from views.
     */
    @Extension
    public static final class DescriptorImpl extends BuildWrapperDescriptor {
        public DescriptorImpl() {
            super(VaultBuildWrapper.class);
            load();
        }

        @Override
        public boolean isApplicable(AbstractProject<?, ?> item) {
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        @Override
        public String getDisplayName() {
            return "Vault Plugin";
        }
    }

    public static class VaultDisposer extends Disposer {

        private static final long serialVersionUID = 1L;
        private VaultBackend vaultBackend;

        public VaultDisposer(VaultBackend vaultBackend) {
            this.vaultBackend = vaultBackend;
        }

        @Override
        public void tearDown(Run<?, ?> build,
                             FilePath workspace,
                             Launcher launcher,
                             TaskListener listener) throws IOException, InterruptedException {
            try {
                vaultBackend.cleanUp();
            } catch (VaultException e) {
                e.printStackTrace(listener.getLogger());
                throw new AbortException("Failed to clean up vault secrets: " + e.getMessage());
            }

            listener.getLogger().println("Vault secrets cleaned up");
        }
}
}
