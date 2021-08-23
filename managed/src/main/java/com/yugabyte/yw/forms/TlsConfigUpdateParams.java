// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.forms;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonDeserialize(converter = TlsConfigUpdateParams.Converter.class)
public class TlsConfigUpdateParams extends UpgradeTaskParams {

  public Boolean enableNodeToNodeEncrypt = false;
  public Boolean enableClientToNodeEncrypt = false;
  public UUID rootCA = null;
  public UUID clientRootCA = null;
  public Boolean rootAndClientRootCASame = null;

  public TlsConfigUpdateParams() {}

  public static class Converter extends BaseConverter<TlsConfigUpdateParams> {}
}
