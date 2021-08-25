// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.forms;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Date;
import java.util.UUID;

@ApiModel(value = "CustomerTaskData", description = "Customer task data")
public class CustomerTaskFormData {

  @ApiModelProperty(value = "Customer task UUID")
  public UUID id;

  @ApiModelProperty(value = "Customer task title", example = "Deleted Universe : test-universe")
  public String title;

  @ApiModelProperty(value = "Customer task percentage completed", example = "100")
  public int percentComplete;

  @ApiModelProperty(value = "Customer task creation time", example = "1624295417405")
  public Date createTime;

  @ApiModelProperty(value = "Customer task completion time", example = "1624295417405")
  public Date completionTime;

  @ApiModelProperty(value = "Customer task target", example = "Universe")
  public String target;

  @ApiModelProperty(value = "Customer task target UUID")
  public UUID targetUUID;

  @ApiModelProperty(value = "Customer task type", example = "Delete")
  public String type;

  @ApiModelProperty(value = "Customer task status", example = "Complete")
  public String status;

  @ApiModelProperty(value = "Customer Task details", example = "2.4.3.0 => 2.7.1.1")
  public JsonNode details;
}
