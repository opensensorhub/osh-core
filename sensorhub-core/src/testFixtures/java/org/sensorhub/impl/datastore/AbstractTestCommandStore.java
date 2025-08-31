/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.

******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.datastore;

import static org.junit.Assert.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.datastore.command.CommandStreamFilter;
import org.sensorhub.api.datastore.command.CommandStreamKey;
import org.sensorhub.api.datastore.command.CommandFilter;
import org.sensorhub.api.datastore.command.CommandStatusFilter;
import org.sensorhub.api.datastore.command.ICommandStore;
import org.sensorhub.api.feature.FeatureId;
import org.sensorhub.api.command.CommandStreamInfo;
import org.sensorhub.api.command.ICommandStreamInfo;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.command.ICommandStatus.CommandStatusCode;
import org.sensorhub.api.command.ICommandData;
import org.sensorhub.api.command.ICommandStatus;
import org.sensorhub.api.command.CommandStatus;
import org.sensorhub.api.command.CommandData;
import org.vast.data.DataBlockDouble;
import org.vast.data.TextEncodingImpl;
import org.vast.swe.SWEHelper;
import org.vast.util.TimeExtent;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;
import com.google.common.collect.Maps;
import net.opengis.swe.v20.DataComponent;


/**
 * <p>
 * Abstract base for testing implementations of ICommandStore.
 * </p>
 *
 * @author Alex Robin
 * @param <StoreType> Type of store under test
 * @since Mar 26, 2021
 */
public abstract class AbstractTestCommandStore<StoreType extends ICommandStore>
{
    protected static int DATABASE_NUM = 5;
    protected static String CMD_DATASTORE_NAME = "test-cmd";
    protected static String PROC_DATASTORE_NAME = "test-proc";
    protected static String PROC_UID_PREFIX = "urn:osh:test:sensor:";
    protected static String FOI_UID_PREFIX = "urn:osh:test:foi:";
    
    protected StoreType cmdStore;
    protected Map<CommandStreamKey, ICommandStreamInfo> allCommandStreams = new LinkedHashMap<>();
    protected Map<BigId, ICommandData> allCommands = new LinkedHashMap<>();
    protected Map<BigId, Map<BigId, ICommandStatus>> allStatus = new LinkedHashMap<>();


    protected abstract StoreType initStore() throws Exception;
    protected abstract void forceReadBackFromStorage() throws Exception;


    @Before
    public void init() throws Exception
    {
        this.cmdStore = initStore();

    }


    protected CommandStreamKey addCommandStream(BigId sysID, DataComponent recordStruct)
    {
        try
        {
            var csInfo = new CommandStreamInfo.Builder()
                .withName(recordStruct.getName())
                .withSystem(new FeatureId(sysID, PROC_UID_PREFIX+sysID))
                .withRecordDescription(recordStruct)
                .withRecordEncoding(new TextEncodingImpl())
                .build();
            
            var csID = cmdStore.getCommandStreams().add(csInfo);
            allCommandStreams.put(csID, csInfo);
            return csID;
        }
        catch (DataStoreException e)
        {
            throw new IllegalStateException(e);
        }
    }


    protected CommandStreamKey addSimpleCommandStream(BigId sysID, String outputName)
    {
        SWEHelper fac = new SWEHelper();
        var builder = fac.createRecord()
            .name(outputName);
        for (int i=0; i<5; i++)
            builder.addField("comp"+i, fac.createQuantity().build());
        return addCommandStream(sysID, builder.build());
    }


    protected BigId addCommand(ICommandData cmd)
    {
        BigId key = cmdStore.add(cmd);
        allCommands.put(key, cmd);
        return key;
    }


    protected BigId addStatus(ICommandStatus status)
    {
        BigId key = cmdStore.getStatusReports().add(status);
        
        allStatus.compute(status.getCommandID(), (k, v) -> {
            if (v == null)
                 v = new LinkedHashMap<>();
            v.put(key, status);
            return v;
        });
        
        return key;
    }


    protected void addCommands(BigId csID, Instant startTime, int numObs) throws Exception
    {
        addCommands(csID, startTime, numObs, 60000, null);
    }
    
    
    protected Map<BigId, ICommandData> addCommands(BigId csID, Instant startTime, int numCmds, long timeStepMillis) throws Exception
    {
        return addCommands(csID, startTime, numCmds, timeStepMillis, null);
    }
    
    
    protected Map<BigId, ICommandData> addCommands(BigId csID, Instant startTime, int numCmds, long timeStepMillis, CommandStatusCode statusCode) throws Exception
    {
        Map<BigId, ICommandData> addedCmds = new LinkedHashMap<>();
        
        for (int i = 0; i < numCmds; i++)
        {
            DataBlockDouble data = new DataBlockDouble(5);
            for (int s=0; s<5; s++)
                data.setDoubleValue(s, i+s);

            var ts = startTime.plusMillis(timeStepMillis*i);
            var cmd = new CommandData.Builder()
                .withCommandStream(csID)
                .withIssueTime(ts)
                .withParams(data)
                .withSender("test" + i)
                .build();

            BigId cmdKey = addCommand(cmd);
            addedCmds.put(cmdKey, cmd);
            
            if (statusCode != null)
            {
                ICommandStatus status;
                switch (statusCode)
                {
                    case COMPLETED:
                        status = CommandStatus.completed(cmdKey, TimeExtent.instant(ts));
                        break;
                        
                    case FAILED:
                        status = CommandStatus.failed(cmdKey, "Task failed");
                        break;
                    
                    case ACCEPTED:
                        status = CommandStatus.accepted(cmdKey);
                        break;
                        
                    default:
                        continue;
                }
                
                addStatus(status);
            }
        }

        System.out.println("Inserted " + numCmds + " commands " +
            "with commandStreamID=" + csID + " starting on " + startTime);

        return addedCmds;
    }


    private void checkCommandDataEqual(ICommandData o1, ICommandData o2)
    {
        assertEquals(o1.getCommandStreamID(), o2.getCommandStreamID());
        assertEquals(o1.getSenderID(), o2.getSenderID());
        assertEquals(o1.getFoiID(), o2.getFoiID());
        assertEquals(o1.getIssueTime(), o2.getIssueTime());
        assertEquals(o1.getParams().getClass(), o2.getParams().getClass());
        assertEquals(o1.getParams().getAtomCount(), o2.getParams().getAtomCount());
    }
    
    
    protected BigId bigId(long id)
    {
        return BigId.fromLong(DATABASE_NUM, id);
    }


    @Test
    public void testGetDatastoreName() throws Exception
    {
        assertEquals(CMD_DATASTORE_NAME, cmdStore.getDatastoreName());

        forceReadBackFromStorage();
        assertEquals(CMD_DATASTORE_NAME, cmdStore.getDatastoreName());
    }


    protected void checkGetCommands(int expectedNumObs) throws Exception
    {
        assertEquals(expectedNumObs, cmdStore.getNumRecords());

        for (Entry<BigId, ICommandData> entry: allCommands.entrySet())
        {
            ICommandData cmd = cmdStore.get(entry.getKey());
            checkCommandDataEqual(cmd, entry.getValue());
        }
    }


    @Test
    public void testGetNumRecordsOneDataStream() throws Exception
    {
        int totalObs = 0, numObs;
        var csKey = addSimpleCommandStream(bigId(10), "out1");
        
        // add cmd w/o FOI
        addCommands(csKey.getInternalID(), Instant.parse("2000-01-01T00:00:00Z"), numObs=100);
        assertEquals(totalObs += numObs, cmdStore.getNumRecords());

        forceReadBackFromStorage();
        assertEquals(totalObs, cmdStore.getNumRecords());
    }


    @Test
    public void testGetNumRecordsTwoDataStreams() throws Exception
    {
        int totalObs = 0, numObs;
        var cs1 = addSimpleCommandStream(bigId(1), "out1");
        var cs2 = addSimpleCommandStream(bigId(2), "out1");

        // add cmd with proc1
        addCommands(cs1.getInternalID(), Instant.parse("2000-06-21T14:36:12Z"), numObs=100);
        assertEquals(totalObs += numObs, cmdStore.getNumRecords());

        // add cmd with proc2
        addCommands(cs2.getInternalID(), Instant.parse("1970-01-01T00:00:00Z"), numObs=50);
        assertEquals(totalObs += numObs, cmdStore.getNumRecords());

        forceReadBackFromStorage();
        assertEquals(totalObs, cmdStore.getNumRecords());
    }


    @Test
    public void testAddAndGetByKeyOneDataStream() throws Exception
    {
        int totalObs = 0, numObs;
        var csKey = addSimpleCommandStream(bigId(1), "out1");
        
        // add cmd w/o FOI
        addCommands(csKey.getInternalID(), Instant.parse("2000-01-01T00:00:00Z"), numObs=100);
        checkGetCommands(totalObs += numObs);
        forceReadBackFromStorage();
        checkGetCommands(totalObs);

        // add cmd with FOI
        addCommands(csKey.getInternalID(), Instant.parse("9080-02-01T00:00:00Z"), numObs=30);
        checkGetCommands(totalObs += numObs);
        forceReadBackFromStorage();
        checkGetCommands(totalObs);
    }


    @Test
    public void testGetWrongKey() throws Exception
    {
        testGetNumRecordsOneDataStream();
        assertNull(cmdStore.get(bigId(11)));
    }


    protected void checkMapKeySet(Set<BigId> keySet)
    {
        keySet.forEach(k -> {
            assertEquals("Invalid scope", DATABASE_NUM, k.getScope());
            if (!allCommands.containsKey(k))
                fail("No matching key in reference list: " + k);
        });

        allCommands.keySet().forEach(k -> {
            if (!keySet.contains(k))
                fail("No matching key in datastore: " + k);
        });
    }


    @Test
    public void testAddAndCheckMapKeys() throws Exception
    {
        var csKey = addSimpleCommandStream(bigId(10), "out1");
        addCommands(csKey.getInternalID(), Instant.parse("2000-01-01T00:00:00Z"), 10);
        checkMapKeySet(cmdStore.keySet());

        forceReadBackFromStorage();
        checkMapKeySet(cmdStore.keySet());

        csKey = addSimpleCommandStream(bigId(10), "out2");
        addCommands(csKey.getInternalID(), Instant.MIN.plusSeconds(1), 11);
        addCommands(csKey.getInternalID(), Instant.MAX.minus(10, ChronoUnit.DAYS), 11);
        checkMapKeySet(cmdStore.keySet());

        csKey = addSimpleCommandStream(bigId(456), "output");
        addCommands(csKey.getInternalID(), Instant.parse("1950-01-01T00:00:00.5648712Z"), 56);
        checkMapKeySet(cmdStore.keySet());

        forceReadBackFromStorage();
        checkMapKeySet(cmdStore.keySet());
    }


    private void checkMapValues(Collection<ICommandData> mapValues)
    {
        mapValues.forEach(obs -> {
            boolean found = false;
            for (ICommandData truth: allCommands.values()) {
                try { checkCommandDataEqual(obs, truth); found = true; break; }
                catch (Throwable e) {}
            }
            if (!found)
                fail();
        });
    }


    @Test
    public void testAddAndCheckMapValues() throws Exception
    {
        var csKey = addSimpleCommandStream(bigId(100), "output3");
        
        addCommands(csKey.getInternalID(), Instant.parse("1900-01-01T00:00:00Z"), 100);
        checkMapValues(cmdStore.values());

        forceReadBackFromStorage();
        checkMapValues(cmdStore.values());
    }


    protected void checkRemoveAllKeys() throws Exception
    {
        assertTrue(cmdStore.getNumRecords() == allCommands.size());

        long t0 = System.currentTimeMillis();
        allCommands.forEach((k, f) -> {
            cmdStore.remove(k);
            assertFalse(cmdStore.containsKey(k));
            assertTrue(cmdStore.get(k) == null);
        });
        cmdStore.commit();
        System.out.println(String.format("%d cmd removed in %d ms", allCommands.size(), System.currentTimeMillis()-t0));

        assertTrue(cmdStore.isEmpty());
        assertTrue(cmdStore.getNumRecords() == 0);
        allCommands.clear();
    }


    @Test
    public void testAddAndRemoveByKey() throws Exception
    {
        var csKey = addSimpleCommandStream(bigId(10), "out1");
        
        addCommands(csKey.getInternalID(), Instant.parse("1900-01-01T00:00:00Z"), 100);
        checkRemoveAllKeys();

        addCommands(csKey.getInternalID(), Instant.parse("2900-01-01T00:00:00Z"), 100);
        forceReadBackFromStorage();
        checkRemoveAllKeys();

        forceReadBackFromStorage();
        addCommands(csKey.getInternalID(), Instant.parse("0001-01-01T00:00:00Z"), 100);
        checkRemoveAllKeys();

        forceReadBackFromStorage();
        checkRemoveAllKeys();
    }


    protected void checkSelectedEntries(Stream<Entry<BigId, ICommandData>> resultStream, Map<BigId, ICommandData> expectedResults, CommandFilter filter)
    {
        System.out.println("Select cmd with " + filter);

        Map<BigId, ICommandData> resultMap = resultStream
                .peek(e -> System.out.println(e.getKey() + ": " + e.getValue()))
                .collect(Collectors.toMap(e->e.getKey(), e->e.getValue()));
        System.out.println(resultMap.size() + " entries selected");
        assertEquals(expectedResults.size(), resultMap.size());
        
        resultMap.forEach((k, v) -> {
            assertEquals("Invalid scope", DATABASE_NUM, k.getScope());
            assertTrue("Result set contains extra key "+k, expectedResults.containsKey(k));
            checkCommandDataEqual(expectedResults.get(k), v);
        });

        expectedResults.forEach((k, v) -> {
            assertTrue("Result set is missing key "+k, resultMap.containsKey(k));
        });
    }
    
    
    @Test
    public void testSelectCommandsByMultipleIDs() throws Exception
    {
        Stream<Entry<BigId, ICommandData>> resultStream;
        CommandFilter filter;

        var csKey = addSimpleCommandStream(bigId(10), "out1");
        var startTime1 = Instant.parse("1918-02-11T08:12:06.897Z");
        var cmdBatch1 = addCommands(csKey.getInternalID(), startTime1, 12, 1000);
        var startTime2 = Instant.parse("3019-05-31T10:46:03.258Z");
        var cmdBatch2 = addCommands(csKey.getInternalID(), startTime2, 23, 10000);
        
        // all from batch 1
        filter = new CommandFilter.Builder()
            .withInternalIDs(cmdBatch1.keySet())
            .build();
        resultStream = cmdStore.selectEntries(filter);
        checkSelectedEntries(resultStream, cmdBatch1, filter);
        
        forceReadBackFromStorage();
        
        // all from batch 2
        filter = new CommandFilter.Builder()
            .withInternalIDs(cmdBatch2.keySet())
            .build();
        resultStream = cmdStore.selectEntries(filter);
        checkSelectedEntries(resultStream, cmdBatch2, filter);
        
        // one from each batch
        filter = new CommandFilter.Builder()
            .withInternalIDs(
                Iterators.get(cmdBatch1.keySet().iterator(), 3),
                Iterators.get(cmdBatch2.keySet().iterator(), 10))
            .build();
        resultStream = cmdStore.selectEntries(filter);
        var expectedResults = ImmutableMap.<BigId, ICommandData>builder()
            .put(Iterators.get(cmdBatch1.entrySet().iterator(), 3))
            .put(Iterators.get(cmdBatch2.entrySet().iterator(), 10))
            .build();
        checkSelectedEntries(resultStream, expectedResults, filter);
    }


    @Test
    public void testSelectCommandsByDataStreamIDAndTime() throws Exception
    {
        Stream<Entry<BigId, ICommandData>> resultStream;
        CommandFilter filter;

        var csKey = addSimpleCommandStream(bigId(10), "out1");
        var startTime1 = Instant.parse("2018-02-11T08:12:06.897Z");
        var cmdBatch1 = addCommands(csKey.getInternalID(), startTime1, 55, 1000);
        var startTime2 = Instant.parse("2019-05-31T10:46:03.258Z");
        var cmdBatch2 = addCommands(csKey.getInternalID(), startTime2, 100, 10000);

        // correct stream ID and all times
        filter = new CommandFilter.Builder()
            .withCommandStreams(csKey.getInternalID())
            .build();
        resultStream = cmdStore.selectEntries(filter);
        checkSelectedEntries(resultStream, allCommands, filter);

        // correct stream ID and issue time range containing all
        filter = new CommandFilter.Builder()
            .withCommandStreams(csKey.getInternalID())
            .withIssueTimeDuring(startTime1, startTime2.plus(1, ChronoUnit.DAYS))
            .build();
        forceReadBackFromStorage();
        resultStream = cmdStore.selectEntries(filter);
        checkSelectedEntries(resultStream, allCommands, filter);

        // correct stream ID and issue time range containing only batch 1
        filter = new CommandFilter.Builder()
            .withCommandStreams(csKey.getInternalID())
            .withIssueTimeDuring(startTime1, startTime1.plus(1, ChronoUnit.DAYS))
            .build();
        forceReadBackFromStorage();
        resultStream = cmdStore.selectEntries(filter);
        checkSelectedEntries(resultStream, cmdBatch1, filter);

        // correct stream ID and issue time range containing only batch 2
        filter = new CommandFilter.Builder()
            .withCommandStreams(csKey.getInternalID())
            .withIssueTimeDuring(startTime2, startTime2.plus(1, ChronoUnit.DAYS))
            .build();
        forceReadBackFromStorage();
        resultStream = cmdStore.selectEntries(filter);
        checkSelectedEntries(resultStream, cmdBatch2, filter);

        // correct stream ID and latest time
        filter = new CommandFilter.Builder()
            .withCommandStreams(csKey.getInternalID())
            .withLatestIssued()
            .build();
        forceReadBackFromStorage();
        resultStream = cmdStore.selectEntries(filter);
        var lastTimeStamp = startTime2.plusMillis(99*10000);
        checkSelectedEntries(resultStream, Maps.filterValues(cmdBatch2,
            v -> v.getIssueTime().equals(lastTimeStamp)), filter);

        // incorrect stream ID
        filter = new CommandFilter.Builder()
            .withCommandStreams(bigId(12))
            .build();
        resultStream = cmdStore.selectEntries(filter);
        checkSelectedEntries(resultStream, Collections.emptyMap(), filter);

        // incorrect time range
        filter = new CommandFilter.Builder()
            .withCommandStreams(csKey.getInternalID())
            .withIssueTimeDuring(startTime1.minus(100, ChronoUnit.DAYS), startTime1.minusMillis(1))
            .build();
        resultStream = cmdStore.selectEntries(filter);
        checkSelectedEntries(resultStream, Collections.emptyMap(), filter);
    }


    @Test
    public void testSelectCommandsByDataStreamIDAndPredicates() throws Exception
    {
        Stream<Entry<BigId, ICommandData>> resultStream;
        Map<BigId, ICommandData> expectedResults;
        CommandFilter filter;

        var cs1 = addSimpleCommandStream(bigId(1), "out1");
        var startTime1 = Instant.parse("2018-02-11T08:12:06.897Z");
        var cmdBatch1 = addCommands(cs1.getInternalID(), startTime1, 55, 1000, null);
        var startTime2 = Instant.parse("2019-05-31T10:46:03.258Z");
        var cmdBatch2 = addCommands(cs1.getInternalID(), startTime2, 100, 10000, null);
        
        forceReadBackFromStorage();

        var cs2 = addSimpleCommandStream(bigId(1), "out2");
        var startTime3 = Instant.parse("2018-02-11T08:12:06.897Z");
        var cmdBatch3 = addCommands(cs2.getInternalID(), startTime3, 10, 10*24*3600*1000L, null);

        // command stream 1 and predicate to select by every 10s
        filter = new CommandFilter.Builder()
            .withCommandStreams(cs1.getInternalID(), cs2.getInternalID())
            .withValuePredicate(v -> v.getIssueTime().atOffset(ZoneOffset.UTC).getLong(ChronoField.MINUTE_OF_HOUR) == 12)
            .build();
        resultStream = cmdStore.selectEntries(filter);
        expectedResults = Maps.filterValues(allCommands, v -> v.getIssueTime().atOffset(ZoneOffset.UTC).getLong(ChronoField.MINUTE_OF_HOUR) == 12);
        checkSelectedEntries(resultStream, expectedResults, filter);

        // command stream 1 and predicate to select param < 10
        filter = new CommandFilter.Builder()
            .withCommandStreams(cs1.getInternalID())
            .withValuePredicate(v -> v.getParams().getDoubleValue(0) < 10)
            .build();
        resultStream = cmdStore.selectEntries(filter);
        var expectedResults1  = new LinkedHashMap<BigId, ICommandData>();
        cmdBatch1.entrySet().stream().limit(10).forEach(e -> expectedResults1.put(e.getKey(), e.getValue()));
        cmdBatch2.entrySet().stream().limit(10).forEach(e -> expectedResults1.put(e.getKey(), e.getValue()));
        checkSelectedEntries(resultStream, expectedResults1, filter);

        // command stream 2 and predicate to select all
        filter = new CommandFilter.Builder()
            .withCommandStreams(cs2.getInternalID())
            .withValuePredicate(v -> v.getSenderID().endsWith("2"))
            .build();
        resultStream = cmdStore.selectEntries(filter);
        var expectedResults2 = new LinkedHashMap<BigId, ICommandData>();
        cmdBatch3.entrySet().stream().filter(e -> e.getValue().getSenderID().endsWith("2"))
                                     .forEach(e -> expectedResults2.put(e.getKey(), e.getValue()));
        checkSelectedEntries(resultStream, expectedResults2, filter);
    }


    @Test
    public void testSelectCommandsByDataStreamIDAndStatus() throws Exception
    {
        Stream<Entry<BigId, ICommandData>> resultStream;
        CommandFilter filter;

        var cs1 = addSimpleCommandStream(bigId(1), "out1");
        var startTime1 = Instant.parse("2018-02-11T08:12:06.897Z");
        var cmdBatch1 = addCommands(cs1.getInternalID(), startTime1, 2, 1000, CommandStatusCode.FAILED);
        var startTime2 = Instant.parse("2019-05-31T10:46:03.258Z");
        var cmdBatch2 = addCommands(cs1.getInternalID(), startTime2, 2, 10000, CommandStatusCode.COMPLETED);

        var cs2 = addSimpleCommandStream(bigId(1), "out2");
        var startTime3 = Instant.parse("2018-02-11T08:12:06.897Z");
        var cmdBatch3 = addCommands(cs2.getInternalID(), startTime3, 2, 10*24*3600*1000L, CommandStatusCode.ACCEPTED);

        // filter by status code
        filter = new CommandFilter.Builder()
            .withCommandStreams(cs1.getInternalID())
            .withLatestStatus(CommandStatusCode.COMPLETED)
            .build();
        resultStream = cmdStore.selectEntries(filter);
        checkSelectedEntries(resultStream, cmdBatch2, filter);
        
        forceReadBackFromStorage();
        
        filter = new CommandFilter.Builder()
            .withCommandStreams(cs1.getInternalID())
            .withLatestStatus(CommandStatusCode.FAILED)
            .build();
        resultStream = cmdStore.selectEntries(filter);
        checkSelectedEntries(resultStream, cmdBatch1, filter);
        
        filter = new CommandFilter.Builder()
            .withCommandStreams(cs1.getInternalID(), cs2.getInternalID())
            .withLatestStatus(CommandStatusCode.ACCEPTED)
            .build();
        resultStream = cmdStore.selectEntries(filter);
        checkSelectedEntries(resultStream, cmdBatch3, filter);
        
        // filter by execution time range
        // this should select only successful commands since failed ones have no execution time
        filter = new CommandFilter.Builder()
            .withCommandStreams(cs1.getInternalID())
            .withStatus()
                .withExecutionTimeDuring(startTime2, startTime2.plus(1, ChronoUnit.DAYS))
                .done()
            .build();
        resultStream = cmdStore.selectEntries(filter);
        checkSelectedEntries(resultStream, cmdBatch2, filter);
    }


    @Test
    public void testSelectCommandsByCommandStreamFilter() throws Exception
    {
        Stream<Entry<BigId, ICommandData>> resultStream;
        Map<BigId, ICommandData> expectedResults = new LinkedHashMap<>();
        CommandFilter filter;

        var cs1 = addSimpleCommandStream(bigId(1), "test1");
        var cs2 = addSimpleCommandStream(bigId(1), "test2");

        var startBatch1 = Instant.parse("2018-02-11T08:12:06.897Z");
        var cmdBatch1 = addCommands(cs1.getInternalID(), startBatch1, 55, 1000, CommandStatusCode.COMPLETED);
        var startBatch2 = Instant.parse("2018-02-11T08:11:48.125Z");
        var cmdBatch2 = addCommands(cs2.getInternalID(), startBatch2, 10, 1200, CommandStatusCode.FAILED);

        // command stream 2 by ID
        filter = new CommandFilter.Builder()
            .withCommandStreams(cs2.getInternalID())
            .build();
        resultStream = cmdStore.selectEntries(filter);
        checkSelectedEntries(resultStream, cmdBatch2, filter);

        // command stream 1 & 2 by proc ID
        filter = new CommandFilter.Builder()
            .withSystems(bigId(1))
            .build();
        resultStream = cmdStore.selectEntries(filter);
        expectedResults.clear();
        expectedResults.putAll(cmdBatch1);
        expectedResults.putAll(cmdBatch2);
        checkSelectedEntries(resultStream, expectedResults, filter);

        // command stream 1 by control input name
        forceReadBackFromStorage();
        filter = new CommandFilter.Builder()
            .withCommandStreams(new CommandStreamFilter.Builder()
                .withControlInputNames("test1")
                .build())
            .build();
        resultStream = cmdStore.selectEntries(filter);
        checkSelectedEntries(resultStream, cmdBatch1, filter);
    }
    
    
    protected void checkSelectedEntries(Stream<Entry<BigId, ICommandStatus>> resultStream, Map<BigId, ICommandStatus> expectedResults, CommandStatusFilter filter)
    {
        System.out.println("Select status with " + filter);

        var resultMap = resultStream
                .peek(e -> System.out.println(e.getKey() + ": " + e.getValue()))
                //.peek(e -> System.out.println(Arrays.toString((double[])e.getValue().getResult().getUnderlyingObject())))
                .collect(Collectors.toMap(e->e.getKey(), e->e.getValue()));
        System.out.println(resultMap.size() + " entries selected");
        
        resultMap.forEach((k, v) -> {
            assertTrue("Result set contains extra key "+k, expectedResults.containsKey(k));
            checkCommandStatusEqual(expectedResults.get(k), v);
            assertEquals("Invalid scope", DATABASE_NUM, k.getScope());
        });
        
        assertEquals(expectedResults.size(), resultMap.size());
        expectedResults.forEach((k, v) -> {
            assertTrue("Result set is missing key "+k, resultMap.containsKey(k));
        });
    }


    private void checkCommandStatusEqual(ICommandStatus o1, ICommandStatus o2)
    {
        assertEquals(o1.getCommandID(), o2.getCommandID());
        assertEquals(o1.getExecutionTime(), o2.getExecutionTime());
        assertEquals(o1.getMessage(), o2.getMessage());
        assertEquals(o1.getProgress(), o2.getProgress());
        assertEquals(o1.getReportTime(), o2.getReportTime());
        assertEquals(o1.getStatusCode(), o2.getStatusCode());
    }
    
    
    @Test
    public void testSelectStatusByCommand() throws Exception
    {
        Stream<Entry<BigId, ICommandStatus>> resultStream;
        Map<BigId, ICommandStatus> expectedResults;
        CommandStatusFilter filter;

        var cs1 = addSimpleCommandStream(bigId(1), "out1");
        var startTime1 = Instant.parse("2018-02-11T08:12:06.897Z");
        var cmdBatch1 = addCommands(cs1.getInternalID(), startTime1, 2, 1000, CommandStatusCode.FAILED);
        var startTime2 = Instant.parse("2019-05-31T10:46:03.258Z");
        var cmdBatch2 = addCommands(cs1.getInternalID(), startTime2, 2, 10000, CommandStatusCode.COMPLETED);
        
        var cs2 = addSimpleCommandStream(bigId(1), "out2");
        var startTime3 = Instant.parse("2018-02-11T08:12:06.897Z");
        var cmdBatch3 = addCommands(cs2.getInternalID(), startTime3, 1, 10*24*3600*1000L, CommandStatusCode.COMPLETED);

        // filter by command only
        filter = new CommandStatusFilter.Builder()
            .withCommands(cmdBatch3.keySet())
            .build();
        resultStream = cmdStore.getStatusReports().selectEntries(filter);
        expectedResults = cmdBatch3.keySet().stream()
            .flatMap(k -> allStatus.get(k).entrySet().stream())
            //.peek(e -> System.out.println(e))
            .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
        checkSelectedEntries(resultStream, expectedResults, filter);
        
        // filter by commands and status
        filter = new CommandStatusFilter.Builder()
            .withCommands()
                .withCommandStreams(cs1.getInternalID())
                .done()
            .withStatus(CommandStatusCode.COMPLETED)
            .build();
        resultStream = cmdStore.getStatusReports().selectEntries(filter);
        expectedResults = cmdBatch2.keySet().stream()
            .flatMap(k -> allStatus.get(k).entrySet().stream())
            .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
        checkSelectedEntries(resultStream, expectedResults, filter);
        
        filter = new CommandStatusFilter.Builder()
            .withCommands()
                .withCommandStreams(cs1.getInternalID())
                .done()
            .withStatus(CommandStatusCode.FAILED)
            .build();
        resultStream = cmdStore.getStatusReports().selectEntries(filter);
        expectedResults = cmdBatch1.keySet().stream()
            .flatMap(k -> allStatus.get(k).entrySet().stream())
            .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
        checkSelectedEntries(resultStream, expectedResults, filter);
        
        // filter by status only
        filter = new CommandStatusFilter.Builder()
            .withStatus(CommandStatusCode.FAILED)
            .build();
        resultStream = cmdStore.getStatusReports().selectEntries(filter);
        expectedResults = cmdBatch1.keySet().stream()
            .flatMap(k -> allStatus.get(k).entrySet().stream())
            .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
        checkSelectedEntries(resultStream, expectedResults, filter);
    }
    
    
    @Test(expected = IllegalStateException.class)
    public void testErrorWithSystemFilterJoin() throws Exception
    {
        try
        {
            cmdStore.selectEntries(new CommandFilter.Builder()
                .withSystems()
                    .withKeywords("thermometer")
                    .done()
                .build());
        }
        catch (Exception e)
        {
            System.err.println(e.getMessage());
            throw e;
        }
    }
}
