/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.test.persistence;

import static org.junit.Assert.*;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.opengis.DateTimeDouble;
import net.opengis.IDateTime;
import net.opengis.gml.v32.TimeInstant;
import net.opengis.gml.v32.TimePeriod;
import net.opengis.gml.v32.TimePosition;
import net.opengis.gml.v32.impl.GMLFactory;
import net.opengis.sensorml.v20.AbstractProcess;
import net.opengis.sensorml.v20.IdentifierList;
import net.opengis.sensorml.v20.PhysicalSystem;
import net.opengis.sensorml.v20.Term;
import net.opengis.swe.v20.DataArray;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.Quantity;
import org.junit.Test;
import org.sensorhub.api.persistence.DataFilter;
import org.sensorhub.api.persistence.DataKey;
import org.sensorhub.api.persistence.IRecordStorageModule;
import org.sensorhub.api.persistence.IDataFilter;
import org.sensorhub.api.persistence.IDataRecord;
import org.sensorhub.api.persistence.IRecordStoreInfo;
import org.sensorhub.test.TestUtils;
import org.vast.data.BinaryEncodingImpl;
import org.vast.data.CountImpl;
import org.vast.data.DataArrayImpl;
import org.vast.data.DataRecordImpl;
import org.vast.data.QuantityImpl;
import org.vast.data.TextEncodingImpl;
import org.vast.data.TextImpl;
import org.vast.sensorML.PhysicalSystemImpl;
import org.vast.sensorML.SMLFactory;
import org.vast.sensorML.SMLUtils;
import org.vast.swe.SWEHelper;
import org.vast.util.DateTimeFormat;
import com.google.common.collect.Sets;


/**
 * <p>
 * Abstract base for testing implementations of IBasicStorage.
 * The storage needs to be correctly initialized by derived tests in a method
 * tagged with '@Before'.
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @param <StorageType> type of storage under test
 * @since Nov 29, 2014
 */
public abstract class AbstractTestBasicStorage<StorageType extends IRecordStorageModule<?>>
{
    static String SENSOR_UID_PREFIX = "urn:domain:sensors:";
    
    protected StorageType storage;
    protected String producerID = SENSOR_UID_PREFIX + 1;
    protected Set<String> producerFilterList = Sets.newHashSet(producerID);
    
    
    protected abstract void forceReadBackFromStorage() throws Exception;
    
    
    protected DataComponent createDs1() throws Exception
    {
        DataComponent recordDesc = new QuantityImpl();
        recordDesc.setName("ds1");
        storage.addRecordStore(recordDesc.getName(), recordDesc, new TextEncodingImpl());
        return recordDesc;
    }
    
    
    protected DataComponent createDs2() throws Exception
    {
        DataComponent recordDesc = new DataRecordImpl();
        recordDesc.setName("ds2");
        recordDesc.setDefinition("urn:auth:blabla:record-stuff");
        Quantity q = new QuantityImpl();
        q.setLabel("My Quantity");
        q.getUom().setCode("m.s-2.kg-1");
        recordDesc.addComponent("c1", q);
        recordDesc.addComponent("c2", new CountImpl());
        recordDesc.addComponent("c3", new TextImpl());
        storage.addRecordStore(recordDesc.getName(), recordDesc, new TextEncodingImpl());
        return recordDesc;
    }
    
    
    protected DataComponent createDs3(DataComponent nestedRec) throws Exception
    {
        DataComponent recordDesc = new DataArrayImpl(10);
        recordDesc.setName("ds3");
        recordDesc.setDefinition("urn:auth:blabla:array-stuff");
        ((DataArray)recordDesc).setElementType("elt", nestedRec);
        storage.addRecordStore(recordDesc.getName(), recordDesc, new BinaryEncodingImpl());
        return recordDesc;
    }
    
    
    @Test
    public void testCreateDataStores() throws Exception
    {
        Map<String, ? extends IRecordStoreInfo> recordTypes;
        
        DataComponent recordDs1 = createDs1();
        forceReadBackFromStorage();
        recordTypes = storage.getRecordStores();
        assertEquals(1, recordTypes.size());
        
        DataComponent recordDs2 = createDs2();
        forceReadBackFromStorage();
        recordTypes = storage.getRecordStores();        
        assertEquals(2, recordTypes.size());  
        
        forceReadBackFromStorage();
        recordTypes = storage.getRecordStores();
        TestUtils.assertEquals(recordDs1, recordTypes.get(recordDs1.getName()).getRecordDescription());
        assertEquals(TextEncodingImpl.class, recordTypes.get(recordDs1.getName()).getRecommendedEncoding().getClass());
        
        TestUtils.assertEquals(recordDs2, recordTypes.get(recordDs2.getName()).getRecordDescription());
        assertEquals(TextEncodingImpl.class, recordTypes.get(recordDs2.getName()).getRecommendedEncoding().getClass());
        
        DataComponent recordDs3 = createDs3(recordDs2);
        forceReadBackFromStorage();
        recordTypes = storage.getRecordStores();
        assertEquals(3, recordTypes.size());        
        TestUtils.assertEquals(recordDs3, recordTypes.get(recordDs3.getName()).getRecordDescription());
        assertEquals(BinaryEncodingImpl.class, recordTypes.get(recordDs3.getName()).getRecommendedEncoding().getClass());
    }
    
    
    @Test
    public void testStoreAndGetLatestSensorML() throws Exception
    {
        SMLUtils smlUtils = new SMLUtils(SMLUtils.V2_0);
        InputStream is = new BufferedInputStream(getClass().getResourceAsStream("/gamma2070_more.xml"));
        AbstractProcess smlIn = smlUtils.readProcess(is);
        storage.storeDataSourceDescription(smlIn);
        forceReadBackFromStorage();
        AbstractProcess smlOut = storage.getLatestDataSourceDescription();
        TestUtils.assertEquals(smlIn, smlOut);
    }
    
    
    @Test
    public void testStoreAndGetSensorMLByTime() throws Exception
    {
        SMLUtils smlUtils = new SMLUtils(SMLUtils.V2_0);
        InputStream is;
                
        // load SensorML doc and set first validity period
        is = new BufferedInputStream(getClass().getResourceAsStream("/gamma2070_more.xml"));
        AbstractProcess smlIn1 = smlUtils.readProcess(is);
        IDateTime begin1 = new DateTimeDouble(new DateTimeFormat().parseIso("2010-05-15Z"));
        ((TimePeriod)smlIn1.getValidTimeList().get(0)).getBeginPosition().setDateTimeValue(begin1);
        IDateTime end1 = new DateTimeDouble(new DateTimeFormat().parseIso("2010-09-23Z"));
        ((TimePeriod)smlIn1.getValidTimeList().get(0)).getEndPosition().setDateTimeValue(end1);
        storage.storeDataSourceDescription(smlIn1);
        forceReadBackFromStorage();
        
        AbstractProcess smlOut = storage.getLatestDataSourceDescription();
        TestUtils.assertEquals(smlIn1, smlOut);
        
        smlOut = storage.getDataSourceDescriptionAtTime(begin1.getAsDouble());
        TestUtils.assertEquals(smlIn1, smlOut);
        
        smlOut = storage.getDataSourceDescriptionAtTime(end1.getAsDouble());
        TestUtils.assertEquals(smlIn1, smlOut);
        
        smlOut = storage.getDataSourceDescriptionAtTime(begin1.getAsDouble() + 3600*24*10);
        TestUtils.assertEquals(smlIn1, smlOut);
        
        // load SensorML doc another time and set with a different validity period
        is = new BufferedInputStream(getClass().getResourceAsStream("/gamma2070_more.xml"));
        AbstractProcess smlIn2 = smlUtils.readProcess(is);
        IDateTime begin2 = new DateTimeDouble(new DateTimeFormat().parseIso("2010-09-24Z"));
        ((TimePeriod)smlIn2.getValidTimeList().get(0)).getBeginPosition().setDateTimeValue(begin2);
        IDateTime end2 = new DateTimeDouble(new DateTimeFormat().parseIso("2010-12-08Z"));
        ((TimePeriod)smlIn2.getValidTimeList().get(0)).getEndPosition().setDateTimeValue(end2);
        storage.storeDataSourceDescription(smlIn2);        
        forceReadBackFromStorage();
        
        smlOut = storage.getDataSourceDescriptionAtTime(begin1.getAsDouble());
        TestUtils.assertEquals(smlIn1, smlOut);
        
        smlOut = storage.getDataSourceDescriptionAtTime(end1.getAsDouble());
        TestUtils.assertEquals(smlIn1, smlOut);
        
        smlOut = storage.getDataSourceDescriptionAtTime(begin1.getAsDouble() + 3600*24*10);
        TestUtils.assertEquals(smlIn1, smlOut);
        
        smlOut = storage.getDataSourceDescriptionAtTime(begin2.getAsDouble());
        TestUtils.assertEquals(smlIn2, smlOut);
        
        smlOut = storage.getDataSourceDescriptionAtTime(end2.getAsDouble());
        TestUtils.assertEquals(smlIn2, smlOut);
        
        smlOut = storage.getDataSourceDescriptionAtTime(begin2.getAsDouble() + 3600*24*10);
        TestUtils.assertEquals(smlIn2, smlOut);
    }
    
    
    @Test
    public void testStoreAndGetRecordsByKey() throws Exception
    {
        DataBlock data, readData;
        DataKey key;
        
        // test data store #1
        DataComponent recordDs1 = createDs1();
        data = recordDs1.createDataBlock();
        data.setDoubleValue(0.95);
        key = new DataKey(recordDs1.getName(), producerID, 12.0);
        storage.storeRecord(key, data);
        forceReadBackFromStorage();
        readData = storage.getDataBlock(key);
        TestUtils.assertEquals(data, readData);
        
        // test data store #2
        DataComponent recordDs2 = createDs2();
        data = recordDs2.createDataBlock();
        data.setDoubleValue(0, 1.0);
        data.setIntValue(1, 2);
        data.setStringValue(2, "test");
        key = new DataKey(recordDs2.getName(), producerID, 123.0);
        storage.storeRecord(key, data);
        forceReadBackFromStorage();
        readData = storage.getDataBlock(key);
        TestUtils.assertEquals(data, readData);
        
        // test data store #3
        DataArray recordDs3 = (DataArray)createDs3(recordDs2);
        data = recordDs3.createDataBlock();
        int arraySize = recordDs3.getElementCount().getValue();
        int offset = 0;
        for (int i=0; i<arraySize; i++)
        {
            data.setDoubleValue(offset++, i+0.5);
            data.setIntValue(offset++, 2*i);
            data.setStringValue(offset++, "test" + i);
        }
        key = new DataKey(recordDs3.getName(), producerID, 10.);
        storage.storeRecord(key, data);
        forceReadBackFromStorage();
        readData = storage.getDataBlock(key);
        TestUtils.assertEquals(data, readData);
    }
    
    
    protected List<DataBlock> writeRecords(DataComponent recordDef, double firstTime, double timeStep, int numRecords) throws Exception
    {
        return writeRecords(recordDef, firstTime, timeStep, numRecords, Integer.MAX_VALUE);
    }
    
    
    protected List<DataBlock> writeRecords(DataComponent recordDef, double firstTime, double timeStep, int numRecords, int maxDuration) throws Exception
    {
        DataBlock data;
        DataKey key;        
        
        // write N records or stop after maxDuration
        long t0 = System.currentTimeMillis();
        double timeStamp = firstTime;
        List<DataBlock> dataList = new ArrayList<DataBlock>(1000);
        
        for (int i=0; i<numRecords; i++)
        {
            data = recordDef.createDataBlock();
            data.setDoubleValue(0, i + 0.3);
            data.setIntValue(1, 2*i);
            data.setStringValue(2, "test" + i);
            
            timeStamp = firstTime + i*timeStep;
            key = new DataKey(recordDef.getName(), producerID, timeStamp);
          
            storage.storeRecord(key, data);
            dataList.add(data);
            
            if (Thread.interrupted() || System.currentTimeMillis() - t0 > maxDuration)
                break;
            
            if (i%250 == 0)
                storage.commit();
            
            Thread.sleep(1);
        }
        storage.commit();
        
        return dataList;
    }
    
    
    @Test
    public void testStoreAndGetMultipleRecordsByKey() throws Exception
    {
        DataComponent recordDef = createDs2();
        
        // write N records
        int numRecords = 100;
        double timeStep = 0.1;
        List<DataBlock> dataList = writeRecords(recordDef, 0.0, timeStep, numRecords);
        forceReadBackFromStorage();
        
        // retrieve them and check their values
        for (int i=0; i<numRecords; i++)
        {
            DataKey key = new DataKey(recordDef.getName(), producerID, i*timeStep);
            DataBlock data = storage.getDataBlock(key);
            TestUtils.assertEquals(dataList.get(i), data);
        }
    }
    
    
    @Test
    public void testStoreAndGetMultipleRecordsByFilter() throws Exception
    {
        DataComponent recordDef = createDs2();
        
        // write N records
        final int numRecords = 100;
        final double timeStep = 0.1;
        List<DataBlock> dataList = writeRecords(recordDef, 0.0, timeStep, numRecords);
        forceReadBackFromStorage();
        
        // prepare filter
        final int firstMatchingRecord = 10;
        final int lastMatchingRecord = 60;
        final int numFilteredRecords = lastMatchingRecord - firstMatchingRecord + 1;
        IDataFilter filter = new DataFilter(recordDef.getName()) {
            @Override
            public double[] getTimeStampRange() { return new double[] {firstMatchingRecord*timeStep, lastMatchingRecord*timeStep}; }
            public Set<String> getProducerIDs() { return producerFilterList; }
        };
        
        // check num matching filter
        int numMatching = storage.getNumMatchingRecords(filter, numRecords);
        assertEquals("Wrong number of matching records", numFilteredRecords, numMatching);
        
        // retrieve records and check their values
        int i = firstMatchingRecord;
        Iterator<? extends IDataRecord> it = storage.getRecordIterator(filter);
        while (it.hasNext())
        {
            TestUtils.assertEquals(dataList.get(i), it.next().getData());
            i++;
        }
        
        assertEquals("Wrong number of records returned", numFilteredRecords, i-firstMatchingRecord);
    }
    
    
    @Test
    public void testStoreAndGetTimeRange() throws Exception
    {
        DataComponent recordDef = createDs2();
        
        // write N records
        int numRecords = 100;
        double timeStep = 0.1;
        writeRecords(recordDef, 0.0, timeStep, numRecords);
        forceReadBackFromStorage();
        
        // check number of records
        int recordCount = storage.getNumRecords(recordDef.getName());
        assertEquals("Wrong number of records returned", numRecords, recordCount);
                
        // retrieve them and check their values
        double[] timeRange = storage.getRecordsTimeRange(recordDef.getName());
        assertEquals("Invalid begin time", 0., timeRange[0], 1e-6);
        assertEquals("Invalid end time", (numRecords-1)*timeStep, timeRange[1], 1e-6);
    }
    
    
    @Test
    public void testStoreIncompatibleRecord() throws Exception
    {
        // TODO check that a datablock that is incompatible with record definition is rejected
    }
    
    
    @Test
    public void testStoreAndRemoveRecordsByKey() throws Exception
    {
        DataComponent recordDef = createDs2();
        
        // write N records
        int numRecords = 100;
        double timeStep = 0.1;
        writeRecords(recordDef, 0.0, timeStep, numRecords);
        forceReadBackFromStorage();
        
        // remove some records by key
        int startIndex = 20;
        int deleteCount = 10;
        for (int i=startIndex; i<startIndex+deleteCount;i++)
        {
            DataKey key = new DataKey(recordDef.getName(), producerID, timeStep*i);
            storage.removeRecord(key);
        }
        
        // check that they are not there
        int recordCount = storage.getNumRecords(recordDef.getName());
        assertEquals("Wrong number of records returned", numRecords-deleteCount, recordCount);
    }
    
    
    @Test
    public void testStoreAndRemoveRecordsByFilter() throws Exception
    {
        DataComponent recordDef = createDs2();
        
        // write N records
        int numRecords = 100;
        double timeStep = 0.1;
        writeRecords(recordDef, 0.0, timeStep, numRecords);
        forceReadBackFromStorage();
        
        // remove some records by key
        final double startTime = 3.0;
        final double endTime = 8.0;
        int deleteCount = (int)((endTime-startTime)/timeStep)+1;
        DataFilter filter = new DataFilter(recordDef.getName()) {
            @Override
            public double[] getTimeStampRange()
            {
                return new double[] {startTime, endTime};
            }            
        };        
        int numDeleted = storage.removeRecords(filter);
        
        // check deleted count
        assertEquals("Wrong remove count", deleteCount, numDeleted);
        
        // check that they are not there
        int recordCount = storage.getNumRecords(recordDef.getName());
        assertEquals("Wrong number of records returned", numRecords-deleteCount, recordCount);
        
        // check that they are still not there after reload
        forceReadBackFromStorage();
        recordCount = storage.getNumRecords(recordDef.getName());
        assertEquals("Wrong number of records returned", numRecords-deleteCount, recordCount);
        
        int readCount = storage.getNumMatchingRecords(filter, Integer.MAX_VALUE);
        assertEquals("Wrong number of records returned", 0, readCount);
        
        // remove everything
        storage.removeRecords(new DataFilter(recordDef.getName()));
        recordCount = storage.getNumRecords(recordDef.getName());
        assertEquals("Wrong number of records returned", 0, recordCount);
    }
    
    
    ///////////////////////
    // Concurrency Tests //
    ///////////////////////
    
    long refTime;
    int numWrittenMetadataObj;
    int numWrittenRecords;
    volatile int numWriteThreadsRunning;
    
    protected void startWriteRecordsThreads(final ExecutorService exec, 
                                            final int numWriteThreads,
                                            final DataComponent recordDef,
                                            final double timeStep,
                                            final int testDurationMs,
                                            final Collection<Throwable> errors)
    {
        numWriteThreadsRunning = numWriteThreads;
                
        for (int i=0; i<numWriteThreads; i++)
        {
            final int count = i;
            exec.submit(new Runnable() {
                @Override
                public void run()
                {
                    long startTimeOffset = System.currentTimeMillis() - refTime;
                    System.out.format("Begin Write Records Thread %d @ %dms\n", Thread.currentThread().getId(), startTimeOffset);
                    
                    try
                    {
                        List<DataBlock> dataList = writeRecords(recordDef, count*10000., timeStep, Integer.MAX_VALUE, testDurationMs);
                        synchronized(AbstractTestBasicStorage.this) {
                            numWrittenRecords += dataList.size();
                        }
                    }
                    catch (Throwable e)
                    {
                        errors.add(e);
                        //exec.shutdownNow();
                    }
                    
                    synchronized(AbstractTestBasicStorage.this) {
                        numWriteThreadsRunning--;
                    }
                    
                    long stopTimeOffset = System.currentTimeMillis() - refTime;
                    System.out.format("End Write Records Thread %d @ %dms\n", Thread.currentThread().getId(), stopTimeOffset);
                }
            });
        }
    }
    
    
    protected void startReadRecordsThreads(final ExecutorService exec, 
                                           final int numReadThreads,
                                           final DataComponent recordDef,
                                           final double timeStep,
                                           final Collection<Throwable> errors)
    {
        for (int i=0; i<numReadThreads; i++)
        {
            exec.submit(new Runnable() {
                @Override
                public void run()
                {
                    long tid = Thread.currentThread().getId();
                    long startTimeOffset = System.currentTimeMillis() - refTime;
                    System.out.format("Begin Read Records Thread %d @ %dms\n", tid, startTimeOffset);
                    int readCount = 0;
                    
                    try
                    {
                        while (numWriteThreadsRunning > 0 && !Thread.interrupted())
                        {
                            //System.out.println(numWriteThreadsRunning);
                            double[] timeRange = storage.getRecordsTimeRange(recordDef.getName());
                            if (Double.isNaN(timeRange[0]))
                                continue;
                            //double[] timeRange = new double[] {0.0, 110000.0};
                            
                            //System.out.format("Read Thread %d, Loop %d\n", Thread.currentThread().getId(), j+1);
                            final double begin = timeRange[0] + Math.random() * (timeRange[1] - timeRange[0]);
                            final double end = begin + Math.max(timeStep*100., Math.random() * (timeRange[1] - begin));
                            
                            // prepare filter
                            IDataFilter filter = new DataFilter(recordDef.getName()) {
                                @Override
                                public double[] getTimeStampRange() { return new double[] {begin, end}; }
                            };
                        
                            // retrieve records
                            Iterator<? extends IDataRecord> it = storage.getRecordIterator(filter);                            
                            
                            // check records time stamps and order
                            //System.out.format("Read Thread %d, [%f-%f]\n", Thread.currentThread().getId(), begin, end);
                            double lastTimeStamp = Double.NEGATIVE_INFINITY;
                            while (it.hasNext())
                            {
                                IDataRecord rec = it.next();
                                double timeStamp = rec.getKey().timeStamp;
                                
                                //System.out.format("Read Thread %d, %f\n", Thread.currentThread().getId(), timeStamp);
                                assertTrue(tid + ": Time steps are not increasing: " + timeStamp + "<" + lastTimeStamp , timeStamp > lastTimeStamp);
                                assertTrue(tid + ": Time stamp lower than begin: " + timeStamp + "<" + begin , timeStamp >= begin);
                                assertTrue(tid + ": Time stamp higher than end: " + timeStamp + ">" + end, timeStamp <= end);
                                lastTimeStamp = timeStamp;
                                readCount++;
                            }
                            
                            Thread.sleep(1);
                        }
                    }
                    catch (Throwable e)
                    {
                        errors.add(e);
                        //exec.shutdownNow();
                    }
                    
                    long stopTimeOffset = System.currentTimeMillis() - refTime;
                    System.out.format("End Read Records Thread %d @%dms - %d read ops\n", Thread.currentThread().getId(), stopTimeOffset, readCount);
                }
            });
        }
    }
    
    
    protected void startWriteMetadataThreads(final ExecutorService exec, 
                                             final int numWriteThreads,
                                             final Collection<Throwable> errors)
    {
        for (int i=0; i<numWriteThreads; i++)
        {
            final int startCount = i*1000000;
            exec.submit(new Runnable() {
                @Override
                public void run()
                {
                    long startTimeOffset = System.currentTimeMillis() - refTime;
                    System.out.format("Begin Write Desc Thread %d @%dms\n", Thread.currentThread().getId(), startTimeOffset);
                    
                    try
                    {
                        int count = startCount;
                        while (numWriteThreadsRunning > 0 && !Thread.interrupted())
                        {
                            // create description
                            //SWEHelper helper = new SWEHelper();
                            SMLFactory smlFac = new SMLFactory();
                            GMLFactory gmlFac = new GMLFactory();
                            
                            PhysicalSystem system = new PhysicalSystemImpl();
                            system.setUniqueIdentifier("TEST" + count++);
                            system.setName("blablabla");
                            system.setDescription("this is the description of my sensor that can be pretty long");
                            
                            IdentifierList identifierList = smlFac.newIdentifierList();
                            system.addIdentification(identifierList);
                            
                            Term term;            
                            term = smlFac.newTerm();
                            term.setDefinition(SWEHelper.getPropertyUri("Manufacturer"));
                            term.setLabel("Manufacturer Name");
                            term.setValue("My manufacturer");
                            identifierList.addIdentifier(term);
                            
                            term = smlFac.newTerm();
                            term.setDefinition(SWEHelper.getPropertyUri("ModelNumber"));
                            term.setLabel("Model Number");
                            term.setValue("SENSOR_2365");
                            identifierList.addIdentifier(term);
                            
                            term = smlFac.newTerm();
                            term.setDefinition(SWEHelper.getPropertyUri("SerialNumber"));
                            term.setLabel("Serial Number");
                            term.setValue("FZEFZE154618989");
                            identifierList.addIdentifier(term);
                            
                            // generate unique time stamp
                            TimePosition timePos = gmlFac.newTimePosition(startCount + System.currentTimeMillis()/1000.);
                            TimeInstant validTime = gmlFac.newTimeInstant(timePos);
                            system.addValidTimeAsTimeInstant(validTime);
                            
                            // add to storage
                            storage.storeDataSourceDescription(system);
                            //storage.commit();
                            
                            synchronized(AbstractTestBasicStorage.this) {
                                numWrittenMetadataObj++;
                            }
                            
                            Thread.sleep(5);
                        }
                    }
                    catch (Throwable e)
                    {
                        errors.add(e);
                        //exec.shutdownNow();
                    }
                    
                    long stopTimeOffset = System.currentTimeMillis() - refTime;
                    System.out.format("End Write Desc Thread %d @%dms\n", Thread.currentThread().getId(), stopTimeOffset);
                }
            });
        }
    }
    
    
    protected void startReadMetadataThreads(final ExecutorService exec, 
            final int numReadThreads,
            final Collection<Throwable> errors)
    {
        for (int i = 0; i < numReadThreads; i++)
        {
            exec.submit(new Runnable()
            {
                @Override
                public void run()
                {
                    long tid = Thread.currentThread().getId();
                    long startTimeOffset = System.currentTimeMillis() - refTime;
                    System.out.format("Begin Read Desc Thread %d @ %dms\n", tid, startTimeOffset);
                    int readCount = 0;

                    try
                    {
                        while (numWriteThreadsRunning > 0 && !Thread.interrupted())
                        {
                            AbstractProcess p = storage.getLatestDataSourceDescription();
                            if (p != null)
                                assertTrue("Missing valid time", p.getValidTimeList().size() > 0);
                            readCount++;
                            Thread.sleep(1);
                        }
                    }
                    catch (Throwable e)
                    {
                        errors.add(e);
                        //exec.shutdownNow();
                    }

                    long stopTimeOffset = System.currentTimeMillis() - refTime;
                    System.out.format("End Read Desc Thread %d @%dms - %d read ops\n", Thread.currentThread().getId(), stopTimeOffset, readCount);
                }
            });
        }
    }   

    
    protected void checkForAsyncErrors(Collection<Throwable> errors) throws Throwable
    {
        // report errors
        System.out.println(errors.size() + " error(s)");
        for (Throwable e: errors)
            e.printStackTrace();
        if (!errors.isEmpty())
            throw errors.iterator().next();
    }
    
    
    protected void checkRecordsInStorage(final DataComponent recordDef) throws Throwable
    {
        System.out.println(numWrittenRecords + " records written");
        
        // check number of records        
        int recordCount = storage.getNumRecords(recordDef.getName());
        assertEquals("Wrong number of records in storage", numWrittenRecords, recordCount);
        
        // check number of records returned by iterator
        recordCount = 0;
        Iterator<?> it = storage.getRecordIterator(new DataFilter(recordDef.getName()));
        while (it.hasNext())
        {
            it.next();
            recordCount++;
        }
        assertEquals("Wrong number of records returned by iterator", numWrittenRecords, recordCount);
    }
    
    
    protected void checkMetadataInStorage() throws Throwable
    {
        System.out.println(numWrittenMetadataObj + " metadata objects written");
        
        int descCount = 0;
        List<AbstractProcess> descList = storage.getDataSourceDescriptionHistory(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        for (AbstractProcess desc: descList)
        {
            assertTrue(desc instanceof PhysicalSystem);
            assertEquals("blablabla", desc.getName());
            assertTrue(desc.getUniqueIdentifier().startsWith("TEST"));
            descCount++;
        }        
        assertEquals("Wrong number of metadata objects in storage", numWrittenMetadataObj, descCount);
        
        AbstractProcess desc = storage.getLatestDataSourceDescription();
        assertTrue(desc instanceof PhysicalSystem);
    }
    
    
    @Test
    public void testConcurrentWriteRecords() throws Throwable
    {
        final DataComponent recordDef = createDs2();
        ExecutorService exec = Executors.newCachedThreadPool();
        final Collection<Throwable> errors = Collections.synchronizedCollection(new ArrayList<Throwable>());
        
        int numWriteThreads = 10;
        int testDurationMs = 2000;
        refTime = System.currentTimeMillis();
        
        startWriteRecordsThreads(exec, numWriteThreads, recordDef, 0.1, testDurationMs, errors);
        
        exec.shutdown();
        exec.awaitTermination(testDurationMs*2, TimeUnit.MILLISECONDS);
        
        forceReadBackFromStorage();
        checkRecordsInStorage(recordDef);
        checkForAsyncErrors(errors);
    }
    
    
    @Test
    public void testConcurrentWriteMetadata() throws Throwable
    {
        ExecutorService exec = Executors.newCachedThreadPool();
        final Collection<Throwable> errors = Collections.synchronizedCollection(new ArrayList<Throwable>());
        
        int numWriteThreads = 10;
        int testDurationMs = 2000;
        refTime = System.currentTimeMillis();
        
        numWriteThreadsRunning = 1;
        startWriteMetadataThreads(exec, numWriteThreads, errors);
      
        Thread.sleep(testDurationMs);
        numWriteThreadsRunning = 0;
        
        exec.shutdown();
        exec.awaitTermination(testDurationMs*2, TimeUnit.MILLISECONDS);
        
        forceReadBackFromStorage();
        checkMetadataInStorage();
        checkForAsyncErrors(errors);
    }
    
    
    @Test
    public void testConcurrentWriteThenReadRecords() throws Throwable
    {
        DataComponent recordDef = createDs2();
        ExecutorService exec = Executors.newCachedThreadPool();
        Collection<Throwable> errors = Collections.synchronizedCollection(new ArrayList<Throwable>());        
        
        int numWriteThreads = 10;
        int numReadThreads = 10;
        int testDurationMs = 1000;
        double timeStep = 0.1;
        refTime = System.currentTimeMillis();
        
        startWriteRecordsThreads(exec, numWriteThreads, recordDef, timeStep, testDurationMs, errors);
        
        exec.shutdown();
        exec.awaitTermination(testDurationMs*2, TimeUnit.MILLISECONDS);
        exec = Executors.newCachedThreadPool();
        numWriteThreadsRunning = 1;
        
        checkForAsyncErrors(errors);
        forceReadBackFromStorage();
        
        errors.clear();
        startReadRecordsThreads(exec, numReadThreads, recordDef, timeStep, errors);
        
        Thread.sleep(testDurationMs);
        numWriteThreadsRunning = 0; // manually stop reading after sleep period
        exec.shutdown();
        exec.awaitTermination(1000, TimeUnit.MILLISECONDS);
        
        checkForAsyncErrors(errors);
        checkRecordsInStorage(recordDef);        
    }
    
    
    @Test
    public void testConcurrentReadWriteRecords() throws Throwable
    {
        final DataComponent recordDef = createDs2();
        final ExecutorService exec = Executors.newCachedThreadPool();
        final Collection<Throwable> errors = Collections.synchronizedCollection(new ArrayList<Throwable>());        
        
        int numWriteThreads = 10;
        int numReadThreads = 10;
        int testDurationMs = 2000;
        double timeStep = 0.1;
        refTime = System.currentTimeMillis();
        
        startWriteRecordsThreads(exec, numWriteThreads, recordDef, timeStep, testDurationMs, errors);       
        startReadRecordsThreads(exec, numReadThreads, recordDef, timeStep, errors);
        
        exec.shutdown();
        exec.awaitTermination(testDurationMs*200, TimeUnit.MILLISECONDS);
        
        forceReadBackFromStorage();
        checkForAsyncErrors(errors);
        checkRecordsInStorage(recordDef);        
    }
    
    
    @Test
    public void testConcurrentReadWriteMetadataAndRecords() throws Throwable
    {
        final DataComponent recordDef = createDs2();
        ExecutorService exec = Executors.newCachedThreadPool();
        final Collection<Throwable> errors = Collections.synchronizedCollection(new ArrayList<Throwable>());        
        
        int numWriteThreads = 10;
        int numReadThreads = 10;
        int testDurationMs = 3000;
        double timeStep = 0.1;
        refTime = System.currentTimeMillis();
        
        startWriteRecordsThreads(exec, numWriteThreads, recordDef, timeStep, testDurationMs, errors);
        startWriteMetadataThreads(exec, numWriteThreads, errors); 
        startReadRecordsThreads(exec, numReadThreads, recordDef, timeStep, errors);
        startReadMetadataThreads(exec, numReadThreads, errors);
        
        exec.shutdown();
        exec.awaitTermination(testDurationMs*2, TimeUnit.MILLISECONDS);

        forceReadBackFromStorage();
        checkForAsyncErrors(errors);
        checkRecordsInStorage(recordDef);
        checkMetadataInStorage();
    }
}
