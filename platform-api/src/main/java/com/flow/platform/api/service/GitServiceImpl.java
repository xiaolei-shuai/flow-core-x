/*
 * Copyright 2017 flow.ci
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.flow.platform.api.service;

import com.flow.platform.api.config.AppConfig;
import com.flow.platform.api.domain.node.Flow;
import com.flow.platform.api.domain.node.Node;
import com.flow.platform.api.domain.envs.GitEnvs;
import com.flow.platform.api.git.GitClientBuilder;
import com.flow.platform.api.git.GitSshClientBuilder;
import com.flow.platform.api.util.NodeUtil;
import com.flow.platform.core.exception.IllegalStatusException;
import com.flow.platform.core.exception.UnsupportedException;
import com.flow.platform.util.Logger;
import com.flow.platform.util.git.GitClient;
import com.flow.platform.util.git.GitException;
import com.flow.platform.util.git.model.GitSource;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.PostConstruct;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * @author yang
 */
@Service
public class GitServiceImpl implements GitService {

    private final static Logger LOGGER = new Logger(GitService.class);

    private final Map<GitSource, Class<? extends GitClientBuilder>> clientBuilderType = new HashMap<>(6);

    @Autowired
    private Path workspace;

    @PostConstruct
    public void init() {
        clientBuilderType.put(GitSource.UNDEFINED_SSH, GitSshClientBuilder.class);
    }

    @Override
    public String clone(Node node, String filePath, ProgressListener progressListener) throws GitException {
        String branch = node.getEnv(GitEnvs.FLOW_GIT_BRANCH);
        GitClient client = gitClientInstance(node);

        if (progressListener != null) {
            progressListener.onStart();
        }

        client.clone(branch, null, Sets.newHashSet(filePath), new GitCloneProgressMonitor(progressListener));

        if (progressListener != null) {
            progressListener.onFinish();
        }

        return fetch(client, filePath);
    }

    private static class GitCloneProgressMonitor implements ProgressMonitor {

        private final ProgressListener progressListener;

        private String currentTask;
        private int currentTotalWork;

        GitCloneProgressMonitor(ProgressListener progressListener) {
            this.progressListener = progressListener;
        }

        @Override
        public void start(int totalTasks) {

        }

        @Override
        public void beginTask(String title, int totalWork) {
            this.currentTask = title;
            this.currentTotalWork = totalWork;

            if (progressListener != null) {
                progressListener.onStartTask(title);
            }
        }

        @Override
        public void update(int completed) {
            if (progressListener != null) {
                progressListener.onProgressing(currentTask, currentTotalWork, completed);
            }
        }

        @Override
        public void endTask() {
            if (progressListener != null) {
                progressListener.onFinishTask(currentTask);
            }
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    }

    /**
     * Init git client from flow env
     *
     * - FLOW_GIT_SOURCE
     * - FLOW_GIT_URL
     * - FLOW_GIT_BRANCH
     * - FLOW_GIT_SSH_PRIVATE_KEY
     * - FLOW_GIT_SSH_PUBLIC_KEY
     */
    private GitClient gitClientInstance(Node node) {
        GitSource source = GitSource.valueOf(node.getEnv(GitEnvs.FLOW_GIT_SOURCE));
        Class<? extends GitClientBuilder> builderClass = clientBuilderType.get(source);
        if (builderClass == null) {
            throw new UnsupportedException(String.format("Git source %s not supported yet", source));
        }

        GitClientBuilder builder;
        try {
            builder = builderClass
                .getConstructor(Flow.class, Path.class)
                .newInstance(node, gitSourcePath(node));
        } catch (Throwable e) {
            throw new IllegalStatusException("Fail to create GitClientBuilder instance: " + e.getMessage());
        }

        GitClient client = builder.build();
        LOGGER.trace("Git client initialized: %s", client);
        return client;
    }

    /**
     * Get git source code folder path of flow workspace
     */
    private Path gitSourcePath(Node node) throws IOException {
        Path flowWorkspace = NodeUtil.workspacePath(workspace, node);
        Files.createDirectories(flowWorkspace);
        return Paths.get(flowWorkspace.toString(), SOURCE_FOLDER_NAME);
    }

    /**
     * Get target file from local git repo folder
     */
    private String fetch(GitClient gitClient, String filePath) {
        Path targetPath = Paths.get(gitClient.targetPath().toString(), filePath);

        if (Files.exists(targetPath)) {
            return getContent(targetPath);
        }

        return null;
    }

    /**
     * Get file content from source code folder of flow workspace
     */
    private String getContent(Path path) {
        try {
            return com.google.common.io.Files.toString(path.toFile(), AppConfig.DEFAULT_CHARSET);
        } catch (IOException e) {
            LOGGER.warn("Fail to load file content from %s", path.toString());
            return null;
        }
    }
}