package com.robot.common.web.validation;

import com.robot.common.core.util.PasswordPolicy;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Applies the shared credential policy while preserving explicit edit omission semantics. */
public final class NewPasswordValidator implements ConstraintValidator<ValidNewPassword, String> {
    private boolean optional;

    @Override
    public void initialize(ValidNewPassword constraint) {
        optional = constraint.optional();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return optional && (value == null || value.isEmpty())
                || PasswordPolicy.validNewPassword(value);
    }
}
