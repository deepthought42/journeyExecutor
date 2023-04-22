package com.looksee.journeyExecutor.services;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.looksee.journeyExecutor.models.enums.JourneyStatus;
import com.looksee.journeyExecutor.models.journeys.DomainMap;
import com.looksee.journeyExecutor.models.journeys.Journey;
import com.looksee.journeyExecutor.models.repository.JourneyRepository;

@Service
public class JourneyService {
	@SuppressWarnings("unused")
	private static Logger log = LoggerFactory.getLogger(JourneyService.class.getName());

	@Autowired
	private JourneyRepository journey_repo;
	
	public Optional<Journey> findById(long id) {
		return journey_repo.findById(id);
	}
	
	public Journey findByKey(String key) {
		return journey_repo.findByKey(key);
	}
	
	public Journey save(Journey journey) {
		Journey journey_record = journey_repo.findByKey(journey.getKey());
		if(journey_record == null) {
			journey_record = journey_repo.findByCandidateKey(journey.getCandidateKey());
			if(journey_record == null) {
				journey_record = journey_repo.save(journey);
			}
			else {
				journey_record.setKey(journey.getKey());
				journey_record.setOrderedIds(journey.getOrderedIds());
				journey_record.setStatus(journey.getStatus());
				journey_repo.save(journey_record);
			}
		}
		else {
			journey_record.setKey(journey.getKey());
			journey_record.setOrderedIds(journey.getOrderedIds());
			journey_record.setStatus(journey.getStatus());
			journey_repo.save(journey_record);
		}
		
		return journey_record;
	}

	public Journey findByCandidateKey(String candidateKey) {
		return journey_repo.findByCandidateKey(candidateKey);
	}

	public Journey updateFields(long journey_id, JourneyStatus status, String key) {
		return journey_repo.updateFields(journey_id, status, key);
	}

	public Journey addStep(long journey_id, long step_id) {
		return journey_repo.addStep(journey_id, step_id);
	}

	public Journey findByCandidateKey(long domain_map_id, String candidate_key) {
		return journey_repo.findByCandidateKey(domain_map_id, candidate_key);
	}
	
}
