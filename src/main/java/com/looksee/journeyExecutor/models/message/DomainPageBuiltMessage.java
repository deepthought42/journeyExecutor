package com.looksee.journeyExecutor.models.message;

import lombok.Getter;
import lombok.Setter;

/**
 * Message used to indicate that a domain page has been built and data extracted
 */
public class DomainPageBuiltMessage extends DomainAuditMessage{
	
	@Setter
	@Getter
	private long pageId;
	
	@Setter
	@Getter
	private long pageAuditRecordId;
	
	public DomainPageBuiltMessage() {}
	
	public DomainPageBuiltMessage(long account_id, 
							long domain_audit_id,
							long page_id) 
	{
		super(account_id, domain_audit_id);
		setPageId(page_id);
	}
}
