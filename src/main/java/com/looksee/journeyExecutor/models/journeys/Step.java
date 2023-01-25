package com.looksee.journeyExecutor.models.journeys;

import org.springframework.data.neo4j.core.schema.Relationship;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.looksee.journeyExecutor.models.LookseeObject;
import com.looksee.journeyExecutor.models.PageState;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT, property="type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = SimpleStep.class, name = "simpleStep"),
    @JsonSubTypes.Type(value = LoginStep.class, name = "loginStep") 
})
public abstract class Step extends LookseeObject {
	
	@Relationship(type = "STARTS_WITH")
	private PageState startPage;
	
	@Relationship(type = "ENDS_WITH")
	private PageState endPage;
	
	public PageState getStartPage() {
		return startPage;
	}
	
	public void setStartPage(PageState page_state) {
		this.startPage = page_state;
	}
	
	
	public PageState getEndPage() {
		return this.endPage;
	}
	
	public void setEndPage(PageState page_state) {
		this.endPage = page_state;
	}
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public String toString(){
		return "{ start = "+this.startPage+" ;     end = "+this.endPage+ " ;  key : "+this.getKey() + " }";
	}
	
	@Override
	public String generateKey() {
		String key = "";
		if(startPage != null) {
			key += startPage.getId();
		}
		if(endPage != null) {
			key += endPage.getId();
		}
		return "step"+key;
	}
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public abstract Step clone();
}
