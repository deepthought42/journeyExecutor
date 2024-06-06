package com.looksee.journeyExecutor.services;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import com.looksee.journeyExecutor.models.PageState;
import com.looksee.journeyExecutor.models.enums.JourneyStatus;
import com.looksee.journeyExecutor.models.journeys.Journey;
import com.looksee.journeyExecutor.models.repository.JourneyRepository;

import lombok.Synchronized;

@Service
public class JourneyService {
	@SuppressWarnings("unused")
	private static Logger log = LoggerFactory.getLogger(JourneyService.class.getName());

	@Autowired
	private JourneyRepository journey_repo;
	
	public Optional<Journey> findById(long id) {
		return journey_repo.findById(id);
	}
	
	public Journey findByKey(long domain_map_id, String key) {
		return journey_repo.findByKey(domain_map_id, key);
	}
	
	/**
	 * 
	 * @param journey
	 * @return
	 */
	@Retryable
	public Journey save(long domain_map_id, Journey journey) {
		Journey journey_record = journey_repo.findByKey(domain_map_id, journey.getKey());
		if(journey_record == null) {
			journey_record = journey_repo.findByCandidateKey(domain_map_id, journey.getCandidateKey());
			if(journey_record == null) {
				journey_record = journey_repo.save(journey);
			}
			/*
			else {
				journey_record.setKey(journey.getKey());
				journey_record.setOrderedIds(journey.getOrderedIds());
				journey_record.setStatus(journey.getStatus());
				journey_repo.save(journey_record);
			}
			*/
		}
		/*
		else {
			journey_record.setKey(journey.getKey());
			journey_record.setOrderedIds(journey.getOrderedIds());
			journey_record.setStatus(journey.getStatus());
			journey_repo.save(journey_record);
		}
		*/
		
		return journey_record;
	}

	@Retryable
	public Journey updateFields(long journey_id, JourneyStatus status, String key, List<Long> ordered_ids) {
		return journey_repo.updateFields(journey_id, status.toString(), key, ordered_ids);
	}

	@Retryable
	public Journey addStep(long journey_id, long step_id) {
		return journey_repo.addStep(journey_id, step_id);
	}

	public Journey findByCandidateKey(long domain_map_id, String candidate_key) {
		return journey_repo.findByCandidateKey(domain_map_id, candidate_key);
	}

	@Synchronized
	public Journey updateStatus(long journey_id, JourneyStatus status) {
		return journey_repo.updateStatus(journey_id, status.toString());
	}

    public PageState executeJourney(Journey journey, long domain_audit_id) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'executeJourney'");
    }
	
}
