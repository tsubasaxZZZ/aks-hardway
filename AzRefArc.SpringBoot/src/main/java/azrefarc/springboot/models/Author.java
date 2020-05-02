package azrefarc.springboot.models;

import java.io.Serializable;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Version;

@SuppressWarnings("serial")
@Entity
@Table(name="authors")
public class Author implements Serializable
{
	@Id
	@Column(name="au_id", nullable=false, length=11)
    private String authorId;
    public String getAuthorId() { return authorId; }
    public void setAuthorId(String AuthorId) { this.authorId = AuthorId; }

	@Column(name="au_fname", nullable=false, length=20)
    private String authorFirstName;
    public String getAuthorFirstName() { return authorFirstName; }
    public void setAuthorFirstName(String AuthorFirstName) { this.authorFirstName = AuthorFirstName; }

	@Column(name="au_lname", nullable=false, length=40)
    private String authorLastName;
    public String getAuthorLastName() { return authorLastName; }
    public void setAuthorLastName(String AuthorLastName) { this.authorLastName = AuthorLastName; }

	@Column(name="phone", nullable=false, length=12)
    private String phone;
    public String getPhone() { return phone; }
    public void setPhone(String Phone) { this.phone = Phone; }

	@Column(name="address", nullable=true, length=40)
    private String address;
    public String getAddress() { return address; }
    public void setAddress(String Address) { this.address = Address; }

	@Column(name="city", nullable=true, length=20)
    private String city;
    public String getCity() { return city; }
    public void setCity(String City) { this.city = City; }

	@Column(name="state", nullable=true, length=2)
    private String state;
    public String getState() { return state; }
    public void setState(String State) { this.state = State; }

	@Column(name="zip", nullable=true, length=5)
    private String zip;
    public String getZip() { return zip; }
    public void setZip(String Zip) { this.zip = Zip; }

	@Column(name="contract", nullable=false)
    private boolean contract;
    public boolean getContract() { return contract; }
    public void setContract(boolean Contract) { this.contract = Contract; }

	@Column(name="rowversion", nullable=true, insertable=false, updatable=false)
	@Version
    private byte[] rowVersion;
    public byte[] getRowVersion() { return rowVersion; }
    public void setRowVersion(byte[] RowVersion) { this.rowVersion = RowVersion; }
}
