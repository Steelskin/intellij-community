/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.plugins.gradle.execution.test.runner;

import com.intellij.execution.RunManagerEx;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.actions.ConfigurationFromContext;
import com.intellij.execution.actions.RunConfigurationProducer;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.model.ExternalProjectInfo;
import com.intellij.openapi.externalSystem.model.ProjectKeys;
import com.intellij.openapi.externalSystem.model.project.ModuleData;
import com.intellij.openapi.externalSystem.model.task.TaskData;
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration;
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil;
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiElement;
import com.intellij.util.BooleanFunction;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.gradle.service.project.GradleProjectResolverUtil;
import org.jetbrains.plugins.gradle.service.resolve.GradleCommonClassNames;
import org.jetbrains.plugins.gradle.settings.GradleSystemRunningSettings;
import org.jetbrains.plugins.gradle.util.GradleConstants;

import java.util.List;

import static org.jetbrains.plugins.gradle.settings.GradleSystemRunningSettings.PreferredTestRunner.GRADLE_TEST_RUNNER;
import static org.jetbrains.plugins.gradle.settings.GradleSystemRunningSettings.PreferredTestRunner.PLATFORM_TEST_RUNNER;

/**
 * @author Vladislav.Soroka
 * @since 2/26/2015
 */
public abstract class GradleTestRunConfigurationProducer extends RunConfigurationProducer<ExternalSystemRunConfiguration> {

  private static final List<String> TEST_SOURCE_SET_TASKS = ContainerUtil.list("cleanTest", "test");

  protected GradleTestRunConfigurationProducer(ConfigurationType configurationType) {
    super(configurationType);
  }

  @Override
  public boolean isPreferredConfiguration(ConfigurationFromContext self, ConfigurationFromContext other) {
    return GradleSystemRunningSettings.getInstance().getPreferredTestRunner() == null ||
           GradleSystemRunningSettings.getInstance().getPreferredTestRunner() == GRADLE_TEST_RUNNER;
  }

  @Override
  public boolean shouldReplace(ConfigurationFromContext self, ConfigurationFromContext other) {
    return GradleSystemRunningSettings.getInstance().getPreferredTestRunner() == GRADLE_TEST_RUNNER;
  }

  @Nullable
  @Override
  public RunnerAndConfigurationSettings findExistingConfiguration(ConfigurationContext context) {
    final RunnerAndConfigurationSettings existingConfiguration = super.findExistingConfiguration(context);
    if (existingConfiguration == null && GradleSystemRunningSettings.getInstance().getPreferredTestRunner() == GRADLE_TEST_RUNNER) {
      final ConfigurationFromContext createdContext = createConfigurationFromContext(context);
      if (createdContext != null) {
        final RunnerAndConfigurationSettings settings = createdContext.getConfigurationSettings();
        final RunManagerEx manager = RunManagerEx.getInstanceEx(context.getProject());
        manager.setTemporaryConfiguration(settings);
        return settings;
      }
      else {
        return null;
      }
    }

    return existingConfiguration;
  }

  @Override
  protected boolean setupConfigurationFromContext(ExternalSystemRunConfiguration configuration,
                                                  ConfigurationContext context,
                                                  Ref<PsiElement> sourceElement) {
    if (!GradleConstants.SYSTEM_ID.equals(configuration.getSettings().getExternalSystemId())) return false;
    if (GradleSystemRunningSettings.getInstance().getPreferredTestRunner() == PLATFORM_TEST_RUNNER) return false;

    return doSetupConfigurationFromContext(configuration, context, sourceElement);
  }

  protected abstract boolean doSetupConfigurationFromContext(ExternalSystemRunConfiguration configuration,
                                                             ConfigurationContext context,
                                                             Ref<PsiElement> sourceElement);

  @Override
  public boolean isConfigurationFromContext(ExternalSystemRunConfiguration configuration, ConfigurationContext context) {
    if (GradleSystemRunningSettings.getInstance().getPreferredTestRunner() == PLATFORM_TEST_RUNNER) return false;
    if (configuration == null) return false;
    if (!GradleConstants.SYSTEM_ID.equals(configuration.getSettings().getExternalSystemId())) return false;

    return doIsConfigurationFromContext(configuration, context);
  }

  protected abstract boolean doIsConfigurationFromContext(ExternalSystemRunConfiguration configuration, ConfigurationContext context);

  @NotNull
  static List<String> getTasksToRun(Module module) {
    final List<String> result;
    final String externalProjectId = ExternalSystemApiUtil.getExternalProjectId(module);
    if (externalProjectId == null) return ContainerUtil.emptyList();
    final String projectPath = ExternalSystemApiUtil.getExternalProjectPath(module);
    if (projectPath == null) return ContainerUtil.emptyList();
    final ExternalProjectInfo externalProjectInfo =
      ExternalSystemUtil.getExternalProjectInfo(module.getProject(), GradleConstants.SYSTEM_ID, projectPath);
    if (externalProjectInfo == null) return ContainerUtil.emptyList();

    if (StringUtil.endsWith(externalProjectId, ":test") || StringUtil.endsWith(externalProjectId, ":main")) {
      result = TEST_SOURCE_SET_TASKS;
    }
    else {
      final DataNode<ModuleData> moduleNode =
        GradleProjectResolverUtil.findModule(externalProjectInfo.getExternalProjectStructure(), projectPath);
      if (moduleNode == null) return ContainerUtil.emptyList();
      final String sourceSetId = StringUtil.substringAfter(externalProjectId, moduleNode.getData().getExternalName() + ':');
      if (sourceSetId == null) return ContainerUtil.emptyList();

      final DataNode<TaskData> taskNode =
        ExternalSystemApiUtil.find(moduleNode, ProjectKeys.TASK, new BooleanFunction<DataNode<TaskData>>() {
          @Override
          public boolean fun(DataNode<TaskData> node) {
            return GradleCommonClassNames.GRADLE_API_TASKS_TESTING_TEST.equals(node.getData().getType()) &&
                   StringUtil.startsWith(sourceSetId, node.getData().getName());
          }
        });

      if (taskNode == null) return ContainerUtil.emptyList();
      final String taskName = taskNode.getData().getName();
      result = ContainerUtil.list("clean" + StringUtil.capitalize(taskName), taskName);
    }
    return result;
  }
}
