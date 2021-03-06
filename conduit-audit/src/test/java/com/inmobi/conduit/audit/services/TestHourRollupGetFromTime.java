package com.inmobi.conduit.audit.services;

import com.inmobi.conduit.audit.util.AuditDBConstants;
import com.inmobi.conduit.audit.util.AuditDBHelper;
import com.inmobi.conduit.audit.util.AuditRollupTestUtil;
import junit.framework.Assert;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.sql.Connection;
import java.util.Calendar;
import java.util.Date;

public class TestHourRollupGetFromTime extends AuditRollupTestUtil {
  private AuditRollUpService rollUpService;

  @BeforeClass
  public void setup() {
    super.setup();
    rollUpService = new HourlyRollupService(config);
    cleanUp();
  }

  private void cleanUp() {
    try {
      FileSystem fs = FileSystem.getLocal(new Configuration());
      fs.delete(
          new Path(config.getString(AuditDBConstants.CHECKPOINT_DIR_KEY)), true);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  @Test
  public void testGetFromTime() {
    Connection connection = getConnection(config);
    Calendar calendar = Calendar.getInstance();
    calendar.setTime(currentDate);
    calendar.set(Calendar.HOUR_OF_DAY, 1);
    calendar.set(Calendar.MINUTE, 5);
    Date fromTime = rollUpService.getRollupTime();
    Assert.assertEquals(AuditDBHelper.getFirstMilliOfDay(calendar.getTime()).longValue(),
        fromTime.getTime());

    calendar.add(Calendar.HOUR_OF_DAY, 1);
    rollUpService.mark(calendar.getTime().getTime());
    fromTime = rollUpService.getRollupTime();
    Assert.assertEquals(calendar.getTime(), fromTime);

    calendar.add(Calendar.HOUR_OF_DAY, -1);
    calendar.add(Calendar.MINUTE, 5);
    rollUpService.mark(calendar.getTime().getTime());
    fromTime = rollUpService.getRollupTime();
    Assert.assertEquals(calendar.getTime(), fromTime);

    calendar.add(Calendar.MINUTE, -5);
    fromTime = rollUpService.getTimeEnrtyDailyTable(connection, true);
    Assert.assertEquals(calendar.getTime(), fromTime);
  }

  @AfterClass
  public void shutDown() {
    super.shutDown();
    cleanUp();
  }
}
