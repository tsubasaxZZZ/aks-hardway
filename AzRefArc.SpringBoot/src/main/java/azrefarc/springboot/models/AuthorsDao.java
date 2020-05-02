package azrefarc.springboot.models;

import java.util.List;

public interface AuthorsDao {
	List<Author> findByCondition(
			boolean enabledState, String state,
			boolean enabledPhone, String phone,
			boolean enabledContract, boolean contract,
			boolean enabledAuFname, String auFname);
}
