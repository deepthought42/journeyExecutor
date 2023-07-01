package com.looksee.journeyExecutor.models.repository;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.looksee.journeyExecutor.models.journeys.Step;

@Repository
public interface StepRepository extends Neo4jRepository<Step, Long>{

	public Step findByKey(@Param("key") String step_key);

	@Query("MATCH (step:Step) WITH step WHERE id(step)=$step_id MATCH (page:PageState) WHERE id(page)=$page_id MERGE (step)-[:ENDS_WITH]->(page) RETURN step")
	public Step addEndPage(@Param("step_id") long step_id, @Param("page_id") long page_id);

	@Query("MATCH (step:Step) WHERE id(step)=$step_id SET step.key=$step_key RETURN step")
	public Step updateKey(@Param("step_id") long step_id, @Param("step_key") String key);

}
