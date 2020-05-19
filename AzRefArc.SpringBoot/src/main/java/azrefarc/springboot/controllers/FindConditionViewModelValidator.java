package azrefarc.springboot.controllers;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

public class FindConditionViewModelValidator implements ConstraintValidator<FindConditionViewModelConstraint, FindConditionViewModel>{

    @Override
    public void initialize(FindConditionViewModelConstraint annotation) {
    }
	
	@Override
	public boolean isValid(FindConditionViewModel value, ConstraintValidatorContext context) {
		if (value.isEnabledState() && isNullOrEmpty(value.getState())) return false;
		if (value.isEnabledPhone() && isNullOrEmpty(value.getPhone())) return false;
		if (value.isEnabledContract() && isNullOrEmpty(value.getContract())) return false;
		if (value.isEnabledAuFname() && isNullOrEmpty(value.getAuFname())) return false;
		return true;
	}

	private static boolean isNullOrEmpty(String str)
	{
		return (str == null || str.length() == 0);
	}
}
