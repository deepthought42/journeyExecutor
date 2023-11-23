package com.looksee.journeyExecutor.models.message;

import com.looksee.journeyExecutor.models.enums.BrowserType;
import com.looksee.journeyExecutor.models.journeys.Journey;

public class DiscardedJourneyMessage extends DomainAuditMessage {

	private Journey journey;
	private BrowserType browserType;
	private long domainId;
   
	public DiscardedJourneyMessage() {}
	
	public DiscardedJourneyMessage(Journey journey, 
								   BrowserType browserType, 
								   long domainId, 
								   long accountId, 
								   long auditRecordId) {
		super(accountId, auditRecordId);
		setJourney(journey);
		setBrowserType(browserType);
		setDomainId(domainId);
	}

	public BrowserType getBrowserType() {
		return browserType;
	}

	public void setBrowserType(BrowserType browserType) {
		this.browserType = browserType;
	}

	public long getDomainId() {
		return domainId;
	}

	public void setDomainId(long domainId) {
		this.domainId = domainId;
	}

	public Journey getJourney() {
		return journey;
	}

	public void setJourney(Journey journey) {
		this.journey = journey;
	}

}
