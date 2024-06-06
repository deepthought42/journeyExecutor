package com.looksee.journeyExecutor.services;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import com.looksee.journeyExecutor.models.Audit;
import com.looksee.journeyExecutor.models.AuditRecord;
import com.looksee.journeyExecutor.models.ElementState;
import com.looksee.journeyExecutor.models.PageAuditRecord;
import com.looksee.journeyExecutor.models.PageState;
import com.looksee.journeyExecutor.models.Screenshot;
import com.looksee.journeyExecutor.models.enums.AuditName;
import com.looksee.journeyExecutor.models.enums.ElementClassification;
import com.looksee.journeyExecutor.models.repository.AuditRecordRepository;
import com.looksee.journeyExecutor.models.repository.AuditRepository;
import com.looksee.journeyExecutor.models.repository.ElementStateRepository;
import com.looksee.journeyExecutor.models.repository.PageStateRepository;
import com.looksee.journeyExecutor.models.repository.ScreenshotRepository;

import io.github.resilience4j.retry.annotation.Retry;
import lombok.Synchronized;



/**
 * Service layer object for interacting with {@link PageState} database layer
 *
 */
@Service
@Retry(name="neoforj")
public class PageStateService {
	@SuppressWarnings("unused")
	private static Logger log = LoggerFactory.getLogger(PageStateService.class.getName());
	
	@Autowired
	private PageStateRepository page_state_repo;

	@Autowired
	private ElementStateRepository element_state_repo;
	
	@Autowired
	private ScreenshotRepository screenshot_repo;
	
	@Autowired
	private AuditRepository audit_repo;
	
	@Autowired
	private AuditRecordRepository audit_record_repo;
	
	/**
	 * Save a {@link PageState} object and its associated objects
	 * 
	 * @param page_state
	 * @return
	 * @throws Exception 
	 * 
	 * @pre page_state != null
	 */
	@Retryable
	public PageState save(long domain_map_id, PageState page_state) throws Exception {
		assert page_state != null;
		
		PageState page_state_record = findPageWithKey(domain_map_id, page_state.getKey());
		if(page_state_record == null) {
			return page_state_repo.save(page_state);
		}

		return page_state_record;
	}
	
	public PageState findByKey(String page_key) {
		PageState page_state = page_state_repo.findByKey(page_key);
		if(page_state != null){
			page_state.setElements(getElementStates(page_key));
		}
		return page_state;
	}
	
	public List<PageState> findByScreenshotChecksumAndPageUrl(String user_id, String url, String screenshot_checksum){
		return page_state_repo.findByScreenshotChecksumAndPageUrl(url, screenshot_checksum);
	}
	
	public List<PageState> findByFullPageScreenshotChecksum(String screenshot_checksum){
		return page_state_repo.findByFullPageScreenshotChecksum(screenshot_checksum);		
	}
	
	public PageState findByAnimationImageChecksum(String user_id, String screenshot_checksum){
		return page_state_repo.findByAnimationImageChecksum(user_id, screenshot_checksum);		
	}
	
	public List<ElementState> getElementStates(String page_key){
		assert page_key != null;
		assert !page_key.isEmpty();
		
		return element_state_repo.getElementStates(page_key);
	}
	
	public List<ElementState> getElementStates(long page_state_id){
		return element_state_repo.getElementStates(page_state_id);
	}
	
	public List<ElementState> getLinkElementStates(long page_state_id){
		return element_state_repo.getLinkElementStates(page_state_id);
	}
	
	public List<Screenshot> getScreenshots(String user_id, String page_key){
		List<Screenshot> screenshots = screenshot_repo.getScreenshots(user_id, page_key);
		if(screenshots == null){
			return new ArrayList<Screenshot>();
		}
		return screenshots;
	}
	
	public List<PageState> findPageStatesWithForm(long account_id, String url, String page_key) {
		return page_state_repo.findPageStatesWithForm(account_id, url, page_key);
	}

	public Collection<ElementState> getExpandableElements(List<ElementState> elements) {
		List<ElementState> expandable_elements = new ArrayList<>();
		for(ElementState elem : elements) {
			if(ElementClassification.LEAF.equals(elem.getClassification())) {
				expandable_elements.add(elem);
			}
		}
		return expandable_elements;
	}
	
	public List<PageState> findBySourceChecksumForDomain(String url, String src_checksum) {
		return page_state_repo.findBySourceChecksumForDomain(url, src_checksum);
	}
	
	public List<Audit> getAudits(String page_state_key){
		assert page_state_key != null;
		assert !page_state_key.isEmpty();
		
		return audit_repo.getAudits(page_state_key);
	}

	public Audit findAuditBySubCategory(AuditName subcategory, String page_state_key) {
		return audit_repo.findAuditBySubCategory(subcategory.getShortName(), page_state_key);
	}

	public List<ElementState> getVisibleLeafElements(String page_state_key) {
		return element_state_repo.getVisibleLeafElements(page_state_key);
	}

	public PageState findByUrl(String url) {
		assert url != null;
		assert !url.isEmpty();
		
		return page_state_repo.findByUrl(url);
	}

	@Retryable
	public boolean addElement(long page_id, long element_id) {
		return element_state_repo.addElement(page_id, element_id) != null;
	}

	private Optional<ElementState> getElementState(long page_id, long element_id) {
		return element_state_repo.getElementState(page_id, element_id);
	}

	/**
	 * Retrieves an {@link AuditRecord} for the page with the given id
	 * @param id
	 * @return
	 */
	public PageAuditRecord getAuditRecord(long id) {
		return audit_record_repo.getAuditRecord(id);
	}

	public Optional<PageState> findById(long page_id) {
		return page_state_repo.findById(page_id);
	}

	@Retryable
	public void updateCompositeImageUrl(Long id, String composite_img_url) {
		page_state_repo.updateCompositeImageUrl(id, composite_img_url);
	}

	@Retryable
	public void addAllElements(long page_state_id, List<Long> element_ids) {
		page_state_repo.addAllElements(page_state_id, element_ids);
	}

	public PageState findByDomainAudit(long domainAuditRecordId, long page_state_id) {
		return page_state_repo.findByDomainAudit(domainAuditRecordId, page_state_id);
	}

	public PageState findByDomainAudit(long domainAuditRecordId, String current_url) {
		return page_state_repo.findByDomainAudit(domainAuditRecordId, current_url);
	}

	public long getElementStateCount(long page_state_id) {
		return element_state_repo.getElementStateCount(page_state_id);
	}

	@Retryable
	public PageState findPageWithKey(long audit_record_id, String key) {
		return page_state_repo.findPageWithKey(audit_record_id, key);
	}

	@Retryable
	@Synchronized
	public void updateElementExtractionCompleteStatus(Long page_id, boolean is_complete) throws Exception {
		PageState page = page_state_repo.updateElementExtractionCompleteStatus(page_id, is_complete);
		if(page == null){
			throw new Exception("Page state with id = "+page_id+" was not found");
		}
	}
}
