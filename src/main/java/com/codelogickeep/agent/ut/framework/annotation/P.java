package com.codelogickeep.agent.ut.framework.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记工具方法的参数描述
 * 
 * 与 LangChain4j 的 @P 注解兼容
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface P {
    /**
     * 参数描述，用于告诉 LLM 这个参数的用途
     */
    String value();

    /**
     * 参数是否必需（默认 true）
     */
    boolean required() default true;
}
