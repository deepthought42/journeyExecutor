package com.looksee.journeyExecutor.models.repository;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.looksee.journeyExecutor.models.journeys.Step;

@Repository
public interface StepRepository extends Neo4jRepository<Step, Long>{

	public Step findByKey(@Param("key") String step_key);

	@Query("MATCH (j:Journey) WITH j MATCH (s:Step) WHERE id(s)=$step_id AND id(j)=$journey_id MERGE (j)-[:HAS]->(s) RETURN s")
	public Step addStep(@Param("journey_id") long journey_id, @Param("step_id") long id);

}
