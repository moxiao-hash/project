package com.moxiao.studypilot.agent.tool;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.Comparator;

@Component
public class AgentToolRequestValidator {
    private final Validator validator;

    public AgentToolRequestValidator(Validator validator) {
        this.validator = validator;
    }

    public <T> T requireValid(T request) {
        var violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                    .sorted(Comparator.comparing(item -> item.getPropertyPath().toString()))
                    .map(ConstraintViolation::getMessage)
                    .findFirst().orElse("工具参数校验失败");
            throw new IllegalArgumentException(message);
        }
        return request;
    }
}
