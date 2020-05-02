package azrefarc.springboot.models;

import java.beans.ConstructorProperties;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Root;

import org.springframework.stereotype.Component;

// DI 対象となるコンポーネントであることを指定
@Component
public class AuthorsDaoImpl implements AuthorsDao {

	private EntityManager entityManager;
	
	// コンストラクタインジェクションを指定
	@ConstructorProperties({"entityManager"})
	public AuthorsDaoImpl(EntityManager entityManager) 
	{ 
		super();
		this.entityManager = entityManager;
	}

	@Override
	public List<Author> findByCondition(boolean enabledState, String state, boolean enabledPhone, String phone,
			boolean enabledContract, boolean contract, boolean enabledAuFname, String auFname) {
		// データ検索処理
		List<Author> list = null;
		CriteriaBuilder cb = entityManager.getCriteriaBuilder();
		CriteriaQuery<Author> query = cb.createQuery(Author.class);
		Root<Author> root = query.from(Author.class);
		query = query.select(root);
		
		// ※ プロパティ名の先頭は大文字ではなく小文字で指定
		if (enabledState) query.where(cb.equal(root.get("state"), state));
		if (enabledPhone) query.where(cb.equal(root.get("phone"), phone));
		if (enabledContract) query.where(cb.equal(root.get("contract"), contract));
		if (enabledAuFname) query.where(cb.equal(root.get("authorFirstName"), auFname));

		list = (List<Author>)entityManager.createQuery(query).getResultList();
		
		return list;
	}	
}
