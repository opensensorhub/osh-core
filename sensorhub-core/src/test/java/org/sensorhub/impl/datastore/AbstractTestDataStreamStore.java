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
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.sensorhub.api.datastore.TemporalFilter;
import org.sensorhub.api.obs.DataStreamFilter;
import org.sensorhub.api.obs.DataStreamInfo;
import org.sensorhub.api.obs.DataStreamKey;
import org.sensorhub.api.obs.IDataStreamInfo;
import org.sensorhub.api.obs.IDataStreamStore;
import org.sensorhub.api.procedure.ProcedureId;
import org.vast.data.TextEncodingImpl;
import org.vast.swe.SWEHelper;
import org.vast.swe.SWEUtils;
import org.vast.util.TimeExtent;
import net.opengis.swe.v20.DataComponent;


/**
 * <p>
 * Abstract base for testing implementations of IFeatureStore.
 * </p>
 *
 * @author Alex Robin
 * @param <StoreType> Type of store under test
 * @since Apr 14, 2018
 */
public abstract class AbstractTestDataStreamStore<StoreType extends IDataStreamStore>
{
    protected static String PROC_UID_PREFIX = "urn:osh:test:sensor:";
    
    protected StoreType dataStreamStore;
    protected Map<DataStreamKey, IDataStreamInfo> allDataStreams = new LinkedHashMap<>();


    protected abstract StoreType initStore() throws Exception;
    protected abstract void forceReadBackFromStorage() throws Exception;


    @Before
    public void init() throws Exception
    {
        this.dataStreamStore = initStore();        
    }
    

    protected DataStreamKey addDataStream(ProcedureId procID, DataComponent recordStruct, TimeExtent validTime)
    {
        var builder = new DataStreamInfo.Builder()
            .withProcedure(procID)
            .withRecordDescription(recordStruct)
            .withRecordEncoding(new TextEncodingImpl());
        
        if (validTime != null)
            builder.withValidTime(validTime);
                
        var dsInfo = builder.build();
        var key = dataStreamStore.add(dsInfo);
        allDataStreams.put(key, dsInfo);
        return key;
    }


    protected DataStreamKey addSimpleDataStream(ProcedureId procID, String outputName, String description, TimeExtent validTime)
    {
        SWEHelper fac = new SWEHelper();
        var dataStruct = fac.createDataRecord()
            .name(outputName)
            .description(description)
            .addField("t1", fac.createTime().asSamplingTimeIsoUTC().build())
            .addField("q2", fac.createQuantity().build())
            .addField("c3", fac.createCount().build())
            .addField("b4", fac.createBoolean().build())
            .addField("txt5", fac.createText().build())
            .build();
        
        return addDataStream(procID, dataStruct, validTime);
    }
    
    
    protected DataStreamKey addSimpleDataStream(long procID, String outputName, TimeExtent validTime)
    {
        return addSimpleDataStream(new ProcedureId(procID, PROC_UID_PREFIX+procID), outputName, "datastream description", validTime);
    }
    
    
    protected DataStreamKey addSimpleDataStream(long procID, String outputName, String description, TimeExtent validTime)
    {
        return addSimpleDataStream(new ProcedureId(procID, PROC_UID_PREFIX+procID), outputName, description, validTime);
    }


    protected void checkDataStreamEqual(IDataStreamInfo ds1, IDataStreamInfo ds2)
    {
        assertEquals(ds1.getProcedureID(), ds2.getProcedureID());
        assertEquals(ds1.getOutputName(), ds2.getOutputName());
        assertEquals(ds1.getName(), ds2.getName());
        assertEquals(ds1.getDescription(), ds2.getDescription());
        checkDataComponentEquals(ds1.getRecordStructure(), ds2.getRecordStructure());
        //assertEquals(ds1.getRecordEncoding(), ds2.getRecordEncoding());
        assertEquals(ds1.getValidTime(), ds2.getValidTime());
    }


    protected void checkDataComponentEquals(DataComponent c1, DataComponent c2)
    {
        SWEUtils utils = new SWEUtils(SWEUtils.V2_0);
        ByteArrayOutputStream os1 = new ByteArrayOutputStream();
        ByteArrayOutputStream os2 = new ByteArrayOutputStream();
        
        try
        {
            utils.writeComponent(os1, c1, false, false);
            utils.writeComponent(os2, c2, false, false);
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }        

        assertArrayEquals(os1.toByteArray(), os2.toByteArray());
    }


    protected void checkSelectedEntries(Stream<Entry<DataStreamKey, IDataStreamInfo>> resultStream, Map<DataStreamKey, IDataStreamInfo> expectedResults, DataStreamFilter filter)
    {
        System.out.println("Select datastreams with " + filter);
        checkSelectedEntries(resultStream, expectedResults);
    }
    
    
    protected void checkSelectedEntries(Stream<Entry<DataStreamKey, IDataStreamInfo>> resultStream, Map<DataStreamKey, IDataStreamInfo> expectedResults)
    {
        Map<DataStreamKey, IDataStreamInfo> resultMap = resultStream
                //.peek(e -> System.out.println(e.getKey()))
                //.peek(e -> System.out.println(Arrays.toString((double[])e.getValue().getResult().getUnderlyingObject())))
                .collect(Collectors.toMap(e->e.getKey(), e->e.getValue()));
        System.out.println(resultMap.size() + " entries selected");
        
        resultMap.forEach((k, v) -> {
            assertTrue("Result set contains extra key "+k, expectedResults.containsKey(k));
            checkDataStreamEqual(expectedResults.get(k), v);
        });

        expectedResults.forEach((k, v) -> {
            assertTrue("Result set is missing key "+k, resultMap.containsKey(k));
        });
    }


    @Test
    public void testAddAndGetByKey() throws Exception
    {
        // add N different datastreams
        var now = TimeExtent.beginAt(Instant.now());
        int numDs = 100;
        for (int i = 1; i < numDs; i++)
        {
            long procID = i;
            addSimpleDataStream(procID, "test1", now);
        }
        
        // read back and check
        forceReadBackFromStorage();
        for (Entry<DataStreamKey, IDataStreamInfo> entry: allDataStreams.entrySet())
        {
            IDataStreamInfo dsInfo = dataStreamStore.get(entry.getKey());
            assertEquals(entry.getValue().getProcedureID(), dsInfo.getProcedureID());
            assertEquals(entry.getValue().getOutputName(), dsInfo.getOutputName());
            checkDataComponentEquals(entry.getValue().getRecordStructure(), dsInfo.getRecordStructure());
        }
    }


    @Test
    public void testGetWrongKey() throws Exception
    {
        assertNull(dataStreamStore.get(new DataStreamKey(1L)));
        assertNull(dataStreamStore.get(new DataStreamKey(21L)));
        
        // add N different datastreams
        var now = TimeExtent.beginAt(Instant.now());
        for (int i = 1; i < 5; i++)
        {
            long procID = i;
            addSimpleDataStream(procID, "test1", now);
        }
        
        assertNotNull(dataStreamStore.get(new DataStreamKey(1L)));
        assertNull(dataStreamStore.get(new DataStreamKey(21L)));
        forceReadBackFromStorage();
        assertNull(dataStreamStore.get(new DataStreamKey(11L)));
        assertNotNull(dataStreamStore.get(new DataStreamKey(3L)));
        
    }


    private void checkMapKeySet(Set<DataStreamKey> keySet)
    {
        keySet.forEach(k -> {
            if (!allDataStreams.containsKey(k))
                fail("No matching key in reference list: " + k);
        });

        allDataStreams.keySet().forEach(k -> {
            if (!keySet.contains(k))
                fail("No matching key in datastore: " + k);
        });
    }


    private void checkMapValues(Collection<IDataStreamInfo> mapValues)
    {
        mapValues.forEach(ds -> {
            boolean found = false;
            for (IDataStreamInfo truth: allDataStreams.values()) {
                try { checkDataStreamEqual(ds, truth); found = true; break; }
                catch (Throwable e) {}
            }
            if (!found)
                fail("Invalid datastream: " + ds);
        });
    }


    @Test
    public void testAddAndCheckMapKeysAndValues() throws Exception
    {
        // add N different datastreams
        var now = TimeExtent.beginAt(Instant.now());
        int numDs = 56;
        for (int i = numDs; i < numDs*2; i++)
        {
            long procID = i;
            addSimpleDataStream(procID, "out" + (int)(Math.random()*10), now);
        }
        
        // read back and check
        forceReadBackFromStorage();
        checkMapKeySet(dataStreamStore.keySet());
        checkMapValues(dataStreamStore.values());
    }


    @Test
    public void testAddAndRemoveByKey() throws Exception
    {
        // add N different datastreams
        var now = TimeExtent.beginAt(Instant.now());
        int numDs = 56;
        for (int i = numDs; i < numDs*2; i++)
        {
            long procID = i;
            addSimpleDataStream(procID, "out" + (int)(Math.random()*10), now);
        }
        
        assertEquals(numDs, dataStreamStore.getNumRecords());
        
        int i = 0;
        for (var id: allDataStreams.keySet())
        {
            var ds = dataStreamStore.remove(id);
            checkDataStreamEqual(allDataStreams.get(id), ds);            
            
            if (i % 5 == 0)
                forceReadBackFromStorage();
            
            i++;
            assertEquals(numDs-i, dataStreamStore.getNumRecords());
        }
        
        // check that there is nothing left
        assertEquals(0, dataStreamStore.getNumRecords());
    }


    @Test
    public void testAddAndRemoveByFilter() throws Exception
    {
        // add N different datastreams
        var now = TimeExtent.beginAt(Instant.now());
        int numDs = 45;
        for (int i = 1; i <= numDs; i++)
        {
            long procID = i;
            addSimpleDataStream(procID, "out"+i, now);
        }
        
        int numRecords = numDs;
        assertEquals(numRecords, dataStreamStore.getNumRecords());
        
        // remove some by ID
        var removedIds = new long[] {3L, 15L, 36L, 24L};
        for (long id: removedIds)
            allDataStreams.remove(new DataStreamKey(id));
        dataStreamStore.removeEntries(new DataStreamFilter.Builder()
                .withInternalIDs(removedIds)
                .build());
        checkSelectedEntries(dataStreamStore.entrySet().stream(), allDataStreams);
        numRecords -= removedIds.length;
        assertEquals(numRecords, dataStreamStore.getNumRecords());
        
        // remove some by name
        var removedIdsList = Arrays.asList(4L, 41L, 29L, 11L, 33L, 12L);
        var removedNames = removedIdsList.stream().map(id -> "out"+id).collect(Collectors.toList());
        for (long id: removedIdsList)
            allDataStreams.remove(new DataStreamKey(id));
        dataStreamStore.removeEntries(new DataStreamFilter.Builder()
                .withOutputNames(removedNames)
                .build());
        checkSelectedEntries(dataStreamStore.entrySet().stream(), allDataStreams);
        numRecords -= removedIdsList.size();
        assertEquals(numRecords, dataStreamStore.getNumRecords());
        
        // remove the rest
        dataStreamStore.removeEntries(new DataStreamFilter.Builder()
            .build());
        assertEquals(0, dataStreamStore.getNumRecords());
    }
    
    
    @Test
    @SuppressWarnings("unused")
    public void testAddAndSelectCurrentVersion() throws Exception
    {
        Stream<Entry<DataStreamKey, IDataStreamInfo>> resultStream;
        var expectedResults = new LinkedHashMap<DataStreamKey, IDataStreamInfo>();

        long procID = 10;
        var now = Instant.now();
        var ds1v0 = addSimpleDataStream(procID, "test1", TimeExtent.beginAt(now.minusSeconds(3600)));
        var ds1v1 = addSimpleDataStream(procID, "test1", TimeExtent.beginAt(now.minusSeconds(1200)));
        var ds1v2 = addSimpleDataStream(procID, "test1", TimeExtent.beginAt(now));
        var ds2v0 = addSimpleDataStream(procID, "test2", TimeExtent.beginAt(now.minusSeconds(3600)));
        var ds2v1 = addSimpleDataStream(procID, "test2", TimeExtent.beginAt(now));
        var ds2v2 = addSimpleDataStream(procID, "test2", TimeExtent.beginAt(now.plusSeconds(600)));
        var ds3v0 = addSimpleDataStream(procID, "test3", TimeExtent.beginAt(now.minusSeconds(3600)));
        var ds3v1 = addSimpleDataStream(procID, "test3", TimeExtent.beginAt(now.minusSeconds(600)));
        forceReadBackFromStorage();

        // last version of everything
        DataStreamFilter filter = new DataStreamFilter.Builder()
            .withProcedures(procID)
            .withCurrentVersion()
            .build();
        resultStream = dataStreamStore.selectEntries(filter);
        
        expectedResults.clear();
        expectedResults.put(ds1v2, allDataStreams.get(ds1v2));
        expectedResults.put(ds2v1, allDataStreams.get(ds2v1));
        expectedResults.put(ds3v1, allDataStreams.get(ds3v1));
        checkSelectedEntries(resultStream, expectedResults, filter);
    }
        
    
    @Test
    @SuppressWarnings("unused")
    public void testAddAndSelectLatestValidTime() throws Exception
    {
        Stream<Entry<DataStreamKey, IDataStreamInfo>> resultStream;
        var expectedResults = new LinkedHashMap<DataStreamKey, IDataStreamInfo>();

        long procID = 1;
        var now = Instant.now();
        var ds1v0 = addSimpleDataStream(procID, "test1", TimeExtent.beginAt(now.minusSeconds(3600)));
        var ds1v1 = addSimpleDataStream(procID, "test1", TimeExtent.beginAt(now.minusSeconds(1200)));
        var ds1v2 = addSimpleDataStream(procID, "test1", TimeExtent.beginAt(now));
        var ds2v0 = addSimpleDataStream(procID, "test2", TimeExtent.beginAt(now.minusSeconds(3600)));
        var ds2v1 = addSimpleDataStream(procID, "test2", TimeExtent.beginAt(now));
        var ds2v2 = addSimpleDataStream(procID, "test2", TimeExtent.beginAt(now.plusSeconds(600)));
        var ds3v0 = addSimpleDataStream(procID, "test3", TimeExtent.beginAt(now.minusSeconds(3600)));
        var ds3v1 = addSimpleDataStream(procID, "test3", TimeExtent.beginAt(now.minusSeconds(600)));
        forceReadBackFromStorage();

        // last version of everything
        DataStreamFilter filter = new DataStreamFilter.Builder()
            .withProcedures(procID)
            .withValidTime(new TemporalFilter.Builder()
                .withLatestTime()
                .build())
            .build();
        resultStream = dataStreamStore.selectEntries(filter);
        
        expectedResults.clear();
        expectedResults.put(ds1v2, allDataStreams.get(ds1v2));
        expectedResults.put(ds2v2, allDataStreams.get(ds2v2));
        expectedResults.put(ds3v1, allDataStreams.get(ds3v1));
        checkSelectedEntries(resultStream, expectedResults, filter);
    }
    
    
    @Test
    @SuppressWarnings("unused")
    public void testAddAndSelectByTimeRange() throws Exception
    {
        Stream<Entry<DataStreamKey, IDataStreamInfo>> resultStream;
        var expectedResults = new LinkedHashMap<DataStreamKey, IDataStreamInfo>();
        
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var ds1v0 = addSimpleDataStream(1, "out1", TimeExtent.beginAt(now.minus(365, ChronoUnit.DAYS)));
        var ds1v1 = addSimpleDataStream(1, "out1", TimeExtent.beginAt(now.minus(60, ChronoUnit.DAYS)));
        var ds2v0 = addSimpleDataStream(1, "out2", TimeExtent.beginAt(now.minus(520, ChronoUnit.DAYS)));
        var ds2v1 = addSimpleDataStream(1, "out2", TimeExtent.beginAt(now.minus(10, ChronoUnit.DAYS)));        
        var ds3v0 = addSimpleDataStream(1, "out3", TimeExtent.beginAt(now.minus(30, ChronoUnit.DAYS)));
        var ds3v1 = addSimpleDataStream(1, "out3", TimeExtent.beginAt(now.minus(1, ChronoUnit.DAYS)));
        var ds4v0 = addSimpleDataStream(3, "temp", TimeExtent.beginAt(now.plus(1, ChronoUnit.DAYS)));
        var ds5v0 = addSimpleDataStream(3, "hum", TimeExtent.beginAt(now.minus(60, ChronoUnit.DAYS)));
        
        // select from t0 to now
        DataStreamFilter filter = new DataStreamFilter.Builder()
            .withValidTimeDuring(now.minus(10, ChronoUnit.DAYS), now)
            .build();
        resultStream = dataStreamStore.selectEntries(filter);
        
        expectedResults.clear();
        expectedResults.put(ds1v1, allDataStreams.get(ds1v1));
        expectedResults.put(ds2v1, allDataStreams.get(ds2v1));
        expectedResults.put(ds3v0, allDataStreams.get(ds3v0));
        expectedResults.put(ds3v1, allDataStreams.get(ds3v1));
        expectedResults.put(ds5v0, allDataStreams.get(ds5v0));
        checkSelectedEntries(resultStream, expectedResults, filter);
                
        // select from t0 to t1
        forceReadBackFromStorage();
        filter = new DataStreamFilter.Builder()
            .withValidTimeDuring(now.minus(90, ChronoUnit.DAYS), now.minus(30, ChronoUnit.DAYS))
            .build();
        resultStream = dataStreamStore.selectEntries(filter);
        
        expectedResults.clear();
        expectedResults.put(ds1v0, allDataStreams.get(ds1v0));
        expectedResults.put(ds1v1, allDataStreams.get(ds1v1));
        expectedResults.put(ds2v0, allDataStreams.get(ds2v0));
        expectedResults.put(ds3v0, allDataStreams.get(ds3v0));
        expectedResults.put(ds5v0, allDataStreams.get(ds5v0));
        checkSelectedEntries(resultStream, expectedResults, filter);
        
        // select from t0 to t1, only proc 3
        forceReadBackFromStorage();
        filter = new DataStreamFilter.Builder()
            .withProcedures(3)
            .withValidTimeDuring(now.minus(90, ChronoUnit.DAYS), now.minus(30, ChronoUnit.DAYS))
            .build();
        resultStream = dataStreamStore.selectEntries(filter);
        
        expectedResults.clear();
        expectedResults.put(ds5v0, allDataStreams.get(ds5v0));
        checkSelectedEntries(resultStream, expectedResults, filter); 
    }
    
    
    @Test
    @SuppressWarnings("unused")
    public void testAddAndSelectByName() throws Exception
    {
        Stream<Entry<DataStreamKey, IDataStreamInfo>> resultStream;
        var expectedResults = new LinkedHashMap<DataStreamKey, IDataStreamInfo>();
        
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var ds1v0 = addSimpleDataStream(1, "out1", TimeExtent.beginAt(now.minus(365, ChronoUnit.DAYS)));
        var ds1v1 = addSimpleDataStream(1, "out1", TimeExtent.beginAt(now.minus(60, ChronoUnit.DAYS)));
        var ds2v0 = addSimpleDataStream(1, "out2", TimeExtent.beginAt(now.minus(60, ChronoUnit.DAYS)));
        var ds3v0 = addSimpleDataStream(2, "out1", TimeExtent.beginAt(now.minus(60, ChronoUnit.DAYS)));
        var ds4v0 = addSimpleDataStream(3, "temp", TimeExtent.beginAt(now.plus(1, ChronoUnit.DAYS)));
        var ds5v0 = addSimpleDataStream(3, "out1", TimeExtent.beginAt(now.minus(60, ChronoUnit.DAYS)));
        
        // select from t0 to now
        DataStreamFilter filter = new DataStreamFilter.Builder()
            .withOutputNames("out1")
            .build();
        resultStream = dataStreamStore.selectEntries(filter);
        
        expectedResults.clear();
        expectedResults.put(ds1v0, allDataStreams.get(ds1v0));
        expectedResults.put(ds1v1, allDataStreams.get(ds1v1));
        expectedResults.put(ds3v0, allDataStreams.get(ds3v0));
        expectedResults.put(ds5v0, allDataStreams.get(ds5v0));
        checkSelectedEntries(resultStream, expectedResults, filter);
    }
    
    
    @Test
    @SuppressWarnings("unused")
    public void testAddAndSelectByProcedureID() throws Exception
    {
        Stream<Entry<DataStreamKey, IDataStreamInfo>> resultStream;
        var expectedResults = new LinkedHashMap<DataStreamKey, IDataStreamInfo>();
        
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var ds1v0 = addSimpleDataStream(1, "out1", TimeExtent.beginAt(now.minus(365, ChronoUnit.DAYS)));
        var ds1v1 = addSimpleDataStream(1, "out1", TimeExtent.beginAt(now.minus(60, ChronoUnit.DAYS)));
        var ds2v0 = addSimpleDataStream(1, "out2", TimeExtent.beginAt(now.minus(60, ChronoUnit.DAYS)));
        var ds3v0 = addSimpleDataStream(2, "out1", TimeExtent.beginAt(now.minus(60, ChronoUnit.DAYS)));
        var ds4v0 = addSimpleDataStream(3, "temp", TimeExtent.beginAt(now.plus(1, ChronoUnit.DAYS)));
        var ds5v0 = addSimpleDataStream(3, "out1", TimeExtent.beginAt(now.minus(60, ChronoUnit.DAYS)));
        
        // select from t0 to now
        DataStreamFilter filter = new DataStreamFilter.Builder()
            .withProcedures(2, 3)
            .build();
        resultStream = dataStreamStore.selectEntries(filter);
        
        expectedResults.clear();
        expectedResults.put(ds3v0, allDataStreams.get(ds3v0));
        expectedResults.put(ds4v0, allDataStreams.get(ds4v0));
        expectedResults.put(ds5v0, allDataStreams.get(ds5v0));
        checkSelectedEntries(resultStream, expectedResults, filter);
    }
    
    
    @Test
    @SuppressWarnings("unused")
    public void testAddAndSelectByKeywords() throws Exception
    {
        Stream<Entry<DataStreamKey, IDataStreamInfo>> resultStream;
        var expectedResults = new LinkedHashMap<DataStreamKey, IDataStreamInfo>();
        DataStreamFilter filter;
        
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var ds1v0 = addSimpleDataStream(1, "out1", "Stationary weather data", TimeExtent.beginAt(now.minus(365, ChronoUnit.DAYS)));
        var ds1v1 = addSimpleDataStream(1, "out1", "Stationary weather data", TimeExtent.beginAt(now.minus(60, ChronoUnit.DAYS)));
        var ds2v0 = addSimpleDataStream(1, "out2", "Traffic video stream", TimeExtent.beginAt(now.minus(60, ChronoUnit.DAYS)));
        var ds3v0 = addSimpleDataStream(2, "out1", TimeExtent.beginAt(now.minus(60, ChronoUnit.DAYS)));
        var ds4v0 = addSimpleDataStream(3, "temp", "Air temperature", TimeExtent.beginAt(now.plus(1, ChronoUnit.DAYS)));
        var ds5v0 = addSimpleDataStream(3, "out1", "Air pressure", TimeExtent.beginAt(now.minus(60, ChronoUnit.DAYS)));
        
        // select with one keyword
        filter = new DataStreamFilter.Builder()
            .withKeywords("air")
            .build();
        resultStream = dataStreamStore.selectEntries(filter);
        
        expectedResults.clear();
        expectedResults.put(ds4v0, allDataStreams.get(ds4v0));
        expectedResults.put(ds5v0, allDataStreams.get(ds5v0));
        checkSelectedEntries(resultStream, expectedResults, filter);
        
        // select with 2 keywords
        filter = new DataStreamFilter.Builder()
            .withKeywords("air", "weather")
            .build();
        resultStream = dataStreamStore.selectEntries(filter);
        
        expectedResults.clear();
        expectedResults.put(ds1v0, allDataStreams.get(ds1v0));
        expectedResults.put(ds1v1, allDataStreams.get(ds1v1));
        expectedResults.put(ds4v0, allDataStreams.get(ds4v0));
        expectedResults.put(ds5v0, allDataStreams.get(ds5v0));
        checkSelectedEntries(resultStream, expectedResults, filter);
        
        // select with 2 keywords
        filter = new DataStreamFilter.Builder()
            .withKeywords("air", "video")
            .build();
        resultStream = dataStreamStore.selectEntries(filter);
        
        expectedResults.clear();
        expectedResults.put(ds2v0, allDataStreams.get(ds2v0));
        expectedResults.put(ds4v0, allDataStreams.get(ds4v0));
        expectedResults.put(ds5v0, allDataStreams.get(ds5v0));
        checkSelectedEntries(resultStream, expectedResults, filter);
        
        // select with procedure and keywords (partial words)
        filter = new DataStreamFilter.Builder()
            .withProcedures(3)
            .withKeywords("weather", "temp")
            .build();
        resultStream = dataStreamStore.selectEntries(filter);
        
        expectedResults.clear();
        expectedResults.put(ds4v0, allDataStreams.get(ds4v0));
        checkSelectedEntries(resultStream, expectedResults, filter);
        
        // select unknown keywords
        filter = new DataStreamFilter.Builder()
            .withProcedures(3)
            .withKeywords("lidar", "humidity")
            .build();
        resultStream = dataStreamStore.selectEntries(filter);
        
        expectedResults.clear();
        checkSelectedEntries(resultStream, expectedResults, filter);
    }
    
    
    @Test(expected = IllegalArgumentException.class)
    public void testErrorAddWithExistingOutput() throws Exception
    {
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        addSimpleDataStream(1, "out1", TimeExtent.beginAt(now));
        addSimpleDataStream(1, "out1", TimeExtent.beginAt(now));
    }
    
    
    @Test(expected = IllegalStateException.class)
    public void testErrorWithProcedureFilterJoin() throws Exception
    {
        try
        {
            dataStreamStore.selectEntries(new DataStreamFilter.Builder()
                .withProcedures()
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