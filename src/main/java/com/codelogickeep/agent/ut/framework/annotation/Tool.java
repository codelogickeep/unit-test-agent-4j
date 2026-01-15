package com.codelogickeep.agent.ut.framework.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个方法为可被 LLM 调用的工具
 * 
 * 与 LangChain4j 的 @Tool 注解兼容
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Tool {
    /**
     * 工具描述，用于告诉 LLM 这个工具的用途
     */
    String value();
    
    /**
     * 工具名称（可选），默认使用方法名
     */
    String name() default "";
}
