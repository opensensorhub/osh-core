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

import org.junit.Test;
import org.sensorhub.api.persistence.IMultiSourceStorage;
import org.sensorhub.api.persistence.IObsStorageModule;


/**
 * <p>
 * Abstract base for testing implementations of {@link IMultiSourceStorage}.
 * The storage needs to be correctly initialized by derived tests in a method
 * tagged with '@Before'.
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @param <StorageType> type of storage under test
 * @since May 29, 2015
 */
public abstract class AbstractTestMultiObsStorage<StorageType extends IObsStorageModule<?>> extends AbstractTestObsStorage<StorageType>
{
    static int NUM_PRODUCERS = 10;
        
    protected AbstractTestMultiObsStorage()
    {
        numFois = 3;
    }
    
    protected void addProducersToStorage() throws Exception
    {
        // add entities
        for (int i = 1; i <= NUM_PRODUCERS; i++)
        {
            String producerID = SENSOR_UID_PREFIX + i;
            ((IMultiSourceStorage<?>)storage).addDataStore(producerID);
        }
    }


    @Override
    public void testCreateDataStores() throws Exception
    {
        addProducersToStorage();
        super.testCreateDataStores();
    }


    @Override
    public void testStoreAndRetrieveFoisByID() throws Exception
    {
        addProducersToStorage();
        super.testStoreAndRetrieveFoisByID();
    }


    @Override
    public void testStoreAndRetrieveFoisWithWrongIDs() throws Exception
    {
        addProducersToStorage();
        super.testStoreAndRetrieveFoisWithWrongIDs();
    }


    @Override
    public void testStoreAndRetrieveFoisByRoi() throws Exception
    {
        addProducersToStorage();
        super.testStoreAndRetrieveFoisByRoi();
    }


    @Override
    public void testStoreOneFoiAndGetRecordsByFoiID() throws Exception
    {
        addProducersToStorage();
        super.testStoreOneFoiAndGetRecordsByFoiID();
    }


    @Override
    public void testGetRecordsForOneFoiID() throws Exception
    {
        addProducersToStorage();
        super.testGetRecordsForOneFoiID();
    }


    @Override
    public void testGetRecordsForMultipleFoiIDs() throws Exception
    {
        addProducersToStorage();
        super.testGetRecordsForMultipleFoiIDs();
    }


    @Override
    public void testGetRecordsForOneFoiIDAndTime() throws Exception
    {
        addProducersToStorage();
        super.testGetRecordsForOneFoiIDAndTime();
    }


    @Override
    public void testGetRecordsForMultipleFoiIDsAndTime() throws Exception
    {
        addProducersToStorage();
        super.testGetRecordsForMultipleFoiIDsAndTime();
    }
    
    
    @Override
    public void testGetRecordsByRoi() throws Exception
    {
        addProducersToStorage();
        super.testGetRecordsByRoi();
    }


    @Override
    public void testStoreAndGetLatestSensorML() throws Exception
    {
        addProducersToStorage();
        super.testStoreAndGetLatestSensorML();
    }


    @Override
    public void testStoreAndGetSensorMLByTime() throws Exception
    {
        addProducersToStorage();
        super.testStoreAndGetSensorMLByTime();
    }


    @Override
    public void testStoreAndGetRecordsByKey() throws Exception
    {
        addProducersToStorage();
        super.testStoreAndGetRecordsByKey();
    }


    @Override
    public void testStoreAndGetMultipleRecordsByKey() throws Exception
    {
        addProducersToStorage();
        super.testStoreAndGetMultipleRecordsByKey();
    }


    @Override
    public void testStoreAndGetMultipleRecordsByFilter() throws Exception
    {
        addProducersToStorage();
        super.testStoreAndGetMultipleRecordsByFilter();
    }


    @Override
    public void testStoreAndGetTimeRange() throws Exception
    {
        addProducersToStorage();
        super.testStoreAndGetTimeRange();
    }


    @Override
    public void testStoreIncompatibleRecord() throws Exception
    {
        addProducersToStorage();
        super.testStoreIncompatibleRecord();
    }
    
    
    @Test
    public void testStoreAndRemoveRecordsByKey() throws Exception
    {
        addProducersToStorage();
        super.testStoreAndRemoveRecordsByKey();
    }
    
    
    @Test
    public void testStoreAndRemoveRecordsByFilter() throws Exception
    {
        addProducersToStorage();
        super.testStoreAndRemoveRecordsByFilter();
    }


    @Override
    public void testConcurrentWriteRecords() throws Throwable
    {
        addProducersToStorage();
        super.testConcurrentWriteRecords();
    }
    
    
    @Override
    public void testConcurrentWriteThenReadRecords() throws Throwable
    {
        addProducersToStorage();
        super.testConcurrentWriteThenReadRecords();
    }


    @Override
    public void testConcurrentReadWriteMetadataAndRecords() throws Throwable
    {
        addProducersToStorage();
        super.testConcurrentReadWriteMetadataAndRecords();
    }


    @Override
    public void testConcurrentReadWriteRecords() throws Throwable
    {
        addProducersToStorage();
        super.testConcurrentReadWriteRecords();
    }
    
    
    @Override
    @Test
    public void testGetFoiExtent() throws Exception
    {
        addProducersToStorage();
        super.testGetFoiExtent();
    }
    
    
    @Test
    public void testGetFoiTimeRanges() throws Exception
    {
        addProducersToStorage();
        super.testGetFoiTimeRanges();
    }
    
    
    @Test
    public void testGetNumMatchingRecordsWithOneFoi() throws Exception
    {
        addProducersToStorage();
        super.testGetNumMatchingRecordsWithOneFoi();
    }
    
}
