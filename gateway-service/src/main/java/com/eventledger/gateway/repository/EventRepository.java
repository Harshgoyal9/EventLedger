package com.eventledger.gateway.repository;

import com.eventledger.gateway.entity.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EventRepository extends JpaRepository<Event, String> {

    @Query("SELECT e FROM Event e WHERE e.accountId = :accountId ORDER BY e.eventTimestamp ASC")
    List<Event> findByAccountIdOrderByEventTimestampAsc(@Param("accountId") String accountId);

    boolean existsByEventId(String eventId);
}
