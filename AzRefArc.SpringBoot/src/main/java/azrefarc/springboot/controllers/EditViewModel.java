package azrefarc.springboot.controllers;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

public class EditViewModel {

	// 入力対象でないフィールドにはアノテーションはつけない
	private String authorId;

	// 空文字に正規表現がヒットしないように工夫
	// NotBlank だと  null, Empty, 空白だけの文字にヒット
	@NotBlank(message="著者名（名）は必須入力です。")
	@Pattern(regexp="^$|^[\u0020-\u007e]{1,20}$", message="著者名（名）は半角 20 文字以内で入力してください。")
	private String authorFirstName;
	
	@NotBlank(message="著者名（姓）は必須入力です。")
	@Pattern(regexp="^$|^[\u0020-\u007e]{1,40}$", message="著者名（姓）は半角 40 文字以内で入力してください。")
	private String authorLastName;

	@NotBlank(message="電話番号は必須入力です。")
	@Pattern(regexp="^$|^\\d{3} \\d{3}-\\d{4}$", message="電話番号は 012 345-6789 のように入力してください。")
	private String phone;

	@NotBlank(message="州は必須入力です。")
	@Pattern(regexp="^$|^[A-Z]{2}$", message="州はアルファベット 2 文字で指定してください。")
	private String state;

	private String originalAuthor;
	
	public String getAuthorId() {
		return authorId;
	}
	public void setAuthorId(String authorId) {
		this.authorId = authorId;
	}
	public String getAuthorFirstName() {
		return authorFirstName;
	}
	public void setAuthorFirstName(String authorFirstName) {
		this.authorFirstName = authorFirstName;
	}
	public String getAuthorLastName() {
		return authorLastName;
	}
	public void setAuthorLastName(String authorLastName) {
		this.authorLastName = authorLastName;
	}
	public String getPhone() {
		return phone;
	}
	public void setPhone(String phone) {
		this.phone = phone;
	}
	public String getState() {
		return state;
	}
	public void setState(String state) {
		this.state = state;
	}
	public String getOriginalAuthor() {
		return originalAuthor;
	}
	public void setOriginalAuthor(String originalAuthor) {
		this.originalAuthor = originalAuthor;
	}
}
