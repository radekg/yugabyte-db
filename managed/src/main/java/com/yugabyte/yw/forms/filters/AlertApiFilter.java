/*
 * Copyright 2021 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */
package com.yugabyte.yw.forms.filters;

import com.yugabyte.yw.models.Alert;
import com.yugabyte.yw.models.AlertDefinitionGroup;
import com.yugabyte.yw.models.filters.AlertFilter;
import java.util.Set;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.collections.CollectionUtils;

@Data
@NoArgsConstructor
public class AlertApiFilter {
  private Set<UUID> uuids;
  private UUID groupUuid;
  private Set<AlertDefinitionGroup.Severity> severities;
  private Set<AlertDefinitionGroup.TargetType> groupTypes;
  private Set<Alert.State> states;

  public AlertFilter toFilter() {
    AlertFilter.AlertFilterBuilder builder = AlertFilter.builder();
    if (!CollectionUtils.isEmpty(uuids)) {
      builder.uuids(uuids);
    }
    if (groupUuid != null) {
      builder.groupUuid(groupUuid);
    }
    if (!CollectionUtils.isEmpty(severities)) {
      builder.severities(severities);
    }
    if (!CollectionUtils.isEmpty(groupTypes)) {
      builder.groupTypes(groupTypes);
    }
    if (!CollectionUtils.isEmpty(states)) {
      builder.states(states);
    }
    return builder.build();
  }
}
