package azrefarc.springboot.controllers;

import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.ModelAndView;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;

import azrefarc.springboot.framework.SerializeUtil;
import azrefarc.springboot.models.*;

@Controller
public class AuthorsController {

	@Autowired
	AuthorsRepository repository;
	@Autowired
	AuthorsDao dao;

	@RequestMapping(value = "/authors/listAuthors", method = RequestMethod.GET)
	public ModelAndView listAuthors(ModelAndView mav) {
		
		Iterable<Author> list = repository.findAll();
		mav.addObject("authors",list);
		
		mav.setViewName("authors/listAuthors");
		return mav;
	}

	@RequestMapping(value = "/authors/editAuthor/{id}", method = RequestMethod.GET)
	public ModelAndView editAuthor_get(
			@PathVariable String id, 
			@ModelAttribute("editViewModel") EditViewModel vm,
			ModelAndView mav) {
		if (id == null) throw new IllegalArgumentException("id shoud not be null.");
		Optional<Author> op_author = repository.findByAuthorId(id);
		if (op_author.isPresent() == false) throw new IllegalArgumentException("Not exist such id " + id);
		
		Author author = op_author.get();
		vm.setAuthorId(author.getAuthorId());
		vm.setAuthorFirstName(author.getAuthorFirstName()); 
		vm.setAuthorLastName(author.getAuthorLastName()); 
		vm.setPhone(author.getPhone()); 
		vm.setState(author.getState()); 
		vm.setOriginalAuthor(SerializeUtil.SerializeToBase64String(author));
		
		mav.addObject("editViewModel", vm);
		mav.addObject("states", USStatesUtil.GetAllStates());
		mav.setViewName("authors/editAuthor");
		return mav;
	}

	@RequestMapping(value = "/authors/editAuthor/{id}", method = RequestMethod.POST)
	public ModelAndView editAuthor_post(
			@PathVariable String id, 
			@ModelAttribute("editViewModel") @Validated EditViewModel vm, 
			BindingResult result,
			ModelAndView mav) {

		// バリデーション処理
		if (result.hasErrors() == true) {
			// エラーがある場合はエラー表示
			vm.setAuthorId(id);
			mav.addObject("editViewModel", vm);
			mav.addObject("states", USStatesUtil.GetAllStates());
			mav.setViewName("authors/editAuthor");
			return mav;
		}
		
		// データを復元
		Author originalAuthor = SerializeUtil.DeserializeFromBase64String(vm.getOriginalAuthor(), Author.class);

		// データを反映
		originalAuthor.setAuthorFirstName(vm.getAuthorFirstName());
		originalAuthor.setAuthorLastName(vm.getAuthorLastName());
		originalAuthor.setPhone(vm.getPhone());
		originalAuthor.setState(vm.getState());
		
		try
		{
			repository.saveAndFlush(originalAuthor);
		}
		catch (org.springframework.orm.ObjectOptimisticLockingFailureException ex)
		{
			// 楽観同時実行制御エラー
			vm.setAuthorId(id);
			mav.addObject("author", vm);
			mav.addObject("states", USStatesUtil.GetAllStates());
			result.reject("com.microsoft.japan.mcs.authorsController.editAuthor.optimisticConcurrencyError", "他のユーザによりデータが更新されています。キャンセルしてやり直してください。");
			mav.setViewName("authors/editAuthor");
			return mav;
		}

		// 問題なければ更新して前のページに戻す
		mav.setViewName("redirect:/authors/listAuthors");
		return mav;
	}
	
	@RequestMapping(value = "/authors/filterByCondition", method = RequestMethod.GET)
	public ModelAndView filterByCondition_get(
			@ModelAttribute("findConditionViewModel") FindConditionViewModel vm,
			ModelAndView mav) {
		
		mav.addObject("findConditionViewModel", vm);
		mav.addObject("states", USStatesUtil.GetAllStates());
		mav.setViewName("authors/filterByCondition");
		return mav;
	}
	
	@RequestMapping(value = "/authors/filterByCondition", method = RequestMethod.POST)
	public ModelAndView filterByCondition_post(
			@ModelAttribute("findConditionViewModel") @Validated FindConditionViewModel vm,
			BindingResult result,
			ModelAndView mav) {
		
		// バリデーション処理
		if (result.hasErrors() == true) {
			// エラーがある場合はエラー表示
			mav.addObject("findConditionViewModel", vm);
			mav.addObject("states", USStatesUtil.GetAllStates());
			mav.setViewName("authors/filterByCondition");
			return mav;
		}
		
		// データ検索処理 (DAO 経由で実施)
		List<Author> list = dao.findByCondition(
				vm.isEnabledState(), vm.getState(), 
				vm.isEnabledPhone(), vm.getPhone(),
				vm.isEnabledContract(), (vm.getContract().equals("true")),
				vm.isEnabledAuFname(), vm.getAuFname());
		mav.addObject("authors",list);
		
		// 画面を表示
		mav.addObject("findConditionViewModel", vm);
		mav.addObject("states", USStatesUtil.GetAllStates());
		mav.setViewName("authors/filterByCondition");
		
		return mav;
	}
}
