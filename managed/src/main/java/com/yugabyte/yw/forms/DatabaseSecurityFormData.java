// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.forms;

import static play.mvc.Http.Status.BAD_REQUEST;

import com.yugabyte.yw.common.PlatformServiceException;
import com.yugabyte.yw.common.Util;
import org.apache.commons.lang3.StringUtils;

public class DatabaseSecurityFormData {

  public String ycqlAdminUsername;
  public String ycqlCurrAdminPassword;
  public String ycqlAdminPassword;

  public String ysqlAdminUsername;
  public String ysqlCurrAdminPassword;
  public String ysqlAdminPassword;

  public String dbName;

  // TODO(Shashank): Move this to use Validatable
  public void validation() {
    if (StringUtils.isEmpty(ysqlAdminUsername) && StringUtils.isEmpty(ycqlAdminUsername)) {
      throw new PlatformServiceException(BAD_REQUEST, "Need to provide YSQL and/or YCQL username.");
    }

    ysqlAdminUsername = Util.removeEnclosingDoubleQuotes(ysqlAdminUsername);
    ycqlAdminUsername = Util.removeEnclosingDoubleQuotes(ycqlAdminUsername);
    if (!StringUtils.isEmpty(ysqlAdminUsername)) {
      if (dbName == null) {
        throw new PlatformServiceException(
            BAD_REQUEST, "DB needs to be specified for YSQL user change.");
      }

      if (ysqlAdminUsername.contains("\"")) {
        throw new PlatformServiceException(BAD_REQUEST, "Invalid username.");
      }
    }
  }
}
