package com.irishrail.repository;

import com.irishrail.model.Trip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface TripRepository extends JpaRepository<Trip, Long> {

    Optional<Trip> findByTrainCodeAndTrainDate(String trainCode, String trainDate);

    @Modifying
    @Query("DELETE FROM Trip t WHERE t.id NOT IN (SELECT DISTINCT s.trip.id FROM TripStationSnapshot s)")
    int deleteOrphans();
}
