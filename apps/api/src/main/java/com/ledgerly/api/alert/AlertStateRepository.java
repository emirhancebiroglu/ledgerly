package com.ledgerly.api.alert;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertStateRepository extends JpaRepository<AlertState, AlertStateId> {

  Optional<AlertState> findByAlertIdAndUserId(UUID alertId, UUID userId);

  List<AlertState> findByUserIdAndAlertIdIn(UUID userId, List<UUID> alertIds);

  List<AlertState> findByUserId(UUID userId);
}
