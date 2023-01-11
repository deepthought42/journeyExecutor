package com.looksee.journeyExecutor.models.message;

public class PageBuiltMessage extends Message{
	private long pageId;
	private long pageAuditId;
	
	public PageBuiltMessage() {
		super(-1, -1, -1);
	}
	
	public PageBuiltMessage(long account_id, 
							long domain_audit_id,
							long domain_id,
							long page_id, 
							long page_audit_id) 
	{
		super(account_id, domain_audit_id, domain_id);
		setPageId(page_id);
		setPageAuditId(page_audit_id);
	}
	
	public long getPageId() {
		return pageId;
	}
	public void setPageId(long page_id) {
		this.pageId = page_id;
	}

	public long getPageAuditId() {
		return pageAuditId;
	}

	public void setPageAuditId(long page_audit_id) {
		this.pageAuditId = page_audit_id;
	}
}
