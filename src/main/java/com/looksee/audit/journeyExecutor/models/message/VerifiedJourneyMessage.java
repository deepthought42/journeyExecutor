package com.looksee.audit.journeyExecutor.models.message;

import com.looksee.audit.journeyExecutor.models.enums.BrowserType;
import com.looksee.audit.journeyExecutor.models.enums.PathStatus;
import com.looksee.journeyExecutor.models.journeys.Journey;

/**
 * 
 */
public class VerifiedJourneyMessage extends Message {

	private long id;
	private Journey journey;
	private PathStatus status;
	private BrowserType browser;
	
	public VerifiedJourneyMessage(long journey_id,
								   Journey journey, 
								   PathStatus status, 
								   BrowserType browser,
								   long domain_id,
								   long account_id, 
								   long audit_record_id){
		setId(journey_id);
		setJourney(journey);
		setStatus(status);
		setBrowser(browser);
		setDomainId(domain_id);
		setAccountId(account_id);
		setDomainAuditRecordId(audit_record_id);
	}
	
	public VerifiedJourneyMessage clone(){
		return new VerifiedJourneyMessage(getId(), 
											journey.clone(), 
											getStatus(), 
											getBrowser(), 
											getDomainId(), 
											getAccountId(), 
											getDomainAuditRecordId());
	}

	public PathStatus getStatus() {
		return status;
	}

	private void setStatus(PathStatus status) {
		this.status = status;
	}

	public BrowserType getBrowser() {
		return browser;
	}

	public void setBrowser(BrowserType browser) {
		this.browser = browser;
	}

	public long getId() {
		return id;
	}

	public void setId(long id) {
		this.id = id;
	}

	public Journey getJourney() {
		return journey;
	}

	public void setJourney(Journey journey) {
		this.journey = journey;
	}
}
