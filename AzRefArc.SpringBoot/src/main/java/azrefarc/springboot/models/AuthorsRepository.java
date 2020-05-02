package azrefarc.springboot.models;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuthorsRepository extends JpaRepository<Author, String> {

    public List<Author> findAll();
    public Optional<Author> findByAuthorId(String AuthorId);

}
