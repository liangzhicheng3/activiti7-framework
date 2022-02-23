package com.liangzhicheng.modules.controller;

import com.liangzhicheng.common.activiti.EngineSingleton;
import org.activiti.bpmn.model.BpmnModel;
import org.activiti.bpmn.model.FlowNode;
import org.activiti.bpmn.model.SequenceFlow;
import org.activiti.engine.*;
import org.activiti.engine.history.HistoricActivityInstance;
import org.activiti.engine.history.HistoricTaskInstance;
import org.activiti.engine.repository.Deployment;
import org.activiti.engine.runtime.Execution;
import org.activiti.engine.runtime.ProcessInstance;
import org.activiti.engine.task.Comment;
import org.activiti.engine.task.Task;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@RestController
@RequestMapping(value = "/leave")
public class LeaveController {

    @GetMapping(value = "/start")
    public void start(){
        //流程部署
        ProcessEngine processEngine = EngineSingleton.getInst().processEngine();
        RepositoryService repositoryService = processEngine.getRepositoryService();
        Deployment deployment = repositoryService.createDeployment()
                .addClasspathResource("bpmn/leave.bpmn")
                .name("请假流程")
                .deploy();
        System.out.println(String.format("流程部署id：%s", deployment.getId()));
        System.out.println(String.format("流程部署名称：%s", deployment.getName()));
        //流程启动
        RuntimeService runtimeService = processEngine.getRuntimeService();
        String assignee0 = "lzc";
        String assignee1 = "gx";
        Map<String, Object> assigneeMap = new HashMap<>();
        assigneeMap.put("assignee0", assignee0);
        assigneeMap.put("assignee1", assignee1);
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey(
                "leaveProcess",
                assigneeMap);
        System.out.println(String.format("流程定义id：%s", processInstance.getProcessDefinitionId()));
        System.out.println(String.format("流程实例id：%s", processInstance.getId()));
    }

    @GetMapping(value = "/complete")
    public void complete(){
        String assignee = "lzc";
        String comment = String.format("%s表示同意", assignee);
        //完成待办任务
        ProcessEngine processEngine = EngineSingleton.getInst().processEngine();
        TaskService taskService = processEngine.getTaskService();
        List<Task> taskList = taskService.createTaskQuery()
                .processDefinitionKey("leaveProcess")
                .taskAssignee(assignee)
                .list();
        if(taskList != null && taskList.size() > 0){
            for(Iterator<Task> taskIt = taskList.iterator(); taskIt.hasNext();){
                Task task = taskIt.next();
                if(StringUtils.isNotBlank(comment)){
                    taskService.addComment(
                            task.getId(),
                            task.getProcessInstanceId(),
                            comment);
                }
                taskService.complete(task.getId());
            }
        }
    }

    /**
     * 回退上一个任务节点
     *
     * @param task 当前任务节点
     */
    @GetMapping(value = "/rollback")
    public void rollback(Task task){
        //回退审批任务
        String processInstanceId = task.getProcessInstanceId();
        ProcessEngine processEngine = EngineSingleton.getInst().processEngine();
        HistoryService historyService = processEngine.getHistoryService();
        //根据流程实例id获取所有历史任务实例按时间降序排序
        List<HistoricTaskInstance> historyTaskInstList = historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .orderByTaskCreateTime()
                .desc()
                .list();
        if(historyTaskInstList == null || historyTaskInstList.size() < 2){
            return;
        }

        //当前任务节点的上一个任务节点
        HistoricTaskInstance lastTask = historyTaskInstList.get(1);
        //当前任务节点
        HistoricTaskInstance currentTask = historyTaskInstList.get(0);

        //上一个任务节点的taskId
        String lastTaskId = lastTask.getId();
        //上一个任务节点的executionId
        String lastExecutionId = lastTask.getExecutionId();

        //当前任务节点的executionId
        String currentExecutionId = currentTask.getExecutionId();

        //根据上一个任务节点的执行id获取所有历史任务实例（所有节点）
        String lastActivityId = "";
        List<HistoricActivityInstance> historyActivityInstList = historyService.createHistoricActivityInstanceQuery()
                .executionId(lastExecutionId)
                .finished()
                .list();
        if(historyActivityInstList != null && historyActivityInstList.size() > 0){
            for(Iterator<HistoricActivityInstance> activityInstIt =
                historyActivityInstList.iterator(); activityInstIt.hasNext();){
                HistoricActivityInstance historicActivityInst = activityInstIt.next();
                if(lastTaskId.equals(historicActivityInst.getTaskId())){
                    lastActivityId = historicActivityInst.getActivityId();
                    break;
                }
            }
        }

        //获取上一个任务节点信息
        RepositoryService repositoryService = processEngine.getRepositoryService();
        BpmnModel bpmnModel = repositoryService.getBpmnModel(lastTask.getProcessDefinitionId());
        FlowNode lastFlowNode = (FlowNode) bpmnModel.getMainProcess().getFlowElement(lastActivityId);

        //获取当前任务节点信息
        RuntimeService runtimeService = processEngine.getRuntimeService();
        Execution execution = runtimeService.createExecutionQuery()
                .executionId(currentExecutionId)
                .singleResult();
        FlowNode currentFlowNode = (FlowNode) bpmnModel.getMainProcess().getFlowElement(execution.getActivityId());

        //存储当前任务节点的原活动方向
        List<SequenceFlow> oldSequenceFlowList = new ArrayList<>();
        oldSequenceFlowList.addAll(currentFlowNode.getOutgoingFlows());

        //清除当前任务节点的原活动方向
        currentFlowNode.getOutgoingFlows().clear();

        //建立新的方向
        List<SequenceFlow> newSequenceFlowList = new ArrayList<>();
        SequenceFlow newSequenceFlow = new SequenceFlow();
        newSequenceFlow.setId("newSequenceFlowId");
        newSequenceFlow.setSourceFlowElement(currentFlowNode);
        newSequenceFlow.setTargetFlowElement(lastFlowNode);
        newSequenceFlowList.add(newSequenceFlow);
        currentFlowNode.setOutgoingFlows(newSequenceFlowList);

        //完成任务
        TaskService taskService = processEngine.getTaskService();
        taskService.complete(task.getId());

        //恢复原活动方向
        currentFlowNode.setOutgoingFlows(oldSequenceFlowList);

        //设置执行人员
        Task nextTask = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        if(nextTask != null){
            taskService.setAssignee(nextTask.getId(), lastTask.getAssignee());
        }
    }

    @GetMapping(value = "/history")
    public void history(){
        //获取历史审批任务（所有节点）
        ProcessEngine processEngine = EngineSingleton.getInst().processEngine();
        HistoryService historyService = processEngine.getHistoryService();
        TaskService taskService = processEngine.getTaskService();
        List<HistoricActivityInstance> historyList = historyService.createHistoricActivityInstanceQuery()
                .processInstanceId("f868252c-93ae-11ec-a0df-244bfe58a4ac")
                .activityType("userTask")
                .taskAssignee("lzc")
                .finished()
                .list();
        if(historyList != null && historyList.size() > 0){
            for(Iterator<HistoricActivityInstance> historyIt =
                historyList.iterator(); historyIt.hasNext();){
                HistoricActivityInstance history = historyIt.next();
                System.out.println(String.format("任务名称：%s", history.getActivityName()));
                System.out.println(String.format("任务开始时间：%s", history.getStartTime()));
                System.out.println(String.format("任务结束时间%s", history.getEndTime()));
                System.out.println(String.format("任务耗时时间：%s", history.getDurationInMillis()));
                /**
                 * 获取审批意见信息
                 *
                 * Comment出现过时，建议自己创建Comment表
                 */
                List<Comment> commentList = taskService.getTaskComments(history.getTaskId());
                if(commentList != null && commentList.size() > 0){
                    System.out.println(String.format("审批意见：%s", commentList.get(0).getFullMessage()));
                }
            }
        }
    }

}
