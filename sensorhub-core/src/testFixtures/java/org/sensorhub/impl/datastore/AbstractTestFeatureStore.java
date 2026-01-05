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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.namespace.QName;
import net.opengis.gml.v32.AbstractFeature;
import net.opengis.gml.v32.Envelope;
import net.opengis.gml.v32.LinearRing;
import net.opengis.gml.v32.Point;
import net.opengis.gml.v32.Polygon;
import net.opengis.gml.v32.impl.GMLFactory;
import org.junit.Before;
import org.junit.Test;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.datastore.feature.FeatureFilter;
import org.sensorhub.api.datastore.feature.FeatureKey;
import org.sensorhub.api.datastore.feature.IFeatureStoreBase;
import org.sensorhub.api.datastore.feature.IFeatureStoreBase.FeatureField;
import org.vast.ogc.gml.GMLUtils;
import org.vast.ogc.gml.GenericFeatureImpl;
import org.vast.ogc.gml.GenericTemporalFeatureImpl;
import org.vast.ogc.gml.IFeature;
import org.vast.ogc.om.SamplingPoint;
import org.vast.util.Bbox;
import org.vast.util.TimeExtent;
import com.google.common.collect.Range;
import com.google.common.collect.Sets;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;


/**
 * <p>
 * Abstract base for testing implementations of IFeatureStore.
 * </p>
 *
 * @author Alex Robin
 * @param <StoreType> type of datastore under test
 * @since Apr 14, 2018
 */
public abstract class AbstractTestFeatureStore<StoreType extends IFeatureStoreBase<IFeature, FeatureField, FeatureFilter>>
{
    protected static int DATABASE_NUM = 31;
    protected String DATASTORE_NAME = "test-features";
    protected String UID_PREFIX = "urn:domain:features:";
    protected int NUM_TIME_ENTRIES_PER_FEATURE = 5;
    protected OffsetDateTime FIRST_VERSION_TIME = OffsetDateTime.parse("2000-01-01T00:00:00Z");
        
    protected StoreType featureStore;
    protected GMLFactory gmlFac = new GMLFactory(true);
    protected Map<FeatureKey, IFeature> allFeatures = new LinkedHashMap<>();
    protected Map<FeatureKey, BigId> allParents = new LinkedHashMap<>();
    protected boolean useAdd;
    protected String[] featureTypes = {"building", "road", "waterbody"};
    
    
    protected abstract StoreType initStore() throws Exception;
    protected abstract void forceReadBackFromStorage() throws Exception;
    
    
    @Before
    public void init() throws Exception
    {
        this.featureStore = initStore();
    }
    
    
    protected void setCommonFeatureProperties(AbstractFeature f, int num)
    {
        String idPrefix = "F";
        if (f.getValidTime() != null)
            idPrefix += "T";
        if (f.isSetGeometry())
            idPrefix += "G";
        
        f.setId(idPrefix + num);
        f.setUniqueIdentifier(UID_PREFIX + idPrefix + num);
        f.setName("Feature " + num);
        f.setDescription("This is " + featureTypes[num%3] +  " #" + num);
    }
    
    
    protected FeatureKey getKey(IFeature f)
    {
        Instant validStartTime = f.getValidTime() != null ?
            f.getValidTime().begin() :
            Instant.MIN;
        
        var id = Long.parseLong(((AbstractFeature)f).getId().replaceAll("(F|G|T)*", ""))+1;
        return new FeatureKey(DATABASE_NUM, id, validStartTime);
    }
    
    
    protected FeatureKey addOrPutFeature(BigId parentID, AbstractFeature f) throws Exception
    {
        //System.out.println("Adding " + f.getId());
        FeatureKey key = null;
        
        if (useAdd) {
            key = featureStore.add(parentID, f);
        }
        else {
            key = getKey(f);
            featureStore.put(key, f);
        }
        
        //System.out.println(key);
        allFeatures.put(key, f);
        allParents.put(key, parentID);
        return key;
    }
    
    
    protected BigId addFeatureCollection(String uidSuffix, String name) throws Exception
    {
        return addFeatureCollection(BigId.NONE, uidSuffix, name);
    }
    
    
    protected BigId addFeatureCollection(BigId parentID, String uidSuffix, String name) throws Exception
    {
        QName fType = new QName("http://mydomain/features", "FeatureCollection");
        AbstractFeature f = new GenericFeatureImpl(fType);
        f.setName(name);
        f.setUniqueIdentifier(UID_PREFIX + uidSuffix);
        
        var fk = featureStore.add(f);
        allFeatures.put(fk, f);
        allParents.put(fk, BigId.NONE);
        return fk.getInternalID();
    }
    
    
    protected void addNonGeoFeatures(int startIndex, int numFeatures) throws Exception
    {
        addNonGeoFeatures(BigId.NONE, startIndex, numFeatures);
    }
    
    
    protected void addNonGeoFeatures(BigId parentID, int startIndex, int numFeatures) throws Exception
    {
        QName fType = new QName("http://mydomain/features", "MyFeature");
        
        long t0 = System.currentTimeMillis();
        for (int i = startIndex; i < startIndex+numFeatures; i++)
        {
            AbstractFeature f = new GenericFeatureImpl(fType);
            setCommonFeatureProperties(f, i);
            addOrPutFeature(parentID, f);
        }
        long t1 = System.currentTimeMillis();
        
        System.out.println("Inserted " + numFeatures + " features in " + (t1-t0) + "ms" +
            " starting at #" + startIndex);
    }
    
    
    protected void addGeoFeaturesPoint2D(int startIndex, int numFeatures) throws Exception
    {
        addGeoFeaturesPoint2D(BigId.NONE, startIndex, numFeatures);
    }
    
    
    protected void addGeoFeaturesPoint2D(BigId parentID, int startIndex, int numFeatures) throws Exception
    {
        QName fType = new QName("http://mydomain/features", "MyPointFeature");
        
        long t0 = System.currentTimeMillis();
        for (int i = startIndex; i < startIndex+numFeatures; i++)
        {
            AbstractFeature f = new GenericFeatureImpl(fType);
            Point p = gmlFac.newPoint();
            p.setPos(new double[] {i, i, 0.0});
            f.setGeometry(p);
            setCommonFeatureProperties(f, i);
            addOrPutFeature(parentID, f);
        }
        long t1 = System.currentTimeMillis();
        
        System.out.println("Inserted " + numFeatures + " point features in " + (t1-t0) + "ms" +
            " starting at #" + startIndex);
    }
    
    
    protected void addSamplingPoints2D(int startIndex, int numFeatures) throws Exception
    {
        addSamplingPoints2D(BigId.NONE, startIndex, numFeatures);
    }
    
    
    protected void addSamplingPoints2D(BigId parentID, int startIndex, int numFeatures) throws Exception
    {
        long t0 = System.currentTimeMillis();
        for (int i = startIndex; i < startIndex+numFeatures; i++)
        {
            SamplingPoint sp = new SamplingPoint();
            Point p = gmlFac.newPoint();
            p.setPos(new double[] {0.5*i, 1.5*i, 0.0});
            sp.setShape(p);
            setCommonFeatureProperties(sp, i);
            addOrPutFeature(parentID, sp);
        }
        long t1 = System.currentTimeMillis();
        
        System.out.println("Inserted " + numFeatures + " sampling point features in " + (t1-t0) + "ms" +
            " starting at #" + startIndex);
    }
    
    
    protected void addTemporalFeatures(int startIndex, int numFeatures) throws Exception
    {
        addTemporalFeatures(BigId.NONE, startIndex, numFeatures);
    }
    
    
    protected void addTemporalFeatures(int startIndex, int numFeatures, OffsetDateTime startTime) throws Exception
    {
        addTemporalFeatures(BigId.NONE, startIndex, numFeatures, startTime);
    }
    
    
    protected void addTemporalFeatures(BigId parentID, int startIndex, int numFeatures) throws Exception
    {
        addTemporalFeatures(parentID, startIndex, numFeatures, FIRST_VERSION_TIME);
    }
    
    
    protected void addTemporalFeatures(BigId parentID, int startIndex, int numFeatures, OffsetDateTime startTime) throws Exception
    {
        addTemporalFeatures(parentID, startIndex, numFeatures, startTime, false); 
    }
    
    
    protected void addTemporalFeatures(BigId parentID, int startIndex, int numFeatures, OffsetDateTime startTime, boolean endNow) throws Exception
    {
        QName fType = new QName("http://mydomain/features", "MyTimeFeature");
        var now = Instant.now().atOffset(ZoneOffset.UTC);
        
        long t0 = System.currentTimeMillis();
        for (int i = startIndex; i < startIndex+numFeatures; i++)
        {
            // add feature with 5 different time periods
            for (int j = 0; j < NUM_TIME_ENTRIES_PER_FEATURE; j++)
            {
                GenericTemporalFeatureImpl f = new GenericTemporalFeatureImpl(fType);
                OffsetDateTime beginTime = startTime.plus(j*30, ChronoUnit.DAYS).plus(i, ChronoUnit.HOURS);
                OffsetDateTime endTime = endNow && now.isAfter(beginTime) ? now : beginTime.plus(30, ChronoUnit.DAYS);
                f.setValidTimePeriod(beginTime, endTime);
                setCommonFeatureProperties(f, i);
                addOrPutFeature(parentID, f);
            }
        }
        long t1 = System.currentTimeMillis();
        
        System.out.println("Inserted " + numFeatures + " temporal features in " + (t1-t0) + "ms" +
            " starting at #" + startIndex);
    }
    
    
    protected void addTemporalGeoFeatures(int startIndex, int numFeatures) throws Exception
    {
        addTemporalGeoFeatures(BigId.NONE, startIndex, numFeatures);
    }
    
    
    protected void addTemporalGeoFeatures(BigId parentID, int startIndex, int numFeatures) throws Exception
    {
        QName fType = new QName("http://mydomain/features", "MyGeoTimeFeature");
        
        long t0 = System.currentTimeMillis();
        for (int i = startIndex; i < startIndex+numFeatures; i++)
        {
            // add feature with 5 different time periods
            for (int j = 0; j < NUM_TIME_ENTRIES_PER_FEATURE; j++)
            {
                GenericTemporalFeatureImpl f = new GenericTemporalFeatureImpl(fType);
                OffsetDateTime beginTime = FIRST_VERSION_TIME.plus(j*30, ChronoUnit.DAYS).plus(i, ChronoUnit.HOURS);
                OffsetDateTime endTime = beginTime.plus(30, ChronoUnit.DAYS);
                f.setValidTimePeriod(beginTime, endTime);
                LinearRing ring = gmlFac.newLinearRing();
                double minx = i*10;
                double miny = j*10;
                ring.setPosList(new double[] {minx, miny, minx+2, miny, minx+2, miny+2, minx, miny+2});
                Polygon shape = gmlFac.newPolygon();
                shape.setExterior(ring);
                f.setGeometry(shape);
                setCommonFeatureProperties(f, i);
                addOrPutFeature(parentID, f);
            }
        }
        long t1 = System.currentTimeMillis();
        
        System.out.println("Inserted " + numFeatures + " spatio temporal features in " + (t1-t0) + "ms" +
            " starting at #" + startIndex);
    }
    
    
    @Test
    public void testGetDatastoreName() throws Exception
    {
        assertEquals(DATASTORE_NAME, featureStore.getDatastoreName());
        
        forceReadBackFromStorage();
        assertEquals(DATASTORE_NAME, featureStore.getDatastoreName());
    }
    
    
    @Test
    public void testPutAndGetNumRecords() throws Exception
    {
        int numFeatures = 100;
        addNonGeoFeatures(1, numFeatures);
        featureStore.commit();
        assertEquals(allFeatures.size(), featureStore.getNumRecords());
        
        numFeatures = 120;
        addGeoFeaturesPoint2D(1000, numFeatures);
        featureStore.commit();
        forceReadBackFromStorage();
        assertEquals(allFeatures.size(), featureStore.getNumRecords());
        
        numFeatures = 40;
        addTemporalFeatures(2000, numFeatures);
        featureStore.commit();
        assertEquals(allFeatures.size(), featureStore.getNumRecords());
    }
    
    
    @Test
    public void testPutAndGetNumFeatures() throws Exception
    {
        int totalFeatures = 0;
        
        int numFeatures = 100;
        addNonGeoFeatures(1, numFeatures);
        featureStore.commit();
        assertEquals(totalFeatures += numFeatures, featureStore.getNumFeatures());
        
        numFeatures = 120;
        addGeoFeaturesPoint2D(1000, numFeatures);
        forceReadBackFromStorage();
        featureStore.commit();
        assertEquals(totalFeatures += numFeatures, featureStore.getNumFeatures());
        
        numFeatures = 40;
        addTemporalFeatures(2000, numFeatures);
        featureStore.commit();
        assertEquals(totalFeatures += numFeatures, featureStore.getNumFeatures());
    }
    
    
    private void checkFeaturesBbox(Bbox bbox)
    {
        Bbox expectedBbox = new Bbox();
        for (IFeature f: allFeatures.values())
        {
            if (f.getGeometry() != null)
            {
                Envelope env = f.getGeometry().getGeomEnvelope();
                expectedBbox.add(GMLUtils.envelopeToBbox(env));
            }
        }
        
        assertTrue(bbox.contains(expectedBbox));
        assertEquals(expectedBbox.getMinX(), bbox.getMinX(), 1e-4);
        assertEquals(expectedBbox.getMinY(), bbox.getMinY(), 1e-4);
        assertEquals(expectedBbox.getMaxX(), bbox.getMaxX(), 1e-4);
        assertEquals(expectedBbox.getMaxY(), bbox.getMaxY(), 1e-4);
    }
    
    
    @Test
    public void testGetFeaturesBbox() throws Exception
    {
        //addNonGeoFeatures(1, 40);
        //checkFeaturesBbox(featureStore.getFeaturesBbox());
        
        addGeoFeaturesPoint2D(100, 25);
        featureStore.commit();
        checkFeaturesBbox(featureStore.getFeaturesBbox());
        forceReadBackFromStorage();
        
        addSamplingPoints2D(200, 50);
        featureStore.commit();
        checkFeaturesBbox(featureStore.getFeaturesBbox());
        forceReadBackFromStorage();
        checkFeaturesBbox(featureStore.getFeaturesBbox());
    }
    
    
    private void checkMapKeySet(Set<FeatureKey> keySet)
    {
        keySet.forEach(k -> {
            if (!allFeatures.containsKey(k))
                fail("No matching key in reference list: " + k);
        });
        
        allFeatures.keySet().forEach(k -> {
            if (!keySet.contains(k))
                fail("No matching key in datastore: " + k);
        });
    }
    
    
    @Test
    public void testMapKeySet() throws Exception
    {
        addGeoFeaturesPoint2D(100, 25);
        featureStore.commit();
        checkMapKeySet(featureStore.keySet());
        
        addTemporalFeatures(200, 30);
        featureStore.commit();
        checkMapKeySet(featureStore.keySet());
    }
    
    
    private void checkMapValues(Collection<IFeature> mapValues)
    {
        mapValues.forEach(f1 -> {
            IFeature f2 = allFeatures.get(getKey(f1));
            if (f2 == null || !f2.getUniqueIdentifier().equals(f1.getUniqueIdentifier()))
                fail("No matching feature in reference list: " + f1);
        });
    }
    
    
    @Test
    public void testMapValues() throws Exception
    {
        addGeoFeaturesPoint2D(100, 25);
        featureStore.commit();
        checkMapValues(featureStore.values());
    }
    
    
    protected void checkFeaturesEqual(IFeature f1, IFeature f2)
    {
        //assertEquals(f1.getClass(), f2.getClass());
        assertEquals(f1.getUniqueIdentifier(), f2.getUniqueIdentifier());
        assertEquals(f1.getValidTime(), f2.getValidTime());
        assertEquals(f1.getGeometry(), f2.getGeometry());
    }
    
    
    protected void getAndCheckFeatures() throws Exception
    {
        long t0 = System.currentTimeMillis();
        allFeatures.forEach((k, f1) -> {
            IFeature f2 = featureStore.get(k);
            assertTrue("Feature " + k + " not found in datastore", f2 != null);
            checkFeaturesEqual(f1, f2);
        });
        System.out.println(String.format("%d features fetched in %d ms", allFeatures.size(), System.currentTimeMillis()-t0));
    }
    
    
    @Test
    public void testPutAndGetByKey() throws Exception
    {
        addTemporalFeatures(50, 63);
        addGeoFeaturesPoint2D(700, 33);
        featureStore.commit();
        forceReadBackFromStorage();
        getAndCheckFeatures();
        
        addTemporalFeatures(200, 22);
        featureStore.commit();
        getAndCheckFeatures();
        addNonGeoFeatures(1, 40);
        featureStore.commit();
        getAndCheckFeatures();
        
        forceReadBackFromStorage();
        getAndCheckFeatures();
    }
    
    
    @Test
    public void testAddAndGetByKey() throws Exception
    {
        useAdd = true;
        
        addGeoFeaturesPoint2D(10, 33);
        featureStore.commit();
        assertEquals(allFeatures.size(), featureStore.size());
        getAndCheckFeatures();
        
        addSamplingPoints2D(44, 5);
        featureStore.commit();
        assertEquals(allFeatures.size(), featureStore.size());
        getAndCheckFeatures();
        
        addNonGeoFeatures(50, 22);
        featureStore.commit();
        forceReadBackFromStorage();
        getAndCheckFeatures();
        
        addTemporalFeatures(80, 22);
        featureStore.commit();
        forceReadBackFromStorage();
        getAndCheckFeatures();
    }
    
    
    @Test
    public void testGetCurrentVersion() throws Exception
    {
        var shiftFromCurrentTime = 2*30; // in days
        int numFeatures = 20;
        
        var startTime = OffsetDateTime.now(ZoneOffset.UTC)
            .minusDays(shiftFromCurrentTime+15).truncatedTo(ChronoUnit.HOURS);
        addTemporalFeatures(0, numFeatures, startTime);
        featureStore.commit();
        forceReadBackFromStorage();
        
        for (int i = 0; i < numFeatures; i++)
        {
            var id = bigId(i+1);
            var expectedCurrentVersionTime = startTime.plusDays(shiftFromCurrentTime).plusHours(i);
            
            var fk = featureStore.getCurrentVersionKey(id);
            assertEquals(expectedCurrentVersionTime.toInstant(), fk.getValidStartTime());
            
            fk = featureStore.getCurrentVersionKey(UID_PREFIX + "FT" + i);
            assertEquals(expectedCurrentVersionTime.toInstant(), fk.getValidStartTime());
            
            var f = featureStore.getCurrentVersion(id);
            assertEquals(expectedCurrentVersionTime.toInstant(), f.getValidTime().begin());
            
            f = featureStore.getCurrentVersion(UID_PREFIX + "FT" + i);
            assertEquals(expectedCurrentVersionTime.toInstant(), f.getValidTime().begin());
        }
    }
    
    
    private void checkRemoveAllKeys()
    {
        long t0 = System.currentTimeMillis();
        allFeatures.forEach((k, f) -> {
            assertTrue(featureStore.containsKey(k));
            featureStore.remove(k);
            assertFalse(featureStore.containsKey(k));
            assertTrue(featureStore.get(k) == null);
        });
        System.out.println(String.format("%d features removed in %d ms", allFeatures.size(), System.currentTimeMillis()-t0));
        
        assertTrue(featureStore.isEmpty());
        assertTrue(featureStore.getNumRecords() == 0);
        assertTrue(featureStore.getNumFeatures() == 0);
        allFeatures.clear();
    }
    
    
    @Test
    public void testRemoveByKey() throws Exception
    {
        //addGeoFeaturesPoint2D(100, 25);
        //testRemoveAllKeys();
        
        addTemporalFeatures(200, 30);
        featureStore.commit();
        forceReadBackFromStorage();
        checkRemoveAllKeys();
        
        forceReadBackFromStorage();
        addNonGeoFeatures(1, 40);
        featureStore.commit();
        checkRemoveAllKeys();
        
        forceReadBackFromStorage();
        checkRemoveAllKeys();
    }
    
    
    protected void checkSelectedEntries(FeatureFilter filter, Stream<Entry<FeatureKey, IFeature>> resultStream, Map<FeatureKey, IFeature> expectedResults)
    {
        if (filter != null)
            System.out.println("\nSelect with " + filter);
        
        Map<FeatureKey, IFeature> resultMap = resultStream
            .peek(e -> System.out.println(e.getKey() + ": " + e.getValue().getUniqueIdentifier()))
            .collect(Collectors.toMap(e->e.getKey(), e->e.getValue()));
        System.out.println("Selected " + resultMap.size() + " entries");
        
        resultMap.forEach((k, v) -> {
            assertEquals("Invalid scope", DATABASE_NUM, k.getInternalID().getScope());
            if (!expectedResults.containsKey(k))
                fail("Result contains unexpected entry: " + k);
        });
        
        expectedResults.entrySet().stream()
            .forEach(e -> {
                if (!resultMap.containsKey(e.getKey()))
                    fail("Result is missing entry: " + e.getKey());
            });
    }
    
    
    protected void checkSelectedEntries(Stream<Entry<FeatureKey, IFeature>> resultStream, Set<String> expectedIds, Range<Instant> timeRange)
    {
        boolean lastVersion = timeRange.lowerEndpoint() == Instant.MAX && timeRange.upperEndpoint() == Instant.MAX;
        System.out.println("\nSelect " + expectedIds + " within " +  (lastVersion ? "LATEST" : timeRange));
        
        Map<FeatureKey, IFeature> expectedResults = new TreeMap<>();
        allFeatures.entrySet().stream()
            .filter(e -> expectedIds.contains(e.getValue().getUniqueIdentifier()))
            .filter(e -> e.getValue().getValidTime() == null ||
                         timeRange.isConnected(e.getValue().getValidTime().asRange()))
            .forEach(e -> expectedResults.put(e.getKey(), e.getValue()));
        
        checkSelectedEntries(null, resultStream, expectedResults);
    }
    
    
    protected void checkSelectedEntries(Stream<Entry<FeatureKey, IFeature>> resultStream, Geometry roi, Range<Instant> timeRange)
    {
        System.out.println("\nSelect " + roi + " within " + timeRange);
        
        Map<FeatureKey, IFeature> expectedResults = new TreeMap<>();
        allFeatures.entrySet().stream()
            .filter(e -> e.getValue().getGeometry() != null && ((Geometry)e.getValue().getGeometry()).intersects(roi))
            .filter(e -> e.getValue().getValidTime() == null ||
                         timeRange.isConnected(e.getValue().getValidTime().asRange()))
            .forEach(e -> expectedResults.put(e.getKey(), e.getValue()));
        
        checkSelectedEntries(null, resultStream, expectedResults);
    }
    
    
    protected BigId bigId(long id)
    {
        return BigId.fromLong(DATABASE_NUM, id);
    }
    
    
    @Test
    public void testSelectByInternalID() throws Exception
    {
        Stream<Entry<FeatureKey, IFeature>> resultStream;
        Range<Instant> timeRange;
                
        addNonGeoFeatures(0, 50);
        featureStore.commit();
        
        // correct IDs and all times
        var ids = BigId.fromLongs(DATABASE_NUM, 3L, 24L, 43L);
        timeRange = Range.closed(Instant.MIN, Instant.MAX);
        resultStream = featureStore.selectEntries(new FeatureFilter.Builder()
            .withInternalIDs(ids)
            .withValidTimeDuring(timeRange.lowerEndpoint(), timeRange.upperEndpoint())
            .build());
        
        Set<String> expectedUids = ids.stream().map(id -> UID_PREFIX+"F"+(id.getIdAsLong()-1)).collect(Collectors.toSet());
        checkSelectedEntries(resultStream, expectedUids, timeRange);
    }
    
    
    @Test
    public void testSelectByUIDAndTime() throws Exception
    {
        Stream<Entry<FeatureKey, IFeature>> resultStream;
        Set<String> uids;
        Range<Instant> timeRange;
        
        addNonGeoFeatures(0, 50);
        featureStore.commit();
        
        /*// correct UIDs and all times
        uids = Sets.newHashSet(UID_PREFIX+"F10", UID_PREFIX+"F31");
        timeRange = Range.closed(Instant.MIN, Instant.MAX);
        resultStream = featureStore.selectEntries(new FeatureFilter.Builder()
                .withUniqueIDs(uids)
                .withValidTimeDuring(timeRange.lowerEndpoint(), timeRange.upperEndpoint())
                .build());
        checkSelectedEntries(resultStream, uids, timeRange);*/
        
        /*// correct UIDs and time range
        uids = Sets.newHashSet(UID_PREFIX+"F25", UID_PREFIX+"F49");
        timeRange = Range.closed(Instant.parse("2000-04-08T08:59:59Z"), Instant.parse("2000-06-08T07:59:59Z"));
        resultStream = featureStore.selectEntries(new FeatureFilter.Builder()
                .withUniqueIDs(uids)
                .withValidTimeDuring(timeRange.lowerEndpoint(), timeRange.upperEndpoint())
                .build());
        checkSelectedEntries(resultStream, uids, timeRange);*/
        
        addTemporalFeatures(200, 30);
        featureStore.commit();
        
        /*// correct IDs and all times
        uids = Sets.newHashSet(UID_PREFIX+"FT200", UID_PREFIX+"FT201");
        timeRange = Range.closed(Instant.MIN, Instant.MAX);
        resultStream = featureStore.selectEntries(new FeatureFilter.Builder()
                .withUniqueIDs(uids)
                .withValidTimeDuring(timeRange.lowerEndpoint(), timeRange.upperEndpoint())
                .build());
        checkSelectedEntries(resultStream, uids, timeRange);*/
        
        // correct IDs and time range
        uids = Sets.newHashSet(UID_PREFIX+"FT200", UID_PREFIX+"FT201");
        timeRange = Range.closed(Instant.parse("2000-04-08T08:59:59Z"), Instant.parse("2000-06-08T07:59:59Z"));
        resultStream = featureStore.selectEntries(new FeatureFilter.Builder()
                .withUniqueIDs(uids)
                .withValidTimeDuring(timeRange.lowerEndpoint(), timeRange.upperEndpoint())
                .build());
        checkSelectedEntries(resultStream, uids, timeRange);
        
        // correct IDs and future time
        uids = Sets.newHashSet(UID_PREFIX+"FT200", UID_PREFIX+"FT201");
        timeRange = Range.singleton(Instant.parse("2001-04-08T07:59:59Z"));
        resultStream = featureStore.selectEntries(new FeatureFilter.Builder()
                .withUniqueIDs(uids)
                .validAtTime(timeRange.lowerEndpoint())
                .build());
        checkSelectedEntries(resultStream, uids, timeRange);
        
        // mix of correct and wrong IDs
        uids = Sets.newHashSet(UID_PREFIX+"FT300", UID_PREFIX+"FT201");
        timeRange = Range.singleton(Instant.MAX);
        resultStream = featureStore.selectEntries(new FeatureFilter.Builder()
                .withUniqueIDs(uids)
                .withCurrentVersion()
                .build());
        checkSelectedEntries(resultStream, uids, timeRange);
        
        // only wrong IDs
        uids = Sets.newHashSet(UID_PREFIX+"FT300", UID_PREFIX+"FT301");
        timeRange = Range.singleton(Instant.MAX);
        resultStream = featureStore.selectEntries(new FeatureFilter.Builder()
                .withUniqueIDs(uids)
                .withCurrentVersion()
                .build());
        checkSelectedEntries(resultStream, uids, timeRange);
    }
    
    
    @Test
    public void testSelectByUIDWithWildcard() throws Exception
    {
        Stream<Entry<FeatureKey, IFeature>> resultStream;
        
        addNonGeoFeatures(0, 50);
        featureStore.commit();
        
        // correct UIDs and all times
        var uidsWithWildcard = Sets.newHashSet(UID_PREFIX+"F1*", UID_PREFIX+"F3*");
        var expectedUids = Sets.newHashSet(UID_PREFIX+"F1", UID_PREFIX+"F3");
        for (int i=0; i<10; i++) {
            expectedUids.add(UID_PREFIX+"F1"+i);
            expectedUids.add(UID_PREFIX+"F3"+i);
        }
        resultStream = featureStore.selectEntries(new FeatureFilter.Builder()
                .withUniqueIDs(uidsWithWildcard)
                .withAllVersions()
                .build());
        checkSelectedEntries(resultStream, expectedUids, Range.closed(Instant.MIN, Instant.MAX));
    }
    
    
    @Test
    public void testSelectByRoi() throws Exception
    {
        Stream<Entry<FeatureKey, IFeature>> resultStream;
        Geometry roi;
        Range<Instant> timeRange;
        
        addTemporalGeoFeatures(0, 30);
        featureStore.commit();
        featureStore.keySet().forEach(System.out::println);
        addSamplingPoints2D(30, 30); // overlap IDs on purpose
        featureStore.commit();
        featureStore.keySet().forEach(System.out::println);
        
        // containing polygon and all times
        roi = new GeometryFactory().createPolygon(new Coordinate[] {
            new Coordinate(0, 0),
            new Coordinate(500, 0),
            new Coordinate(500, 100),
            new Coordinate(0, 100),
            new Coordinate(0, 0)
        });
        timeRange = Range.closed(Instant.MIN, Instant.MAX);
        resultStream = featureStore.selectEntries(new FeatureFilter.Builder()
            .withLocationIntersecting((org.locationtech.jts.geom.Polygon)roi)
            .withValidTimeDuring(timeRange.lowerEndpoint(), timeRange.upperEndpoint())
            .build());
        checkSelectedEntries(resultStream, roi, timeRange);
        
        // containing polygon and time range
        forceReadBackFromStorage();
        timeRange = Range.closed(Instant.parse("2000-02-28T09:59:59Z"), Instant.parse("2000-04-08T10:00:00Z"));
        resultStream = featureStore.selectEntries(new FeatureFilter.Builder()
            .withLocationIntersecting((org.locationtech.jts.geom.Polygon)roi)
            .withValidTimeDuring(timeRange.lowerEndpoint(), timeRange.upperEndpoint())
            .build());
        checkSelectedEntries(resultStream, roi, timeRange);
        
        // smaller polygon and all times
        roi = new GeometryFactory().createPolygon(new Coordinate[] {
            new Coordinate(0, 0),
            new Coordinate(200, 0),
            new Coordinate(200, 29.9),
            new Coordinate(0, 29.9),
            new Coordinate(0, 0)
        });
        timeRange = Range.closed(Instant.MIN, Instant.MAX);        
        resultStream = featureStore.selectEntries(new FeatureFilter.Builder()
            .withLocationIntersecting((org.locationtech.jts.geom.Polygon)roi)
            .withValidTimeDuring(timeRange.lowerEndpoint(), timeRange.upperEndpoint())
            .build());
        checkSelectedEntries(resultStream, roi, timeRange);
        
        // smaller polygon and time range
        timeRange = Range.closed(Instant.parse("2000-02-28T09:59:59Z"), Instant.parse("2000-04-08T10:00:00Z"));
        resultStream = featureStore.selectEntries(new FeatureFilter.Builder()
            .withLocationIntersecting((org.locationtech.jts.geom.Polygon)roi)
            .withValidTimeDuring(timeRange.lowerEndpoint(), timeRange.upperEndpoint())
            .build());
        checkSelectedEntries(resultStream, roi, timeRange);
    }


    @Test
    public void testAddAndRemoveByTimeRange() throws Exception
    {
        addTemporalGeoFeatures(0, 20);
        featureStore.commit();
        
        var timeFilter = TimeExtent.period(FIRST_VERSION_TIME.toInstant(), Instant.parse("2000-04-08T10:00:00Z"));
        long count = featureStore.removeEntries(new FeatureFilter.Builder()
            .withValidTimeDuring(timeFilter)
            .build());
        System.out.println(count + " features removed");

        forceReadBackFromStorage();
        // generate truth by removing entries from allFeatures map
        var it = allFeatures.values().iterator();
        while (it.hasNext())
        {
            var f = it.next();
            if (f.getValidTime().begin().isBefore(timeFilter.end()))
                it.remove();
        }
        
        var resultStream = featureStore.selectEntries(featureStore.selectAllFilter());
        checkSelectedEntries(null, resultStream, allFeatures);
    }


    @Test
    public void testAddAndRemoveByRoi() throws Exception
    {
        addTemporalGeoFeatures(0, 20);
        featureStore.commit();
        
        // remove all features intersecting polygon
        var roi = new GeometryFactory().createPolygon(new Coordinate[] {
            new Coordinate(0, 0),
            new Coordinate(500, 0),
            new Coordinate(500, 100),
            new Coordinate(0, 100),
            new Coordinate(0, 0)
        });
        
        long count = featureStore.removeEntries(new FeatureFilter.Builder()
            .withLocationIntersecting((org.locationtech.jts.geom.Polygon)roi)
            .build());
        System.out.println(count + " features removed");
        forceReadBackFromStorage();
        
        // generate truth by removing entries from allFeatures map
        var preparedGeom = PreparedGeometryFactory.prepare(roi);
        var it = allFeatures.values().iterator();
        while (it.hasNext())
        {
            var f = it.next();
            if (preparedGeom.intersects((Geometry)f.getGeometry()))
                it.remove();
        }
        
        var resultStream = featureStore.selectEntries(featureStore.selectAllFilter());
        checkSelectedEntries(null, resultStream, allFeatures);
    }
            
    
    @Test
    public void testSelectByParentID() throws Exception
    {
        useAdd = true;
        BigId group1Id = addFeatureCollection("col1", "collection 1");
        forceReadBackFromStorage();
        BigId group2Id = addFeatureCollection("col2", "collection 2");
        forceReadBackFromStorage();
        BigId group3Id = addFeatureCollection("col3", "collection 3");
        forceReadBackFromStorage();
        addGeoFeaturesPoint2D(group1Id, 0, 20);
        addNonGeoFeatures(group2Id, 40, 35);
        forceReadBackFromStorage();
        addTemporalGeoFeatures(group3Id, 100, 46);
        featureStore.commit();
        
        var filter = new FeatureFilter.Builder()
            .withParents(group1Id, group3Id)
            .build();
        
        Map<FeatureKey, IFeature> expectedResults = allFeatures.entrySet().stream()
            .filter(e -> {
                var parentID = allParents.get(e.getKey());
                return parentID.equals(group1Id) || parentID.equals(group3Id);
            })
            .collect(Collectors.toMap(e->e.getKey(), e->e.getValue()));
        
        var resultStream = featureStore.selectEntries(filter);
        checkSelectedEntries(filter, resultStream, expectedResults);
    }
    
    
    @Test
    public void testSelectByParentIDAndTime() throws Exception
    {
        useAdd = true;
        BigId group1Id = addFeatureCollection("col1", "collection 1");
        forceReadBackFromStorage();
        BigId group2Id = addFeatureCollection("col2", "collection 2");
        forceReadBackFromStorage();
        BigId group3Id = addFeatureCollection("col3", "collection 3");
        forceReadBackFromStorage();
        addGeoFeaturesPoint2D(group1Id, 0, 20);
        addNonGeoFeatures(group2Id, 40, 35);
        addTemporalGeoFeatures(group3Id, 100, 46);
        addTemporalFeatures(group2Id, 200, 40, OffsetDateTime.now().minusDays(90), false);
        addTemporalFeatures(BigId.NONE, 300, 10, OffsetDateTime.now().minusDays(110), true);
        featureStore.commit();
        
        // select with parent and time range
        var timeRange1 = Range.closed(
            FIRST_VERSION_TIME.toInstant(),
            FIRST_VERSION_TIME.toInstant().plus(70, ChronoUnit.DAYS));
        
        var filter1 = new FeatureFilter.Builder()
            .withParents(group1Id, group3Id)
            .withValidTimeDuring(timeRange1.lowerEndpoint(), timeRange1.upperEndpoint())
            .build();
        
        Map<FeatureKey, IFeature> expectedResults = allFeatures.entrySet().stream()
            .filter(e -> {
                var parentID = allParents.get(e.getKey());
                return parentID.equals(group1Id) || parentID.equals(group3Id);
            })
            .filter(e -> {
                return e.getValue().getValidTime() == null ||
                    timeRange1.isConnected(e.getValue().getValidTime().asRange());
            })
            .collect(Collectors.toMap(e->e.getKey(), e->e.getValue()));
        
        var resultStream = featureStore.selectEntries(filter1);
        checkSelectedEntries(filter1, resultStream, expectedResults);
        
        // select with parent and current time
        var filter2 = new FeatureFilter.Builder()
            .withParents()
                .withInternalIDs(group1Id, group3Id)
                .done()
            .withCurrentVersion()
            .build();
        
        var currentTime = Instant.now();
        expectedResults = allFeatures.entrySet().stream()
            .filter(e -> {
                var parentID = allParents.get(e.getKey());
                return parentID.equals(group1Id) || parentID.equals(group3Id);
            })
            .filter(e -> {
                return e.getValue().getValidTime() == null ||
                    e.getValue().getValidTime().asRange().contains(currentTime);
            })             
            .collect(Collectors.toMap(e->e.getKey(), e->e.getValue()));
        
        resultStream = featureStore.selectEntries(filter2);
        checkSelectedEntries(filter2, resultStream, expectedResults);
        
        // select with parent and time instant
        var timeInstant = FIRST_VERSION_TIME.toInstant().plus(70, ChronoUnit.DAYS);
        var filter3 = new FeatureFilter.Builder()
            .withParents()
                .withInternalIDs(group2Id, group3Id)
                .done()
            .validAtTime(timeInstant)
            .build();
                
        expectedResults = allFeatures.entrySet().stream()
            .filter(e -> {
                var parentID = allParents.get(e.getKey());
                return parentID.equals(group2Id) || parentID.equals(group3Id);
            })
            .filter(e -> {
                return e.getValue().getValidTime() == null ||
                    e.getValue().getValidTime().asRange().contains(timeInstant);
            })             
            .collect(Collectors.toMap(e->e.getKey(), e->e.getValue()));
        
        resultStream = featureStore.selectEntries(filter3);
        checkSelectedEntries(filter3, resultStream, expectedResults);
        
        // no filtering on parent and time range until now
        var timeExtent4 = TimeExtent.period(
            FIRST_VERSION_TIME.toInstant(),
            Instant.now());
        
        var filter4 = new FeatureFilter.Builder()
            .withValidTimeDuring(timeExtent4)
            .build();
                
        expectedResults = allFeatures.entrySet().stream()
            .filter(e -> {
                return e.getValue().getValidTime() == null ||
                    e.getValue().getValidTime().asRange().isConnected(timeExtent4.asRange());
            })             
            .collect(Collectors.toMap(e->e.getKey(), e->e.getValue()));
        
        resultStream = featureStore.selectEntries(filter4);
        checkSelectedEntries(filter4, resultStream, expectedResults);
        
        // no filtering on parent and time range from now
        var timeExtent5 = TimeExtent.period(
            Instant.now(),
            Instant.now().plusSeconds(3600));
        
        var filter5 = new FeatureFilter.Builder()
            .withValidTimeDuring(timeExtent5)
            .build();
                
        expectedResults = allFeatures.entrySet().stream()
            .filter(e -> {
                return e.getValue().getValidTime() == null ||
                    e.getValue().getValidTime().asRange().isConnected(timeExtent5.asRange());
            })             
            .collect(Collectors.toMap(e->e.getKey(), e->e.getValue()));
        
        resultStream = featureStore.selectEntries(filter5);
        checkSelectedEntries(filter5, resultStream, expectedResults);
        
        // no filtering on parent and all times
        var timeExtent6 = TimeExtent.ALL_TIMES;
        
        var filter6 = new FeatureFilter.Builder()
            .withValidTimeDuring(timeExtent6)
            .build();
                
        expectedResults = allFeatures.entrySet().stream()
            .collect(Collectors.toMap(e->e.getKey(), e->e.getValue()));
        
        resultStream = featureStore.selectEntries(filter6);
        checkSelectedEntries(filter6, resultStream, expectedResults);
    }
    
    
    @Test
    public void testSelectByParentUID() throws Exception
    {
        useAdd = true;
        BigId group1Id = addFeatureCollection("col1", "collection 1");
        forceReadBackFromStorage();
        BigId group2Id = addFeatureCollection("col2", "collection 2");
        forceReadBackFromStorage();
        BigId group3Id = addFeatureCollection("col3", "collection 3");
        forceReadBackFromStorage();
        addGeoFeaturesPoint2D(group1Id, 0, 20);
        addNonGeoFeatures(group2Id, 40, 35);
        addTemporalGeoFeatures(group3Id, 100, 46);
        featureStore.commit();
        
        var filter = new FeatureFilter.Builder()
            .withParents()
                .withUniqueIDs(UID_PREFIX + "col3")
                .done()
            .build();
        
        Map<FeatureKey, IFeature> expectedResults = allFeatures.entrySet().stream()
            .filter(e -> {
                var parentID = allParents.get(e.getKey());
                return parentID.equals(group3Id);
            })
            .collect(Collectors.toMap(e->e.getKey(), e->e.getValue()));
        
        var resultStream = featureStore.selectEntries(filter);
        checkSelectedEntries(filter, resultStream, expectedResults);
    }
    
    
    @Test
    public void testSelectByKeywords() throws Exception
    {
        addNonGeoFeatures(40, 35);
        featureStore.commit();
    
        // single keyword
        var filter = new FeatureFilter.Builder()
            .withKeywords(featureTypes[0])
            .build();
        
        Map<FeatureKey, IFeature> expectedResults = allFeatures.entrySet().stream()
            .filter(e -> e.getValue().getDescription().contains(featureTypes[0]))
            .collect(Collectors.toMap(e->e.getKey(), e->e.getValue()));
        
        var resultStream = featureStore.selectEntries(filter);
        checkSelectedEntries(filter, resultStream, expectedResults);
        
        // two keywords
        filter = new FeatureFilter.Builder()
            .withKeywords(featureTypes[0], featureTypes[2])
            .build();
        
        expectedResults = allFeatures.entrySet().stream()
            .filter(e -> e.getValue().getDescription().contains(featureTypes[0]) ||
                e.getValue().getDescription().contains(featureTypes[2]))
            .collect(Collectors.toMap(e->e.getKey(), e->e.getValue()));
        
        resultStream = featureStore.selectEntries(filter);
        checkSelectedEntries(filter, resultStream, expectedResults);
    }
    
    
    @Test
    public void testSelectByRoiAndKeywords() throws Exception
    {
        addGeoFeaturesPoint2D(0, 200);
        featureStore.commit();
    
        var roi = new GeometryFactory().createPolygon(new Coordinate[] {
            new Coordinate(0, 0),
            new Coordinate(500, 0),
            new Coordinate(500, 100),
            new Coordinate(0, 100),
            new Coordinate(0, 0)
        });
        
        // single keyword
        var filter = new FeatureFilter.Builder()
            .withKeywords(featureTypes[0])
            .withLocationIntersecting(roi)
            .build();
        
        Map<FeatureKey, IFeature> expectedResults = allFeatures.entrySet().stream()
            .filter(e -> e.getValue().getDescription().contains(featureTypes[0]) &&
                ((Geometry)e.getValue().getGeometry()).intersects(roi))
            .collect(Collectors.toMap(e->e.getKey(), e->e.getValue()));
        
        var resultStream = featureStore.selectEntries(filter);
        checkSelectedEntries(filter, resultStream, expectedResults);
    }
    
    
    ///////////////////////
    // Performance Tests //
    ///////////////////////
    
    @Test
    public void testPutThroughput() throws Exception
    {
        System.out.println("Write Throughput (put operations)");
        
        // simple features
        int numFeatures = 100000;
        long t0 = System.nanoTime();
        addNonGeoFeatures(0, numFeatures);
        featureStore.commit();
        double dt = System.nanoTime() - t0;
        int throughPut = (int)(numFeatures/dt*1e9);
        System.out.println(String.format("Simple Features: %d writes/s", throughPut));
        assertTrue(throughPut > 20000);        
        
        // sampling features
        numFeatures = 10000;
        t0 = System.nanoTime();
        addSamplingPoints2D(1000000, numFeatures);
        featureStore.commit();
        dt = System.nanoTime() - t0;
        throughPut = (int)(numFeatures/dt*1e9);
        System.out.println(String.format("Sampling-point Features: %d writes/s", throughPut));
        assertTrue(throughPut > 10000);
        
        // geo features w/ polygons
        numFeatures = 10000;
        t0 = System.nanoTime();
        addTemporalGeoFeatures(2000000, numFeatures);
        featureStore.commit();
        dt = System.nanoTime() - t0;
        throughPut = (int)(numFeatures/dt*1e9*NUM_TIME_ENTRIES_PER_FEATURE);
        System.out.println(String.format("Spatio-temporal features: %d writes/s", throughPut));
        assertTrue(throughPut > 10000);
    }
    
    
    @Test
    public void testGetThroughput() throws Exception
    {
        System.out.println("Read Throughput (get operations)");
        
        int numFeatures = 100000;
        addNonGeoFeatures(0, numFeatures);
        featureStore.commit();
        
        // sequential reads
        int numReads = numFeatures;
        long t0 = System.nanoTime();
        for (int i = 0; i < numReads; i++)
        {
            String uid = UID_PREFIX + "F" + i;
            var key = new FeatureKey(bigId(i+1));
            var f = featureStore.get(key);
            assertEquals(uid, f.getUniqueIdentifier());
        }
        double dt = System.nanoTime() - t0;
        int throughPut = (int)(numReads/dt*1e9);
        System.out.println(String.format("Sequential Reads: %d reads/s", throughPut));
        assertTrue(throughPut > 100000);
        
        // random reads
        numReads = 10000;
        t0 = System.nanoTime();
        for (int i = 0; i < numReads; i++)
        {
            int id = (int)(Math.random()*(numFeatures-1));
            String uid = UID_PREFIX + "F" + id;
            var key = new FeatureKey(bigId(id+1));
            var f = featureStore.get(key);
            assertEquals(uid, f.getUniqueIdentifier());
        }
        dt = System.nanoTime() - t0;
        throughPut = (int)(numReads/dt*1e9);
        System.out.println(String.format("Random Reads: %d reads/s", throughPut));
        assertTrue(throughPut > 10000);
    }
    
    
    @Test
    public void testScanThroughput() throws Exception
    {
        System.out.println("Scan Throughput (cursor iteration)");
        
        int numFeatures = 200000;
        addNonGeoFeatures(0, numFeatures);
        featureStore.commit();
        forceReadBackFromStorage();
        
        // warm up
        long count = featureStore.keySet().stream().count();
        
        // key scan
        long t0 = System.nanoTime();
        count = featureStore.keySet().stream().count();
        double dt = System.nanoTime() - t0;
        int throughPut = (int)(count/dt*1e9);
        System.out.println(String.format("Key Scan: %d keys, %d reads/s", count, throughPut));
        assertTrue(throughPut > 1000000);
        
        // entry scan
        t0 = System.nanoTime();
        count = featureStore.entrySet().stream().count();
        dt = System.nanoTime() - t0;
        throughPut = (int)(count/dt*1e9);
        System.out.println(String.format("Entry Scan: %d reads/s", throughPut));
        assertTrue(throughPut > 1000000);
    }
    
    
    @Test
    public void testTemporalFilterThroughput() throws Exception
    {
        System.out.println("Temporal Query Throughput (select operation w/ temporal filter)");
        
        int numFeatures = 20000;
        addTemporalFeatures(0, numFeatures);
        featureStore.commit();
        
        // spatial filter with all features
        Instant date0 = featureStore.keySet().iterator().next().getValidStartTime();
        FeatureFilter filter = new FeatureFilter.Builder()
                .withValidTimeDuring(date0, date0.plus(numFeatures+NUM_TIME_ENTRIES_PER_FEATURE*30*24, ChronoUnit.HOURS))
                .build();
        
        long t0 = System.nanoTime();
        long count = featureStore.selectEntries(filter).count();
        double dt = System.nanoTime() - t0;
        int throughPut = (int)(count/dt*1e9);
        System.out.println(String.format("Entry Stream: %d reads/s", throughPut));
        assertTrue(throughPut > 50000);
        assertEquals(allFeatures.size(), count);
    }
        
    
    @Test
    public void testSpatialFilterThroughput() throws Exception
    {
        System.out.println("Geo Query Throughput (select operation w/ spatial filter)");
        
        int numFeatures = 100000;
        addSamplingPoints2D(0, numFeatures);
        
        // spatial filter with all features
        FeatureFilter filter = new FeatureFilter.Builder()
                .withLocationWithin(featureStore.getFeaturesBbox())
                .build();
        
        long t0 = System.nanoTime();
        long count = featureStore.selectEntries(filter).count();
        double dt = System.nanoTime() - t0;
        int throughPut = (int)(count/dt*1e9);
        System.out.println(String.format("Entry Stream: %d reads/s", throughPut));
        assertTrue(throughPut > 50000);
        assertEquals(numFeatures, count);
        
        // with geo temporal features
        int numFeatures2 = 20000;
        addTemporalGeoFeatures(numFeatures, numFeatures2);
        featureStore.commit();
          
        // spatial filter with all features
        filter = new FeatureFilter.Builder()
                .withValidTimeDuring(Instant.MIN, Instant.MAX)
                .withLocationWithin(featureStore.getFeaturesBbox())
                .build();
        
        t0 = System.nanoTime();
        count = featureStore.selectEntries(filter).count();
        dt = System.nanoTime() - t0;
        throughPut = (int)(count/dt*1e9);
        System.out.println(String.format("Entry Stream: %d reads/s", throughPut));
        assertTrue(throughPut > 50000);
        assertEquals(allFeatures.size(), count);
        
        // many requests with small random bbox
        Bbox bboxAll = featureStore.getFeaturesBbox();
        Bbox selectBbox = new Bbox();
        int numRequests = 1000;
        t0 = System.nanoTime();
        for (int i = 0; i < numRequests; i++)
        {        
            selectBbox.setMinX(bboxAll.getMinX() + bboxAll.getSizeX()*Math.random());
            selectBbox.setMaxX(selectBbox.getMinX() + 1);
            selectBbox.setMinY(bboxAll.getMinY() + bboxAll.getSizeY()*Math.random());
            selectBbox.setMaxY(selectBbox.getMinY() + 1);
            filter = new FeatureFilter.Builder()
                    .withLocationWithin(selectBbox)
                    .build();
            featureStore.selectEntries(filter).count();
        }
                
        dt = System.nanoTime() - t0;
        throughPut = (int)(numRequests/dt*1e9);
        System.out.println(String.format("Random Reads: %d reads/s", throughPut));
        //assertTrue(throughPut > 50000);        
    }
    
    
    @Test(expected = DataStoreException.class)
    public void testErrorAddWithExistingUID() throws Exception
    {
        useAdd = true;
        addNonGeoFeatures(1, 10);
        featureStore.commit();
        addNonGeoFeatures(1, 2);
        featureStore.commit();
    }
    
    
    @Test(expected = DataStoreException.class)
    public void testErrorAddWithInvalidParent() throws Exception
    {
        useAdd = true;
        addNonGeoFeatures(bigId(11000L), 1, 2);
        featureStore.commit();
    }    
    
    
    ///////////////////////
    // Concurrency Tests //
    ///////////////////////
    
    /*long refTime;
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
                        synchronized(AbstractTestFeatureStore.this) {
                            numWrittenRecords += dataList.size();
                        }
                    }
                    catch (Throwable e)
                    {
                        errors.add(e);
                        //exec.shutdownNow();
                    }
                    
                    synchronized(AbstractTestFeatureStore.this) {
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
                            identifierList.addIdentifier2(term);
                            
                            term = smlFac.newTerm();
                            term.setDefinition(SWEHelper.getPropertyUri("ModelNumber"));
                            term.setLabel("Model Number");
                            term.setValue("SENSOR_2365");
                            identifierList.addIdentifier2(term);
                            
                            term = smlFac.newTerm();
                            term.setDefinition(SWEHelper.getPropertyUri("SerialNumber"));
                            term.setLabel("Serial Number");
                            term.setValue("FZEFZE154618989");
                            identifierList.addIdentifier2(term);
                            
                            // generate unique time stamp
                            TimePosition timePos = gmlFac.newTimePosition(startCount + System.currentTimeMillis()/1000.);
                            TimeInstant validTime = gmlFac.newTimeInstant(timePos);
                            system.addValidTimeAsTimeInstant(validTime);
                            
                            // add to storage
                            storage.storeDataSourceDescription(system);
                            //storage.commit();
                            
                            synchronized(AbstractTestFeatureStore.this) {
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
    }*/
}
