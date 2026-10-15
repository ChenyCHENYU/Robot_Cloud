package com.robot.common.web.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 新密码输入合同；optional 仅供编辑时不改密码的 null/空串。 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NewPasswordValidator.class)
public @interface ValidNewPassword {
    /**
     * @return safe user-facing validation message
     */
    String message() default "密码长度6~32且UTF-8编码不得超过72字节";
    /**
     * @return validation groups in which the credential policy applies
     */
    Class<?>[] groups() default {};
    /**
     * @return standard Bean Validation payload metadata
     */
    Class<? extends Payload>[] payload() default {};
    /**
     * @return whether null or an empty string means no password change
     */
    boolean optional() default false;
}
