package azrefarc.springboot.controllers;

import javax.validation.constraints.Pattern;

@FindConditionViewModelConstraint
public class FindConditionViewModel {

	private boolean enabledState;
	private boolean enabledPhone;
	private boolean enabledContract;
	private boolean enabledAuFname;
	
	@Pattern(regexp="^$|^[A-Z]{2}$", message="州は半角 2 文字です。")
	private String state;
	
	@Pattern(regexp="^$|^\\d{3} \\d{3}-\\d{4}$", message="電話番号は 012 456-7894 のような形式で入力してください。")
	private String phone;

	@Pattern(regexp="^$|^true$|^false$", message="契約有無は true/false/無指定 のいずれかです。")
	private String contract;
	
	@Pattern(regexp="^$|^[\\u0020-\\u007e]{1,20}$", message="名前は英数半角 20 文字以内で入力してください。")
	private String auFname;

	public boolean isEnabledState() {
		return enabledState;
	}

	public void setEnabledState(boolean enabledState) {
		this.enabledState = enabledState;
	}

	public boolean isEnabledPhone() {
		return enabledPhone;
	}

	public void setEnabledPhone(boolean enabledPhone) {
		this.enabledPhone = enabledPhone;
	}

	public boolean isEnabledContract() {
		return enabledContract;
	}

	public void setEnabledContract(boolean enabledContract) {
		this.enabledContract = enabledContract;
	}

	public boolean isEnabledAuFname() {
		return enabledAuFname;
	}

	public void setEnabledAuFname(boolean enabledAuFname) {
		this.enabledAuFname = enabledAuFname;
	}

	public String getState() {
		return state;
	}

	public void setState(String state) {
		this.state = state;
	}

	public String getPhone() {
		return phone;
	}

	public void setPhone(String phone) {
		this.phone = phone;
	}

	public String getContract() {
		return contract;
	}

	public void setContract(String contract) {
		this.contract = contract;
	}

	public String getAuFname() {
		return auFname;
	}

	public void setAuFname(String auFname) {
		this.auFname = auFname;
	}
}

