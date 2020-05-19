package azrefarc.springboot.controllers;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import javax.validation.Constraint;
import javax.validation.Payload;

@Documented
@Constraint(validatedBy = FindConditionViewModelValidator.class)
@Target( { ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface FindConditionViewModelConstraint {
    String message() default "検索条件として指定した項目は、検索条件の指定が必要です。"; 
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {}; 
}
