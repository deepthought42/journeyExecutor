package com.looksee.journeyExecutor.models.message;

import com.looksee.journeyExecutor.models.enums.AuditCategory;

import lombok.Getter;
import lombok.Setter;

public class AuditError extends Message{
	@Getter
	@Setter
	private String errorMessage;
	
	@Getter
	@Setter
	private AuditCategory auditCategory;
	
	@Getter
	@Setter
	private double progress;

	@Getter
	@Setter
	private long auditRecordId;
	
	public AuditError(long accountId, 
					  long auditRecordId, 
					  String error_message,
					  AuditCategory category, 
					  double progress, 
					  long domainId
	) {
		super(accountId);
		setErrorMessage(error_message);
		setAuditCategory(category);
		setProgress(progress);
		setAuditRecordId(auditRecordId);
	}
}
