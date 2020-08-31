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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.namespace.QName;
import net.opengis.gml.v32.AbstractFeature;
import net.opengis.gml.v32.Point;
import net.opengis.gml.v32.impl.GMLFactory;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import org.junit.Test;
import org.sensorhub.api.persistence.FoiFilter;
import org.sensorhub.api.persistence.IObsStorageModule;
import org.sensorhub.api.persistence.IDataRecord;
import org.sensorhub.api.persistence.IObsFilter;
import org.sensorhub.api.persistence.IObsStorage;
import org.sensorhub.api.persistence.ObsFilter;
import org.sensorhub.api.persistence.ObsKey;
import org.sensorhub.api.persistence.ObsPeriod;
import org.sensorhub.test.TestUtils;
import org.vast.ogc.gml.GenericFeatureImpl;
import org.vast.util.Bbox;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.Polygon;


/**
 * <p>
 * Abstract base for testing implementations of {@link IObsStorage}.
 * The storage needs to be correctly initialized by derived tests in a method
 * tagged with '@Before'.
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @param <StorageType> type of storage under test
 * @since May 5, 2015
 */
public abstract class AbstractTestObsStorage<StorageType extends IObsStorageModule<?>> extends AbstractTestBasicStorage<StorageType>
{
    static String FOI_UID_PREFIX = "urn:domain:features:foi";
    protected int numFois = 100;
    protected double[] longitudeBounds = new double[] {-179, 179};
    protected double[] latitudeBounds = new double[] {-89, 89};
    GMLFactory gmlFac = new GMLFactory(true);
    Map<String, AbstractFeature> allFeatures;

    
    static String[] FOI_SET1_IDS = new String[]
    {
        FOI_UID_PREFIX + "1",
        FOI_UID_PREFIX + "2",
        FOI_UID_PREFIX + "3",
        FOI_UID_PREFIX + "4",
        FOI_UID_PREFIX + "15"
    };
    
    static int[] FOI_SET1_STARTS = new int[] {0, 20, 25, 40, 60};
    
    
    String[] FOI_IDS = FOI_SET1_IDS;
    int[] FOI_STARTS = FOI_SET1_STARTS;
    
    
    protected void addFoisToStorage(Bbox extent) throws Exception
    {
        allFeatures = new LinkedHashMap<String, AbstractFeature>(numFois);
        
        for (int foiNum = 1; foiNum <= numFois; foiNum++)
        {
            QName fType = new QName("http://myNsUri", "MyFeature");
            AbstractFeature foi = new GenericFeatureImpl(fType);
            foi.setId("F" + foiNum);
            foi.setUniqueIdentifier(FOI_UID_PREFIX + foiNum);
            foi.setName("FOI" + foiNum);
            foi.setDescription("This is feature of interest #" + foiNum);                        
            Point p = gmlFac.newPoint();
            p.setPos(new double[] {
                    interpolate(foiNum, numFois, longitudeBounds),
                    interpolate(foiNum, numFois, latitudeBounds), 0.0
            });
            if (extent != null)
                extent.add(new Bbox(p.getPos()[0],p.getPos()[1], 0,p.getPos()[0],p.getPos()[1], 0));
            foi.setLocation(p);
            allFeatures.put(foi.getUniqueIdentifier(), foi);
            storage.storeFoi(producerID, foi);
        }
        
        storage.commit();
        forceReadBackFromStorage();
    }
    
    
    protected double interpolate(double val, double max, double[] bounds)
    {
        return (val / max) * (bounds[1] - bounds[0]) + bounds[0];
    }    
    
    
    @Test
    public void testStoreAndRetrieveFoisByID() throws Exception
    {
        addFoisToStorage(null);
        testFilterFoiByID(1, 2, 3, 22, 50, 78);
        testFilterFoiByID(1);
        testFilterFoiByID(56);
        int[] ids = new int[numFois];
        for (int i = 1; i <= numFois; i++)
            ids[i-1] = i;
        testFilterFoiByID(ids);
    }
    
    
    @Test
    public void testStoreAndRetrieveFoisWithWrongIDs() throws Exception
    {
        addFoisToStorage(null);
        testFilterFoiByID(102);
        testFilterFoiByID(102, 56, 516);
        testFilterFoiByID(102, 103, 104, 56, 516, 5);
    }
    
    
    protected void testFilterFoiByID(int... foiNums)
    {
        final Set<String> idList = new LinkedHashSet<String>(foiNums.length);
        for (int foiNum: foiNums)
            idList.add(FOI_UID_PREFIX + foiNum);
            
        FoiFilter filter = new FoiFilter()
        {
            @Override
            public Set<String> getFeatureIDs() { return idList; };
        };
        
        // build set of good IDs
        HashSet<String> validIDs = new HashSet<String>();        
        for (int foiNum: foiNums)
            if (foiNum <= numFois)
                validIDs.add(FOI_UID_PREFIX + foiNum);
            
        // test retrieve objects
        Iterator<AbstractFeature> it = storage.getFois(filter);
        int foiCount = 0;
        while (it.hasNext())
        {
            String fid = it.next().getUniqueIdentifier();
            assertTrue("Non requested FOI returned: " + fid, validIDs.contains(fid));
            foiCount++;
        }
        assertEquals("Wrong number of FOIs returned", validIDs.size(), foiCount);
        
        // test retrieve ids only
        Iterator<String> it2 = storage.getFoiIDs(filter);
        foiCount = 0;
        while (it2.hasNext())
        {
            String fid = it2.next();
            assertTrue("Non requested FOI id returned: " + fid, validIDs.contains(fid));
            foiCount++;
        }
        assertEquals("Wrong number of FOIs returned", validIDs.size(), foiCount);
    }
    
    
    @Test
    public void testStoreAndRetrieveFoisByRoi() throws Exception
    {
        addFoisToStorage(null);
        
        for (int i = 1; i <= numFois; i++) {
            double x = interpolate(i, numFois, longitudeBounds);
            double y = interpolate(i, numFois, latitudeBounds);
            testFilterFoiByRoi(new Bbox(x - 0.5, y - 0.5, x + 0.5, y + 0.5), i);
        }
    }
    
    
    protected void testFilterFoiByRoi(Bbox bbox, int... foiNums)
    {
        final Polygon poly = (Polygon)new GeometryFactory().toGeometry(bbox.toJtsEnvelope());
        FoiFilter filter = new FoiFilter()
        {
            @Override
            public Polygon getRoi() { return poly; };
        };
        
        // test retrieve objects
        Iterator<AbstractFeature> it = storage.getFois(filter);
        int i = 0;
        while (it.hasNext())
            assertEquals(FOI_UID_PREFIX + foiNums[i++], it.next().getUniqueIdentifier());
        assertEquals(foiNums.length, i);
        
        // test retrieve ids only
        Iterator<String> it2 = storage.getFoiIDs(filter);
        i = 0;
        while (it2.hasNext())
            assertEquals(FOI_UID_PREFIX + foiNums[i++], it2.next());
        assertEquals(foiNums.length, i);
    }
    
    
    protected List<DataBlock> addObservationsWithFoiToStorage(DataComponent recordDef) throws Exception
    {
        // write N records
        final double timeStep = 0.1;
        final int numRecords = 100;
        List<DataBlock> dataList = new ArrayList<DataBlock>(numRecords);
        
        for (int i=0; i<numRecords; i++)
        {
            DataBlock data = recordDef.createDataBlock();
            data.setDoubleValue(0, i*timeStep);
            data.setIntValue(1, 3*i);
            data.setStringValue(2, "test" + i);
            dataList.add(data);
            String foiID = null;
            for (int f=0; f<FOI_IDS.length; f++)
            {
                if (i >= FOI_STARTS[f])
                    foiID = FOI_IDS[f];
            }
            ObsKey key = new ObsKey(recordDef.getName(), producerID, foiID, i*timeStep);
            storage.storeRecord(key, data);
        }
        
        storage.commit();
        forceReadBackFromStorage();
        
        return dataList;
    }
    
    
    protected IObsFilter buildFilterByFoiID(DataComponent recordDef, List<DataBlock> dataList, final int[] foiIndexes)
    {
        final Set<String> foiSet = new HashSet<String>();
        for (int index: foiIndexes)
            foiSet.add(FOI_IDS[index]);
        
        // generate filter
        IObsFilter filter = new ObsFilter(recordDef.getName()) {
            @Override
            public Set<String> getFoiIDs() { return foiSet; }
            @Override
            public Set<String> getProducerIDs() {return producerFilterList; };
        };
        
        // filter dataList to provide ground truth
        int i = 0;
        Iterator<DataBlock> it = dataList.iterator();
        while (it.hasNext())
        {
            it.next();
            boolean foundFoi = false;
            
            // check foi index ranges
            for (int index: foiIndexes)
            {
                int startIndex = FOI_STARTS[index];
                int endIndex = (index == FOI_IDS.length-1) ? 100 : FOI_STARTS[index+1];
                
                if (i >= startIndex && i <= endIndex-1)
                {
                    foundFoi = true;
                    break;
                }
            }
            
            if (!foundFoi)
                it.remove();
            
            i++;
        }
        
        return filter;
    }
    
    
    protected IObsFilter buildFilterByRoi(DataComponent recordDef, List<DataBlock> dataList, final Polygon roi)
    {
        // get list of FOIs within roi
        ArrayList<Integer> foiIndexList = new ArrayList<Integer>();
        int fIndex = 0;
        for (String foiID: FOI_IDS)
        {
            AbstractFeature f = allFeatures.get(foiID);
            if (f != null)
            {
                if (roi.intersects((Geometry)f.getLocation()))
                    foiIndexList.add(fIndex);                
            }
            fIndex++;
        }
        
        // then just filter dataList using list of indexes
        int i = 0;
        int[] foiIndexes = new int[foiIndexList.size()];
        for (int index: foiIndexList)
            foiIndexes[i++] = index;
        buildFilterByFoiID(recordDef, dataList, foiIndexes);
        
        // generate filter
        IObsFilter filter = new ObsFilter(recordDef.getName()) {
            @Override
            public Polygon getRoi() { return roi; }
            @Override
            public Set<String> getProducerIDs() {return producerFilterList; };
        };
        
        return filter;
    }
    
    
    protected IObsFilter buildFilterByFoiIDAndTime(DataComponent recordDef, List<DataBlock> dataList, int[] foiIndexes, final double[] timeRange)
    {
        final Set<String> foiSet = new HashSet<String>();
        for (int index: foiIndexes)
            foiSet.add(FOI_IDS[index]);
        
        // generate filter
        IObsFilter filter = new ObsFilter(recordDef.getName()) {
            @Override
            public double[] getTimeStampRange() { return timeRange; }
            @Override
            public Set<String> getFoiIDs() { return foiSet; }
            @Override
            public Set<String> getProducerIDs() {return producerFilterList; };
        };
        
        // filter dataList to provide ground truth
        int i = 0;
        Iterator<DataBlock> it = dataList.iterator();
        while (it.hasNext())
        {
            DataBlock data = it.next();
            double timeStamp = data.getDoubleValue(0);
            boolean foundFoi = false;
            
            // remove if not this FOI record or if not within time range
            // check foi index ranges
            for (int index: foiIndexes)
            {
                int startIndex = FOI_STARTS[index];
                int endIndex = (index == FOI_IDS.length-1) ? 100 : FOI_STARTS[index+1];
                
                if (i >= startIndex && i <= endIndex-1)
                {
                    foundFoi = true;
                    break;
                }
            }
            
            if (!foundFoi || timeStamp < timeRange[0] || timeStamp > timeRange[1])
                it.remove();
            
            i++;
        }
        
        return filter;
    }
    
    
    protected void checkFilteredResults(IObsFilter filter, List<DataBlock> dataList) throws Exception
    {
        int i;
        
        // check data blocks
        i = 0;
        Iterator<DataBlock> it1 = storage.getDataBlockIterator(filter);
        while (it1.hasNext())
        {
            DataBlock blk = it1.next();
            if (dataList.size() > i)
                TestUtils.assertEquals(dataList.get(i), blk);
            i++;
        }
        
        assertEquals("Wrong number of records", dataList.size(), i);
        
        // check full DB records
        i = 0;
        Iterator<? extends IDataRecord> it2 = storage.getRecordIterator(filter);
        while (it2.hasNext())
        {
            IDataRecord dbRec = it2.next();            
            if (dataList.size() > i)
            {
                TestUtils.assertEquals(dataList.get(i), dbRec.getData());
                if (filter.getFoiIDs() != null)
                    assertTrue(filter.getFoiIDs().contains(((ObsKey)dbRec.getKey()).foiID));
            }            
            i++;
        }
        
        assertEquals("Wrong number of records", dataList.size(), i);
    }
    
    
    @Test
    public void testStoreOneFoiAndGetRecordsByFoiID() throws Exception
    {
        FOI_IDS = new String[] {"urn:domain:features:myfoi"};
        FOI_STARTS = new int[1];
        DataComponent recordDef = createDs2();
        List<DataBlock> dataList = addObservationsWithFoiToStorage(recordDef);
        
        int[] foiIndex = new int[] { 0 };
        IObsFilter filter = buildFilterByFoiID(recordDef, dataList, foiIndex);
        checkFilteredResults(filter, dataList);
    }
    
    
    @Test
    public void testGetRecordsForOneFoiID() throws Exception
    {
        DataComponent recordDef = createDs2();
        List<DataBlock> dataList = addObservationsWithFoiToStorage(recordDef);
        List<DataBlock> testList = new ArrayList<DataBlock>(dataList.size());
        
        for (int foiIndex = 0; foiIndex < FOI_IDS.length; foiIndex++)
        {
            testList.clear();
            testList.addAll(dataList);
            IObsFilter filter = buildFilterByFoiID(recordDef, testList, new int[] {foiIndex});
            checkFilteredResults(filter, testList);
        }
    }
    
    
    @Test
    public void testGetRecordsForMultipleFoiIDs() throws Exception
    {
        DataComponent recordDef = createDs2();
        List<DataBlock> dataList = addObservationsWithFoiToStorage(recordDef);
        List<DataBlock> testList = new ArrayList<DataBlock>(dataList.size());
        
        // FOI 1 and 2
        testList.clear();
        testList.addAll(dataList);
        IObsFilter filter = buildFilterByFoiID(recordDef, testList, new int[] {0, 1});
        checkFilteredResults(filter, testList);
        
        // FOI 1 and 3
        testList.clear();
        testList.addAll(dataList);
        filter = buildFilterByFoiID(recordDef, testList, new int[] {0, 2});
        checkFilteredResults(filter, testList);
        
        // FOI 3 and 2
        testList.clear();
        testList.addAll(dataList);
        filter = buildFilterByFoiID(recordDef, testList, new int[] {2, 1});
        checkFilteredResults(filter, testList);
        
        // FOI 2, 1 and 3
        testList.clear();
        testList.addAll(dataList);
        filter = buildFilterByFoiID(recordDef, testList, new int[] {1, 0, 2});
        checkFilteredResults(filter, testList);
    }
    
    
    @Test
    public void testGetRecordsForOneFoiIDAndTime() throws Exception
    {
        DataComponent recordDef = createDs2();
        List<DataBlock> dataList = addObservationsWithFoiToStorage(recordDef);
        List<DataBlock> testList = new ArrayList<DataBlock>(dataList.size());
        
        double[] timeRange1 = new double[] {1.0, 8.4};
        
        // test FOI 1 by 1
        for (int foiIndex = 0; foiIndex < FOI_IDS.length; foiIndex++)
        {
            testList.clear();
            testList.addAll(dataList);
            IObsFilter filter = buildFilterByFoiIDAndTime(recordDef, testList, new int[] {foiIndex}, timeRange1);
            checkFilteredResults(filter, testList);
        }
    }
    
    
    @Test
    public void testGetRecordsForMultipleFoiIDsAndTime() throws Exception
    {
        DataComponent recordDef = createDs2();
        List<DataBlock> dataList = addObservationsWithFoiToStorage(recordDef);
        List<DataBlock> testList = new ArrayList<DataBlock>(dataList.size());
        double[] timeRange;
        IObsFilter filter;
        
        // FOI 1 and 2
        timeRange = new double[] {1.0, 8.4};
        testList.clear();
        testList.addAll(dataList);
        filter = buildFilterByFoiIDAndTime(recordDef, testList, new int[] {0, 1}, timeRange);
        checkFilteredResults(filter, testList);
        
        // FOI 3 and 2
        timeRange = new double[] {2.5, 8.4};
        testList.clear();
        testList.addAll(dataList);
        filter = buildFilterByFoiIDAndTime(recordDef, testList, new int[] {2, 1}, timeRange);
        checkFilteredResults(filter, testList);
    }
    
    
    @Test
    public void testGetRecordsByRoi() throws Exception
    {
        addFoisToStorage(null);
        
        DataComponent recordDef = createDs2();
        List<DataBlock> dataList = addObservationsWithFoiToStorage(recordDef);
        List<DataBlock> testList = new ArrayList<DataBlock>(dataList.size());
        IObsFilter filter;
        Polygon roi;
        
        // NO FOI
        testList.clear();
        testList.addAll(dataList);
        roi = new GeometryFactory().createPolygon(new Coordinate[] {
            new Coordinate(0.0, 0.0),
            new Coordinate(0.0, 0.1),
            new Coordinate(0.1, 0.1),
            new Coordinate(0.1, 0.0),
            new Coordinate(0.0, 0.0)
        });
        filter = buildFilterByRoi(recordDef, testList, roi);
        checkFilteredResults(filter, testList);
        
        // FOI 1
        testList.clear();
        testList.addAll(dataList);
        roi = new GeometryFactory().createPolygon(new Coordinate[] {
            new Coordinate(0.5, 0.5),
            new Coordinate(0.5, 1.5),
            new Coordinate(1.5, 1.5),
            new Coordinate(1.5, 0.5),
            new Coordinate(0.5, 0.5)
        });
        filter = buildFilterByRoi(recordDef, testList, roi);
        checkFilteredResults(filter, testList);
        
        // FOIs 1 + 3
        testList.clear();
        testList.addAll(dataList);
        roi = new GeometryFactory().createPolygon(new Coordinate[] {
            new Coordinate(0.5, 0.5),
            new Coordinate(0.5, 3.5),
            new Coordinate(3.5, 3.5),
            new Coordinate(3.5, 2.5),
            new Coordinate(1.5, 2.5),
            new Coordinate(1.5, 0.5),
            new Coordinate(0.5, 0.5)
        });
        filter = buildFilterByRoi(recordDef, testList, roi);
        checkFilteredResults(filter, testList);
        
        // FOIs 1-4
        testList.clear();
        testList.addAll(dataList);
        roi = new GeometryFactory().createPolygon(new Coordinate[] {
            new Coordinate(0.0, 0.0),
            new Coordinate(0.0, 4.0),
            new Coordinate(4.0, 4.0),
            new Coordinate(4.0, 0.0),
            new Coordinate(0.0, 0.0)
        });
        filter = buildFilterByRoi(recordDef, testList, roi);
        checkFilteredResults(filter, testList);
    }
    
    
    @Test
    public void testGetFoiExtent() throws Exception
    {
        Bbox realExtent = new Bbox();
        addFoisToStorage(realExtent);
        
        Bbox foiExtent = storage.getFoisSpatialExtent();        
        assertTrue("FOI spatial extent is null", foiExtent != null);
        
        // Storage bounding box may be larger than real extent, but not smaller
        assertTrue("Wrong FOI spatial extent", foiExtent.contains(realExtent));
    }
    
    
    @Test
    public void testGetFoiTimeRanges() throws Exception
    {
        addFoisToStorage(null);
        
        DataComponent recordDef = createDs2();
        addObservationsWithFoiToStorage(recordDef);
        
        Iterator<ObsPeriod> timeRanges = storage.getFoiTimeRanges(new ObsFilter(recordDef.getName()));
        assertTrue("No FOI times returned", timeRanges != null && timeRanges.hasNext());
        
        double timeStep = 0.1;
        while (timeRanges.hasNext())
        {
            ObsPeriod p = timeRanges.next();
            int foiIdx = Arrays.asList(FOI_IDS).indexOf(p.foiID);
            assertEquals(FOI_STARTS[foiIdx]*timeStep, p.begin, 1e-12);
            if (foiIdx < FOI_STARTS.length-1)
                assertEquals((FOI_STARTS[foiIdx+1]-1)*timeStep, p.end, 1e-12);
        }
    }
    
    
    @Test
    public void testGetNumMatchingRecordsWithOneFoi() throws Exception
    {
        DataComponent recordDef = createDs2();
        List<DataBlock> dataList = addObservationsWithFoiToStorage(recordDef);
        List<DataBlock> testList = new ArrayList<DataBlock>(dataList.size());
        
        for (int foiIndex = 0; foiIndex < FOI_IDS.length-1; foiIndex++)
        {
            testList.clear();
            testList.addAll(dataList);
            IObsFilter filter = buildFilterByFoiID(recordDef, testList, new int[] {foiIndex});
            
            int expectedCount = FOI_STARTS[foiIndex+1] - FOI_STARTS[foiIndex];
            assertEquals(expectedCount, storage.getNumMatchingRecords(filter, Integer.MAX_VALUE));
        }
    }
}
