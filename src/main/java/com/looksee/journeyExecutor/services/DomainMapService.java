package com.looksee.journeyExecutor.services;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.looksee.journeyExecutor.models.journeys.JourneyMap;
import com.looksee.journeyExecutor.models.repository.DomainMapRepository;

import io.github.resilience4j.retry.annotation.Retry;

/**
 * Enables interacting with database for {@link InteractiveStep Steps}
 */
@Service
@Retry(name = "neoforj")
public class DomainMapService {
	@SuppressWarnings("unused")
	private static Logger log = LoggerFactory.getLogger(DomainMapService.class);

	@Autowired
	private DomainMapRepository domain_map_repo;
	
	public JourneyMap findByKey(String journey_map_key) {
		return domain_map_repo.findByKey(journey_map_key);
	}

	public JourneyMap save(JourneyMap domain_map) {
		assert domain_map != null;
		return domain_map_repo.save(domain_map);
	}

	public JourneyMap findByDomainId(long domain_id) {
		return domain_map_repo.findByDomainId(domain_id);
	}

	public void addJourneyToDomainMap(long journey_id, long domain_map_id) {
		domain_map_repo.addJourneyToDomainMap(journey_id, domain_map_id);
		
	}
}
