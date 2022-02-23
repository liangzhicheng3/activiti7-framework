package com.liangzhicheng.common.activiti;

import org.activiti.engine.ProcessEngine;
import org.activiti.engine.ProcessEngines;

public class EngineSingleton {

    private EngineSingleton(){}

    private static EngineSingleton instance;

    public static EngineSingleton getInst(){
        if(instance == null){
            instance = new EngineSingleton();
        }
        return instance;
    }

    public ProcessEngine processEngine(){
        return ProcessEngines.getDefaultProcessEngine();
    }

}
