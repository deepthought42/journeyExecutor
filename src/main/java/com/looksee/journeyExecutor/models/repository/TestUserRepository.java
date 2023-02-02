package com.looksee.journeyExecutor.models.repository;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;

import com.looksee.journeyExecutor.models.TestUser;

/**
 * 
 */
public interface TestUserRepository extends Neo4jRepository<TestUser, Long> {
	
	@Query("MATCH (s:LoginStep) WITH s MATCH (user:TestUser) WHERE id(s)=$step_id AND id(user)=$user_id MERGE (s)-[:USES]->(user) RETURN user")
	public TestUser addToLoginStep(@Param("step_id") long id, @Param("user_id") long user_id);

	@Query("MATCH (s:LoginStep)-[:USES]->(user:TestUser) WHERE id(s)=$step_id RETURN user")
	public TestUser getTestUserForStep(@Param("step_id") long id);
}
