package com.codelogickeep.agent.ut.governance;

import com.codelogickeep.agent.ut.config.GovernanceConfig;
import com.codelogickeep.agent.ut.config.PolicyRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.PropertyAccessor;
import org.springframework.expression.TypedValue;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Custom Tool Governance Proxy using Spring Expression Language (SpEL).
 * This replaces the external governor-core dependency while maintaining similar
 * functionality.
 */
@Slf4j
public class ToolGovernanceProxy implements InvocationHandler {

    private final Object target;
    private final GovernanceConfig config;
    private final ExpressionParser parser = new SpelExpressionParser();

    private ToolGovernanceProxy(Object target, GovernanceConfig config) {
        this.target = target;
        this.config = config;
    }

    @SuppressWarnings("unchecked")
    public static <T> T createProxy(T target, Class<T> interfaceType, GovernanceConfig config) {
        return (T) Proxy.newProxyInstance(
                interfaceType.getClassLoader(),
                new Class<?>[] { interfaceType },
                new ToolGovernanceProxy(target, config));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String methodName = method.getName();
        // Skip governance if disabled (but we still want logging)
        boolean governanceEnabled = config != null && config.isEnabled();

        try {
            log.info(">>> [Tool Call] {}.{} Args: {}", target.getClass().getSimpleName(), methodName, formatArgs(args));

            if (governanceEnabled) {
                GovernanceResource resource = method.getAnnotation(GovernanceResource.class);
                if (resource != null) {
                    String resourceName = resource.value();
                    if (config.getPolicy() != null) {
                        Optional<PolicyRule> rule = config.getPolicy().stream()
                                .filter(r -> r.getResource().equals(resourceName))
                                .findFirst();

                        if (rule.isPresent()) {
                            checkPermission(rule.get(), method, args);
                        }
                    }
                }
            }

            Object result = method.invoke(target, args);
            log.info("<<< [Tool Return] {}.{} Result: {}", target.getClass().getSimpleName(), methodName, result);
            return result;

        } catch (Throwable t) {
            // Unwrap InvocationTargetException
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ? t.getCause() : t;
            
            log.error("!!! [Tool Error] {}.{} failed: {}", target.getClass().getSimpleName(), methodName, cause.getMessage());
            
            // Return error message to LLM if return type is String, so it can self-correct
            if (method.getReturnType().equals(String.class)) {
                return "ERROR: Tool execution failed: " + cause.getMessage();
            }
            throw cause;
        }
    }

    private String formatArgs(Object[] args) {
        if (args == null) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            Object arg = args[i];
            if (arg instanceof String) {
                sb.append("'").append(arg).append("'");
            } else {
                sb.append(arg);
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private void checkPermission(PolicyRule rule, Method method, Object[] args) {
        String action = rule.getAction();
        String condition = rule.getCondition();

        if ("ALLOW".equalsIgnoreCase(action)) {
            if (condition != null && !condition.isEmpty()) {
                boolean allowed = evaluateCondition(condition, method, args);
                if (!allowed) {
                    throw new SecurityException("Access denied for resource '" + rule.getResource() +
                            "': Condition failed (" + condition + ")");
                }
            }
        } else if ("DENY".equalsIgnoreCase(action)) {
            if (condition == null || condition.isEmpty() || evaluateCondition(condition, method, args)) {
                throw new SecurityException("Access denied for resource '" + rule.getResource() +
                        "': Explicitly denied");
            }
        }
    }

    private boolean evaluateCondition(String condition, Method method, Object[] args) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.addPropertyAccessor(new MapAccessor());

        Map<String, Object> arguments = new HashMap<>();
        Parameter[] parameters = method.getParameters();
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String paramName = parameters[i].getName();
                Object argValue = args[i];

                // Normalize paths in strings to use forward slashes for policy checks
                if (argValue instanceof String) {
                    argValue = ((String) argValue).replace('\\', '/');
                }

                arguments.put(paramName, argValue);
                // Also set as variable for #paramName syntax
                context.setVariable(paramName, argValue);
            }
        }

        // Set arguments map as root object to allow "path" instead of "#path"
        context.setRootObject(arguments);

        try {
            Expression expression = parser.parseExpression(condition);
            Boolean result = expression.getValue(context, Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("Failed to evaluate governance rule: {}", condition, e);
            return false;
        }
    }

    /**
     * Simple PropertyAccessor to allow Map keys to be accessed as properties.
     */
    private static class MapAccessor implements PropertyAccessor {
        @Override
        public Class<?>[] getSpecificTargetClasses() {
            return new Class<?>[] { Map.class };
        }

        @Override
        public boolean canRead(EvaluationContext context, Object target, String name) {
            return target instanceof Map && ((Map<?, ?>) target).containsKey(name);
        }

        @Override
        public TypedValue read(EvaluationContext context, Object target, String name) {
            return new TypedValue(((Map<?, ?>) target).get(name));
        }

        @Override
        public boolean canWrite(EvaluationContext context, Object target, String name) {
            return false;
        }

        @Override
        public void write(EvaluationContext context, Object target, String name, Object newValue) {
            // No-op
        }
    }
}
