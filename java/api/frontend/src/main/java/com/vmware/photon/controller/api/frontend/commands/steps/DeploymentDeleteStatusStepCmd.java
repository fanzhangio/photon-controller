/*
 * Copyright 2015 VMware, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, without warranties or
 * conditions of any kind, EITHER EXPRESS OR IMPLIED.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.vmware.photon.controller.api.frontend.commands.steps;

import com.vmware.photon.controller.api.frontend.backends.DeploymentXenonBackend;
import com.vmware.photon.controller.api.frontend.backends.StepBackend;
import com.vmware.photon.controller.api.frontend.backends.TaskBackend;
import com.vmware.photon.controller.api.frontend.commands.tasks.TaskCommand;
import com.vmware.photon.controller.api.frontend.entities.DeploymentEntity;
import com.vmware.photon.controller.api.frontend.entities.StepEntity;
import com.vmware.photon.controller.api.frontend.entities.TaskEntity;
import com.vmware.photon.controller.api.frontend.exceptions.ApiFeException;
import com.vmware.photon.controller.api.frontend.exceptions.external.DeleteDeploymentFailedException;
import com.vmware.photon.controller.api.model.Deployment;
import com.vmware.photon.controller.api.model.DeploymentState;
import com.vmware.photon.controller.api.model.Operation;
import com.vmware.photon.controller.common.clients.exceptions.RpcException;
import com.vmware.photon.controller.common.xenon.exceptions.DocumentNotFoundException;
import com.vmware.photon.controller.deployer.xenon.workflow.RemoveDeploymentWorkflowService;
import com.vmware.xenon.common.TaskState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * StepCommand that monitors the status of deleting a deployment.
 */
public class DeploymentDeleteStatusStepCmd extends XenonTaskStatusStepCmd {

  private static final Logger logger = LoggerFactory.getLogger(DeploymentDeleteStatusStepCmd.class);

  private static final long DEFAULT_DELETE_DEPLOYMENT_TIMEOUT = TimeUnit.HOURS.toMillis(2);
  private static final long DELETE_DEPLOYMENT_STATUS_POLL_INTERVAL = TimeUnit.SECONDS.toMillis(10);
  private static final long DEFAULT_MAX_SERVICE_UNAVAILABLE_COUNT = 100;

  public DeploymentDeleteStatusStepCmd(TaskCommand taskCommand, StepBackend stepBackend, StepEntity step,
                                       XenonTaskStatusPoller xenonTaskStatusPoller) {
    super(taskCommand, stepBackend, step, xenonTaskStatusPoller);
    super.setTimeout(DEFAULT_DELETE_DEPLOYMENT_TIMEOUT);
    super.setPollInterval(DELETE_DEPLOYMENT_STATUS_POLL_INTERVAL);
    super.setDocumentNotFoundMaxCount(DEFAULT_MAX_SERVICE_UNAVAILABLE_COUNT);
  }

  @Override
  protected void cleanup() {
  }

  @VisibleForTesting
  protected void setDeleteDeploymentTimeout(long timeout) {
    super.setTimeout(timeout);
  }

  @VisibleForTesting
  protected void setStatusPollInterval(long interval) {
    super.setPollInterval(interval);
  }

  @VisibleForTesting
  protected void setMaxServiceUnavailableCount(long count) {
    super.setDocumentNotFoundMaxCount(count);
  }

  @Override
  protected void execute() throws ApiFeException, RpcException, InterruptedException {
    // get the entity
    List<DeploymentEntity> deploymentEntityList =
        step.getTransientResourceEntities(Deployment.KIND);
    Preconditions.checkArgument(deploymentEntityList.size() == 1);
    DeploymentEntity entity = deploymentEntityList.get(0);
    step.createOrUpdateTransientResource(XenonTaskStatusStepCmd.REMOTE_TASK_LINK_RESOURCE_KEY,
        entity.getOperationId());
    setRemoteTaskLink(entity.getOperationId());
    super.execute();
  }

  /**
   * Polls task status.
   */
  public static class DeploymentDeleteStepPoller implements XenonTaskStatusStepCmd.XenonTaskStatusPoller {
    private static final Map<Operation, Integer> OPERATION_TO_SUBSTAGE_MAP =
        ImmutableMap.<Operation, Integer>builder()
            .put(Operation.PERFORM_DELETE_DEPLOYMENT,
                RemoveDeploymentWorkflowService.TaskState.SubStage.REMOVE_FROM_API_FE.ordinal())
            .put(Operation.DEPROVISION_HOSTS,
                RemoveDeploymentWorkflowService.TaskState.SubStage.DEPROVISION_HOSTS.ordinal())
            .build();

    private final DeploymentXenonBackend deploymentBackend;

    private DeploymentEntity entity;
    private TaskCommand taskCommand;
    private TaskBackend taskBackend;

    public DeploymentDeleteStepPoller(TaskCommand taskCommand,
                                      TaskBackend taskBackend,
                                      DeploymentXenonBackend deploymentBackend) {
      this.taskCommand = taskCommand;
      this.deploymentBackend = deploymentBackend;
      this.taskBackend = taskBackend;
    }

    @Override
    public int getTargetSubStage(Operation op) {
      Integer targetSubStage = OPERATION_TO_SUBSTAGE_MAP.get(op);
      if (targetSubStage == null) {
        throw new IllegalArgumentException("unexpected operation " + op);
      }
      return targetSubStage;
    }

    @Override
    public TaskState poll(String remoteTaskLink) throws DocumentNotFoundException, ApiFeException {
      List<DeploymentEntity> deploymentEntityList = null;
      for (StepEntity step : taskCommand.getTask().getSteps()) {
        deploymentEntityList = step.getTransientResourceEntities(Deployment.KIND);
        if (!deploymentEntityList.isEmpty()) {
          break;
        }
      }
      this.entity = deploymentEntityList.get(0);

      RemoveDeploymentWorkflowService.State serviceDocument = deploymentBackend.getDeployerClient()
          .getRemoveDeploymentStatus(remoteTaskLink);
      if (serviceDocument.taskState.stage == TaskState.TaskStage.FINISHED) {
        TaskEntity taskEntity = taskCommand.getTask();
        taskEntity.setEntityId(serviceDocument.deploymentId);
        taskEntity.setEntityKind(Deployment.KIND);
        taskBackend.update(taskEntity);
      } else if (serviceDocument.taskState.stage != TaskState.TaskStage.STARTED){
        handleTaskFailure(serviceDocument.taskState);
      }
      return serviceDocument.taskState;
    }

    private void handleTaskFailure(TaskState state) throws ApiFeException {
      if (this.entity != null) {
        logger.info("Deployment delete failed, mark entity {} state as ERROR", this.entity.getId());
        this.deploymentBackend.updateState(this.entity, DeploymentState.ERROR);
      }
      throw new DeleteDeploymentFailedException(this.entity == null ? "" : this.entity.getId(), state.failure.message);
    }

    @Override
    public void handleDone(TaskState taskState) throws ApiFeException {
      deploymentBackend.updateState(this.entity, DeploymentState.NOT_DEPLOYED);
    }

    @Override
    public int getSubStage(TaskState taskState) {
      return ((RemoveDeploymentWorkflowService.TaskState) taskState).subStage.ordinal();
    }

    @VisibleForTesting
    protected void setEntity(DeploymentEntity entity) {
      this.entity = entity;
    }


  }
}
