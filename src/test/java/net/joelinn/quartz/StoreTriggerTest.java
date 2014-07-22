package net.joelinn.quartz;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.joelinn.quartz.jobstore.RedisJobStoreSchema;
import net.joelinn.quartz.jobstore.RedisStorage;
import net.joelinn.quartz.jobstore.RedisTriggerState;
import org.junit.Test;
import org.quartz.*;
import org.quartz.impl.calendar.WeeklyCalendar;
import org.quartz.impl.matchers.GroupMatcher;
import org.quartz.impl.triggers.CronTriggerImpl;
import org.quartz.spi.OperableTrigger;
import org.quartz.spi.SchedulerSignaler;
import org.quartz.spi.TriggerFiredResult;

import java.util.*;

import static junit.framework.Assert.assertEquals;
import static junit.framework.TestCase.*;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.hamcrest.collection.IsMapContaining.hasKey;
import static org.mockito.Mockito.mock;

/**
 * Joe Linn
 * 7/15/2014
 */
public class StoreTriggerTest extends BaseTest{

    @Test
    public void testStoreTrigger(){
        CronTriggerImpl trigger = getCronTrigger();
        try {
            jobStore.storeTrigger(trigger, false);
        } catch (JobPersistenceException e) {
            e.printStackTrace();
            fail();
        }

        final String triggerHashKey = schema.triggerHashKey(trigger.getKey());
        Map<String, String> triggerMap = jedis.hgetAll(triggerHashKey);
        assertThat(triggerMap, hasKey("description"));
        assertEquals(trigger.getDescription(), triggerMap.get("description"));
        assertThat(triggerMap, hasKey("trigger_class"));
        assertEquals(trigger.getClass().getName(), triggerMap.get("trigger_class"));

        assertTrue("The trigger hash key is not a member of the triggers set.", jedis.sismember(schema.triggersSet(), triggerHashKey));
        assertTrue("The trigger group set key is not a member of the trigger group set.", jedis.sismember(schema.triggerGroupsSet(), schema.triggerGroupSetKey(trigger.getKey())));
        assertTrue(jedis.sismember(schema.triggerGroupSetKey(trigger.getKey()), triggerHashKey));
        assertTrue(jedis.sismember(schema.jobTriggerSetKey(trigger.getJobKey()), triggerHashKey));
    }

    //TODO: test storing trigger with calendar

    @Test(expected = JobPersistenceException.class)
    public void testStoreTriggerNoReplace() throws JobPersistenceException {
        jobStore.storeTrigger(getCronTrigger(), false);
        jobStore.storeTrigger(getCronTrigger(), false);
    }

    @Test
    public void testStoreTriggerWithReplace(){
        try {
            jobStore.storeTrigger(getCronTrigger(), true);
            jobStore.storeTrigger(getCronTrigger(), true);
        } catch (JobPersistenceException e) {
            e.printStackTrace();
            fail();
        }
    }

    @Test
    public void testRetrieveTrigger(){
        CronTriggerImpl cronTrigger = getCronTrigger();
        try {
            jobStore.storeJob(getJobDetail(), false);
            jobStore.storeTrigger(cronTrigger, false);
        } catch (JobPersistenceException e) {
            e.printStackTrace();
            fail();
        }

        OperableTrigger operableTrigger = null;
        try {
            operableTrigger = jobStore.retrieveTrigger(cronTrigger.getKey());
        } catch (JobPersistenceException e) {
            e.printStackTrace();
            fail();
        }
        assertThat(operableTrigger, instanceOf(CronTriggerImpl.class));
        CronTriggerImpl retrievedTrigger = (CronTriggerImpl) operableTrigger;

        assertEquals(cronTrigger.getCronExpression(), retrievedTrigger.getCronExpression());
        assertEquals(cronTrigger.getTimeZone(), retrievedTrigger.getTimeZone());
        assertEquals(cronTrigger.getStartTime(), retrievedTrigger.getStartTime());
    }

    @Test
    public void testRemoveTrigger() throws JobPersistenceException {
        JobDetail job = getJobDetail();
        CronTriggerImpl trigger1 = getCronTrigger("trigger1", "triggerGroup", job.getKey());
        CronTriggerImpl trigger2 = getCronTrigger("trigger2", "triggerGroup", job.getKey());

        jobStore.storeJob(job, false);
        jobStore.storeTrigger(trigger1, false);
        jobStore.storeTrigger(trigger2, false);

        jobStore.removeTrigger(trigger1.getKey());

        // ensure that the trigger was removed, but the job was not
        assertThat(jobStore.retrieveTrigger(trigger1.getKey()), nullValue());
        assertThat(jobStore.retrieveJob(job.getKey()), not(nullValue()));

        // remove the second trigger
        jobStore.removeTrigger(trigger2.getKey());

        //  ensure that both the trigger and job were removed
        assertThat(jobStore.retrieveTrigger(trigger2.getKey()), nullValue());
        assertThat(jobStore.retrieveJob(job.getKey()), nullValue());
    }

    @Test
    public void testGetTriggersForJob(){
        JobDetail job = getJobDetail();
        CronTriggerImpl trigger1 = getCronTrigger("trigger1", "triggerGroup", job.getKey());
        CronTriggerImpl trigger2 = getCronTrigger("trigger2", "triggerGroup", job.getKey());
        try {
            jobStore.storeJob(job, false);
            jobStore.storeTrigger(trigger1, false);
            jobStore.storeTrigger(trigger2, false);
        } catch (JobPersistenceException e) {
            e.printStackTrace();
            fail();
        }

        try {
            List<OperableTrigger> triggers = jobStore.getTriggersForJob(job.getKey());
            assertThat(triggers, hasSize(2));
        } catch (JobPersistenceException e) {
            e.printStackTrace();
            fail();
        }
    }

    @Test
    public void testGetNumberOfTriggers() throws JobPersistenceException {
        JobDetail job = getJobDetail();
        jobStore.storeTrigger(getCronTrigger("trigger1", "group1", job.getKey()), false);
        jobStore.storeTrigger(getCronTrigger("trigger2", "group1", job.getKey()), false);
        jobStore.storeTrigger(getCronTrigger("trigger3", "group2", job.getKey()), false);
        jobStore.storeTrigger(getCronTrigger("trigger4", "group3", job.getKey()), false);

        int numberOfTriggers = jobStore.getNumberOfTriggers();

        assertEquals(4, numberOfTriggers);
    }

    @Test
    public void testGetTriggerKeys() throws JobPersistenceException {
        JobDetail job = getJobDetail();
        jobStore.storeTrigger(getCronTrigger("trigger1", "group1", job.getKey()), false);
        jobStore.storeTrigger(getCronTrigger("trigger2", "group1", job.getKey()), false);
        jobStore.storeTrigger(getCronTrigger("trigger3", "group2", job.getKey()), false);
        jobStore.storeTrigger(getCronTrigger("trigger4", "group3", job.getKey()), false);

        Set<TriggerKey> triggerKeys = jobStore.getTriggerKeys(GroupMatcher.triggerGroupEquals("group1"));

        assertThat(triggerKeys, hasSize(2));
        assertThat(triggerKeys, contains(new TriggerKey("trigger2", "group1"), new TriggerKey("trigger1", "group1")));
    }

    @Test
    public void testGetTriggerGroupNames() throws JobPersistenceException {
        List<String> triggerGroupNames = jobStore.getTriggerGroupNames();

        assertThat(triggerGroupNames, not(nullValue()));
        assertThat(triggerGroupNames, hasSize(0));

        JobDetail job = getJobDetail();
        jobStore.storeTrigger(getCronTrigger("trigger1", "group1", job.getKey()), false);
        jobStore.storeTrigger(getCronTrigger("trigger2", "group1", job.getKey()), false);
        jobStore.storeTrigger(getCronTrigger("trigger3", "group2", job.getKey()), false);
        jobStore.storeTrigger(getCronTrigger("trigger4", "group3", job.getKey()), false);

        triggerGroupNames = jobStore.getTriggerGroupNames();

        assertThat(triggerGroupNames, hasSize(3));
        assertThat(triggerGroupNames, contains("group3", "group2", "group1"));
    }

    @Test
    public void testGetTriggerState() throws JobPersistenceException {
        SchedulerSignaler signaler = mock(SchedulerSignaler.class);
        RedisStorage storageDriver = new RedisStorage(new RedisJobStoreSchema(), new ObjectMapper(), signaler, "scheduler1", 2000);

        // attempt to retrieve the state of a non-existent trigger
        Trigger.TriggerState state = jobStore.getTriggerState(new TriggerKey("foobar"));
        assertEquals(Trigger.TriggerState.NONE, state);

        // store a trigger
        JobDetail job = getJobDetail();
        CronTriggerImpl cronTrigger = getCronTrigger("trigger1", "group1", job.getKey());
        jobStore.storeTrigger(cronTrigger, false);

        // the newly-stored trigger's state should be NONE
        state = jobStore.getTriggerState(cronTrigger.getKey());
        assertEquals(Trigger.TriggerState.NORMAL, state);

        // set the trigger's state
        storageDriver.setTriggerState(RedisTriggerState.WAITING, 500, schema.triggerHashKey(cronTrigger.getKey()), jedis);

        // the trigger's state should now be NORMAL
        state = jobStore.getTriggerState(cronTrigger.getKey());
        assertEquals(Trigger.TriggerState.NORMAL, state);
    }

    @Test
    public void testPauseTrigger() throws JobPersistenceException {
        SchedulerSignaler signaler = mock(SchedulerSignaler.class);
        RedisStorage storageDriver = new RedisStorage(new RedisJobStoreSchema(), new ObjectMapper(), signaler, "scheduler1", 2000);

        // store a trigger
        JobDetail job = getJobDetail();
        CronTriggerImpl cronTrigger = getCronTrigger("trigger1", "group1", job.getKey());
        cronTrigger.setNextFireTime(new Date(System.currentTimeMillis()));
        jobStore.storeTrigger(cronTrigger, false);

        // set the trigger's state to COMPLETED
        storageDriver.setTriggerState(RedisTriggerState.COMPLETED, 500, schema.triggerHashKey(cronTrigger.getKey()), jedis);
        jobStore.pauseTrigger(cronTrigger.getKey());

        // trigger's state should not have changed
        assertEquals(Trigger.TriggerState.COMPLETE, jobStore.getTriggerState(cronTrigger.getKey()));

        // set the trigger's state to BLOCKED
        storageDriver.setTriggerState(RedisTriggerState.BLOCKED, 500, schema.triggerHashKey(cronTrigger.getKey()), jedis);
        jobStore.pauseTrigger(cronTrigger.getKey());

        // trigger's state should be PAUSED
        assertEquals(Trigger.TriggerState.PAUSED, jobStore.getTriggerState(cronTrigger.getKey()));

        // set the trigger's state to ACQUIRED
        storageDriver.setTriggerState(RedisTriggerState.ACQUIRED, 500, schema.triggerHashKey(cronTrigger.getKey()), jedis);
        jobStore.pauseTrigger(cronTrigger.getKey());

        // trigger's state should be PAUSED
        assertEquals(Trigger.TriggerState.PAUSED, jobStore.getTriggerState(cronTrigger.getKey()));
    }

    @Test
    public void testPauseTriggers() throws JobPersistenceException {
        // store triggers
        JobDetail job = getJobDetail();
        jobStore.storeTrigger(getCronTrigger("trigger1", "group1", job.getKey()), false);
        jobStore.storeTrigger(getCronTrigger("trigger2", "group1", job.getKey()), false);
        jobStore.storeTrigger(getCronTrigger("trigger3", "group2", job.getKey()), false);
        jobStore.storeTrigger(getCronTrigger("trigger4", "group3", job.getKey()), false);

        // pause triggers
        Collection<String> pausedGroups = jobStore.pauseTriggers(GroupMatcher.triggerGroupEquals("group1"));

        assertThat(pausedGroups, hasSize(1));
        assertThat(pausedGroups, contains("group1"));

        // ensure that the triggers were actually paused
        assertEquals(Trigger.TriggerState.PAUSED, jobStore.getTriggerState(new TriggerKey("trigger1", "group1")));
        assertEquals(Trigger.TriggerState.PAUSED, jobStore.getTriggerState(new TriggerKey("trigger2", "group1")));
    }

    @Test
    public void testResumeTrigger() throws JobPersistenceException {
        // create and store a job and trigger
        JobDetail job = getJobDetail();
        jobStore.storeJob(job, false);
        CronTriggerImpl trigger = getCronTrigger("trigger1", "group1", job.getKey());
        trigger.computeFirstFireTime(new WeeklyCalendar());
        jobStore.storeTrigger(trigger, false);

        // pause the trigger
        jobStore.pauseTrigger(trigger.getKey());
        assertEquals(Trigger.TriggerState.PAUSED, jobStore.getTriggerState(trigger.getKey()));

        // resume the trigger
        jobStore.resumeTrigger(trigger.getKey());
        // the trigger state should now be NORMAL
        assertEquals(Trigger.TriggerState.NORMAL, jobStore.getTriggerState(trigger.getKey()));

        // attempt to resume the trigger, again
        jobStore.resumeTrigger(trigger.getKey());
        // the trigger state should not have changed
        assertEquals(Trigger.TriggerState.NORMAL, jobStore.getTriggerState(trigger.getKey()));
    }

    @Test
    public void testResumeTriggers() throws JobPersistenceException {
        // store triggers and job
        JobDetail job = getJobDetail();
        jobStore.storeJob(job, false);
        CronTriggerImpl trigger1 = getCronTrigger("trigger1", "group1", job.getKey());
        trigger1.computeFirstFireTime(new WeeklyCalendar());
        jobStore.storeTrigger(trigger1, false);
        CronTriggerImpl trigger2 = getCronTrigger("trigger2", "group1", job.getKey());
        trigger2.computeFirstFireTime(new WeeklyCalendar());
        jobStore.storeTrigger(trigger2, false);
        jobStore.storeTrigger(getCronTrigger("trigger3", "group2", job.getKey()), false);
        jobStore.storeTrigger(getCronTrigger("trigger4", "group3", job.getKey()), false);

        // pause triggers
        Collection<String> pausedGroups = jobStore.pauseTriggers(GroupMatcher.triggerGroupEquals("group1"));

        assertThat(pausedGroups, hasSize(1));
        assertThat(pausedGroups, contains("group1"));

        // ensure that the triggers were actually paused
        assertEquals(Trigger.TriggerState.PAUSED, jobStore.getTriggerState(new TriggerKey("trigger1", "group1")));
        assertEquals(Trigger.TriggerState.PAUSED, jobStore.getTriggerState(new TriggerKey("trigger2", "group1")));

        // resume triggers
        Collection<String> resumedGroups = jobStore.resumeTriggers(GroupMatcher.triggerGroupEquals("group1"));

        assertThat(resumedGroups, hasSize(1));
        assertThat(resumedGroups, contains("group1"));

        // ensure that the triggers were resumed
        assertEquals(Trigger.TriggerState.NORMAL, jobStore.getTriggerState(new TriggerKey("trigger1", "group1")));
        assertEquals(Trigger.TriggerState.NORMAL, jobStore.getTriggerState(new TriggerKey("trigger2", "group1")));
    }

    @Test
    public void testGetPausedTriggerGroups() throws JobPersistenceException {
        // store triggers
        JobDetail job = getJobDetail();
        jobStore.storeTrigger(getCronTrigger("trigger1", "group1", job.getKey()), false);
        jobStore.storeTrigger(getCronTrigger("trigger2", "group1", job.getKey()), false);
        jobStore.storeTrigger(getCronTrigger("trigger3", "group2", job.getKey()), false);
        jobStore.storeTrigger(getCronTrigger("trigger4", "group3", job.getKey()), false);

        // pause triggers
        Collection<String> pausedGroups = jobStore.pauseTriggers(GroupMatcher.triggerGroupEquals("group1"));
        pausedGroups.addAll(jobStore.pauseTriggers(GroupMatcher.triggerGroupEquals("group3")));

        assertThat(pausedGroups, hasSize(2));
        assertThat(pausedGroups, contains("group3", "group1"));

        // retrieve paused trigger groups
        Set<String> pausedTriggerGroups = jobStore.getPausedTriggerGroups();
        assertThat(pausedTriggerGroups, hasSize(2));
        assertThat(pausedTriggerGroups, contains("group1", "group3"));
    }

    @Test
    public void testPauseAndResumeAll() throws JobPersistenceException {
        // store some jobs with triggers
        Map<JobDetail, Set<? extends Trigger>> jobsAndTriggers = getJobsAndTriggers(2, 2, 2, 2);
        jobStore.storeJobsAndTriggers(jobsAndTriggers, false);

        // ensure that all triggers are in the NORMAL state
        for (Map.Entry<JobDetail, Set<? extends Trigger>> jobDetailSetEntry : jobsAndTriggers.entrySet()) {
            for (Trigger trigger : jobDetailSetEntry.getValue()) {
                assertEquals(Trigger.TriggerState.NORMAL, jobStore.getTriggerState(trigger.getKey()));
            }
        }

        jobStore.pauseAll();

        // ensure that all triggers were paused
        for (Map.Entry<JobDetail, Set<? extends Trigger>> jobDetailSetEntry : jobsAndTriggers.entrySet()) {
            for (Trigger trigger : jobDetailSetEntry.getValue()) {
                assertEquals(Trigger.TriggerState.PAUSED, jobStore.getTriggerState(trigger.getKey()));
            }
        }

        // resume all triggers
        jobStore.resumeAll();

        // ensure that all triggers are again in the NORMAL state
        for (Map.Entry<JobDetail, Set<? extends Trigger>> jobDetailSetEntry : jobsAndTriggers.entrySet()) {
            for (Trigger trigger : jobDetailSetEntry.getValue()) {
                assertEquals(Trigger.TriggerState.NORMAL, jobStore.getTriggerState(trigger.getKey()));
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testTriggersFired() throws JobPersistenceException {
        // store some jobs with triggers
        Map<JobDetail, Set<? extends Trigger>> jobsAndTriggers = getJobsAndTriggers(2, 2, 2, 2, "* * * * * ?");
        jobStore.storeCalendar("testCalendar", new WeeklyCalendar(), false, true);
        jobStore.storeJobsAndTriggers(jobsAndTriggers, false);

        List<OperableTrigger> acquiredTriggers = jobStore.acquireNextTriggers(System.currentTimeMillis() - 1000, 500, 4000);
        assertThat(acquiredTriggers, hasSize(16));

        // ensure that all triggers are in the NORMAL state and have been ACQUIRED
        for (Map.Entry<JobDetail, Set<? extends Trigger>> jobDetailSetEntry : jobsAndTriggers.entrySet()) {
            for (Trigger trigger : jobDetailSetEntry.getValue()) {
                assertEquals(Trigger.TriggerState.NORMAL, jobStore.getTriggerState(trigger.getKey()));
                String triggerHashKey = schema.triggerHashKey(trigger.getKey());
                assertThat(jedis.zscore(schema.triggerStateKey(RedisTriggerState.ACQUIRED), triggerHashKey), not(nullValue()));
            }
        }

        Set<? extends OperableTrigger> triggers = (Set<? extends  OperableTrigger>) new ArrayList<>(jobsAndTriggers.entrySet()).get(0).getValue();
        List<TriggerFiredResult> triggerFiredResults = jobStore.triggersFired(new ArrayList<>(triggers));
        assertThat(triggerFiredResults, hasSize(4));
    }

    @Test
    public void testReplaceTrigger() throws JobPersistenceException {
        assertFalse(jobStore.replaceTrigger(TriggerKey.triggerKey("foo", "bar"), getCronTrigger()));

        // store triggers and job
        JobDetail job = getJobDetail();
        jobStore.storeJob(job, false);
        CronTriggerImpl trigger1 = getCronTrigger("trigger1", "group1", job.getKey());
        jobStore.storeTrigger(trigger1, false);
        CronTriggerImpl trigger2 = getCronTrigger("trigger2", "group1", job.getKey());
        jobStore.storeTrigger(trigger2, false);

        CronTriggerImpl newTrigger = getCronTrigger("newTrigger", "group1", job.getKey());

        assertTrue(jobStore.replaceTrigger(trigger1.getKey(), newTrigger));

        // ensure that the proper trigger was replaced
        assertThat(jobStore.retrieveTrigger(trigger1.getKey()), nullValue());

        List<OperableTrigger> jobTriggers = jobStore.getTriggersForJob(job.getKey());

        assertThat(jobTriggers, hasSize(2));
        List<TriggerKey> jobTriggerKeys = new ArrayList<>(jobTriggers.size());
        for (OperableTrigger jobTrigger : jobTriggers) {
            jobTriggerKeys.add(jobTrigger.getKey());
        }

        assertThat(jobTriggerKeys, contains(trigger2.getKey(), newTrigger.getKey()));
    }

    @Test(expected = JobPersistenceException.class)
    public void testReplaceTriggerWithDifferentJob() throws JobPersistenceException {
        // store triggers and job
        JobDetail job = getJobDetail();
        jobStore.storeJob(job, false);
        CronTriggerImpl trigger1 = getCronTrigger("trigger1", "group1", job.getKey());
        jobStore.storeTrigger(trigger1, false);
        CronTriggerImpl trigger2 = getCronTrigger("trigger2", "group1", job.getKey());
        jobStore.storeTrigger(trigger2, false);

        CronTriggerImpl newTrigger = getCronTrigger("newTrigger", "group1", JobKey.jobKey("foo", "bar"));

        jobStore.replaceTrigger(trigger1.getKey(), newTrigger);
    }
}
