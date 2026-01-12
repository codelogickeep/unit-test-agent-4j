package com.codelogickeep.agent.ut.exception;

/**
 * Unified exception class for all Agent Tool errors.
 * Provides structured error information to help the Agent understand and recover from failures.
 */
public class AgentToolException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String context;
    private final String suggestion;

    /**
     * Basic constructor with error code and message.
     */
    public AgentToolException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.context = null;
        this.suggestion = errorCode.getSuggestion();
    }

    /**
     * Constructor with error code, message, and context.
     */
    public AgentToolException(ErrorCode errorCode, String message, String context) {
        super(message);
        this.errorCode = errorCode;
        this.context = context;
        this.suggestion = errorCode.getSuggestion();
    }

    /**
     * Constructor with error code, message, context, and cause.
     */
    public AgentToolException(ErrorCode errorCode, String message, String context, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.context = context;
        this.suggestion = errorCode.getSuggestion();
    }

    /**
     * Private constructor for builder pattern.
     */
    private AgentToolException(Builder builder) {
        super(builder.message, builder.cause);
        this.errorCode = builder.errorCode;
        this.context = builder.context;
        this.suggestion = builder.suggestion != null ? builder.suggestion : builder.errorCode.getSuggestion();
    }

    /**
     * Create a builder for more flexible exception construction.
     */
    public static Builder builder(ErrorCode errorCode, String message) {
        return new Builder(errorCode, message);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public String getContext() {
        return context;
    }

    public String getSuggestion() {
        return suggestion;
    }

    /**
     * Returns a formatted error message suitable for Agent consumption.
     * Includes error code, message, context, and suggestion.
     */
    public String toAgentMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("ERROR [").append(errorCode.getCode()).append("]: ").append(getMessage());
        
        if (context != null && !context.isEmpty()) {
            sb.append("\nContext: ").append(context);
        }
        
        if (suggestion != null && !suggestion.isEmpty()) {
            sb.append("\nSuggestion: ").append(suggestion);
        }
        
        return sb.toString();
    }

    @Override
    public String toString() {
        return toAgentMessage();
    }

    /**
     * Builder for AgentToolException with custom suggestion support.
     */
    public static class Builder {
        private final ErrorCode errorCode;
        private final String message;
        private String context;
        private String suggestion;
        private Throwable cause;

        private Builder(ErrorCode errorCode, String message) {
            this.errorCode = errorCode;
            this.message = message;
        }

        public Builder context(String context) {
            this.context = context;
            return this;
        }

        public Builder suggestion(String suggestion) {
            this.suggestion = suggestion;
            return this;
        }

        public Builder cause(Throwable cause) {
            this.cause = cause;
            return this;
        }

        public AgentToolException build() {
            return new AgentToolException(this);
        }
    }

    /**
     * Error codes categorized by type for better error handling.
     */
    public enum ErrorCode {
        // File System Errors (1xx)
        FILE_NOT_FOUND("E101", "File not found", "Use 'fileExists' to check if the file exists before reading."),
        FILE_ACCESS_DENIED("E102", "Access denied - path outside project root", "Ensure the path is relative to the project root."),
        FILE_WRITE_FAILED("E103", "Failed to write file", "Check if the path is valid and the directory exists."),
        INVALID_PATH("E104", "Invalid or null path", "Provide a valid file path relative to the project root."),
        CONTENT_NOT_FOUND("E105", "Content to replace not found", "Use 'readFile' to get the exact content first."),
        FILE_READ_FAILED("E106", "Failed to read file", "Check file permissions and encoding."),
        PATH_OUT_OF_BOUNDS("E107", "Path is outside project root", "Use a path within the project root directory."),
        INVALID_ARGUMENT("E108", "Invalid argument provided", "Check the parameter value and try again."),
        IO_ERROR("E109", "I/O operation failed", "Check file/directory permissions and disk space."),

        // Directory Errors (2xx)
        DIRECTORY_NOT_FOUND("E201", "Directory not found", "Use 'directoryExists' to check before listing."),
        NOT_A_DIRECTORY("E202", "Path is not a directory", "Provide a directory path, not a file path."),
        DIRECTORY_CREATE_FAILED("E203", "Failed to create directory", "Check if the parent path is valid."),

        // Code Analysis Errors (3xx)
        PARSE_ERROR("E301", "Failed to parse Java source file", "Ensure the file contains valid Java syntax."),
        CLASS_NOT_FOUND("E302", "Class not found in source file", "Check if the class name matches the file content."),
        METHOD_NOT_FOUND("E303", "Method not found in class", "Use 'analyzeClass' to list available methods first."),

        // Coverage Errors (4xx)
        COVERAGE_REPORT_NOT_FOUND("E401", "JaCoCo coverage report not found", "Run 'mvn test jacoco:report' to generate the report."),
        COVERAGE_PARSE_ERROR("E402", "Failed to parse coverage report", "Ensure the JaCoCo XML report is valid."),
        COVERAGE_CLASS_NOT_FOUND("E403", "Class not found in coverage report", "Check if the class was included in test execution."),

        // Maven Execution Errors (5xx)
        MAVEN_COMPILE_FAILED("E501", "Maven compilation failed", "Check the error log for syntax or dependency issues."),
        MAVEN_TEST_FAILED("E502", "Maven test execution failed", "Review the test failure details in the output."),
        MAVEN_TIMEOUT("E503", "Maven execution timed out", "Try running with fewer tests or increase timeout."),

        // Configuration Errors (6xx)
        CONFIG_NOT_FOUND("E601", "Configuration file not found", "Create an agent.yml or use --config to specify one."),
        CONFIG_INVALID("E602", "Invalid configuration", "Check the configuration file for syntax errors."),
        CONFIG_MISSING_FIELD("E603", "Required configuration field missing", "Ensure all required fields are set in the config."),

        // Knowledge Base Errors (7xx)
        KB_NOT_INITIALIZED("E701", "Knowledge base not initialized", "Specify a valid knowledge base path with -kb."),
        KB_SEARCH_FAILED("E702", "Knowledge base search failed", "Try a different search query."),

        // External Tool Errors (8xx)
        EXTERNAL_TOOL_ERROR("E801", "External tool execution failed", "Check the tool is installed and accessible."),
        RESOURCE_NOT_FOUND("E802", "Required resource not found", "Ensure the resource exists and is accessible."),
        PARSING_ERROR("E803", "Failed to parse data", "Check the data format is correct."),
        
        // General Errors (9xx)
        UNKNOWN_ERROR("E999", "Unknown error occurred", "Check the logs for more details.");

        private final String code;
        private final String description;
        private final String suggestion;

        ErrorCode(String code, String description, String suggestion) {
            this.code = code;
            this.description = description;
            this.suggestion = suggestion;
        }

        public String getCode() {
            return code;
        }

        public String getDescription() {
            return description;
        }

        public String getSuggestion() {
            return suggestion;
        }
    }
}
