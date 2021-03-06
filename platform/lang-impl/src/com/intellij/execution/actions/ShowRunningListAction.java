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
package com.intellij.execution.actions;

import com.intellij.execution.ExecutionBundle;
import com.intellij.execution.Executor;
import com.intellij.execution.KillableProcess;
import com.intellij.execution.impl.ExecutionManagerImpl;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.BalloonBuilder;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.openapi.wm.ex.WindowManagerEx;
import com.intellij.ui.LayeredIcon;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.panels.NonOpaquePanel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Set;

public class ShowRunningListAction extends AnAction {
  public ShowRunningListAction() {
    super(ExecutionBundle.message("show.running.list.action.name"), ExecutionBundle.message("show.running.list.action.description"),
          LayeredIcon.create(AllIcons.RunConfigurations.Variables, AllIcons.Nodes.RunnableMark));
  }

  @Override
  public void actionPerformed(final AnActionEvent e) {
    final Project project = e.getProject();
    if (project == null || project.isDisposed()) return;
    final Ref<Pair<? extends JComponent, String>> stateRef = new Ref<Pair<? extends JComponent, String>>();
    final Ref<Balloon> balloonRef = new Ref<Balloon>();

    final Timer timer = new Timer(250, null);
    ActionListener actionListener = new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent actionEvent) {
        Balloon balloon = balloonRef.get();
        if (project.isDisposed() || (balloon != null && balloon.isDisposed())) {
          timer.stop();
          return;
        }
        Pair<? extends JComponent, String> state = getCurrentState(project);

        Pair<? extends JComponent, String> prevState = stateRef.get();
        if (prevState != null && prevState.getSecond().equals(state.getSecond())) return;
        stateRef.set(state);

        BalloonBuilder builder = JBPopupFactory.getInstance().createBalloonBuilder(state.getFirst());
        builder.setShowCallout(false)
          .setTitle(ExecutionBundle.message("show.running.list.balloon.title"))
          .setBlockClicksThroughBalloon(true)
          .setDialogMode(true)
          .setHideOnKeyOutside(false);
        IdeFrame frame = IdeFrame.KEY.getData(e.getDataContext());
        if (frame == null) {
          frame = WindowManagerEx.getInstanceEx().getFrame(project);
        }
        if (balloon != null) {
          balloon.hide();
        }
        builder.setClickHandler(new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            if (e.getSource() instanceof MouseEvent) {
              MouseEvent mouseEvent = (MouseEvent)e.getSource();
              Component component = mouseEvent.getComponent();
              component = SwingUtilities.getDeepestComponentAt(component, mouseEvent.getX(), mouseEvent.getY());
              Object value = ((JComponent)component).getClientProperty(KEY);
              if (value instanceof Pair) {
                ExecutionManagerImpl.getInstance(project).getContentManager().
                  toFrontRunContent((Executor)((Pair)value).first, (RunContentDescriptor)((Pair)value).second);
              }
            }
          }
        }, false);
        balloon = builder.createBalloon();

        balloonRef.set(balloon);
        JComponent component = frame.getComponent();
        RelativePoint point = new RelativePoint(component, new Point(component.getWidth(), 0));
        balloon.show(point, Balloon.Position.below);
      }
    };
    timer.addActionListener(actionListener);
    timer.setInitialDelay(0);
    timer.start();
  }

  private static final Object KEY = new Object();

  private static Pair<? extends JComponent, String> getCurrentState(@NotNull Project project) {
    final ExecutionManagerImpl executionManager = ExecutionManagerImpl.getInstance(project);
    List<RunContentDescriptor> runningDescriptors = executionManager.getRunningDescriptors(Condition.TRUE);
    NonOpaquePanel panel = new NonOpaquePanel(new GridLayout(0, 1, 10, 10));
    StringBuilder state = new StringBuilder();
    for (RunContentDescriptor descriptor : runningDescriptors) {
      Set<Executor> executors = executionManager.getExecutors(descriptor);
      for (Executor executor : executors) {
        state.append(System.identityHashCode(descriptor.getAttachedContent())).append("@")
          .append(System.identityHashCode(executor.getIcon())).append(";");
        ProcessHandler processHandler = descriptor.getProcessHandler();
        Icon icon = (processHandler instanceof KillableProcess && processHandler.isProcessTerminating())
                    ? AllIcons.Debugger.KillProcess
                    : executor.getIcon();
        JLabel label =
          new JLabel("<html><body><a href=\"\">" + descriptor.getDisplayName() + "</a></body></html>", icon, SwingConstants.LEADING);
        label.setIconTextGap(JBUI.scale(2));
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.putClientProperty(KEY, Pair.create(executor, descriptor));
        panel.add(label);
      }
    }
    if (panel.getComponentCount() == 0) {
      panel.setBorder(JBUI.Borders.empty(10));
      panel.add(new JLabel(ExecutionBundle.message("show.running.list.balloon.nothing"), SwingConstants.CENTER));
    }
    else {
      panel.setBorder(JBUI.Borders.empty(10, 10, 0, 10));
      JLabel label = new JLabel(ExecutionBundle.message("show.running.list.balloon.hint"));
      label.setFont(JBUI.Fonts.miniFont());
      panel.add(label);
    }
    return Pair.create(panel, state.toString());
  }

  @Override
  public void update(AnActionEvent e) {
    Project project = e.getProject();
    e.getPresentation().setEnabled(project != null && !project.isDisposed()
                                   && !ExecutionManagerImpl.getInstance(project).getRunningDescriptors(Condition.TRUE).isEmpty());
  }
}
