package com.looksee.journeyExecutor.models.repository;


import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.looksee.journeyExecutor.models.journeys.JourneyMap;


@Repository
public interface DomainMapRepository extends Neo4jRepository<JourneyMap, Long>{

	@Query("MATCH (map:DomainMap{key:$key}) RETURN map LIMIT 1")
	public JourneyMap findByKey(@Param("key") String domain_map_key);

	@Query("MATCH (d:Domain) with d WHERE id(d)=$domain_id MATCH (d)-[:CONTAINS]->(map:DomainMap) RETURN map")
	public JourneyMap findByDomainId(@Param("domain_id") long domain_id);

	@Query("MATCH (dm:DomainMap) WITH dm MATCH (journey:journey) WHERE id(dm)=$domain_map_id AND id(journey)=$journey_id MERGE (dm)-[:CONTAINS]->(journey) RETURN dm LIMIT 1")
	public JourneyMap addJourneyToDomainMap(@Param("domain_id") long journey_id, @Param("domain_map_id") long domain_map_id);

}
