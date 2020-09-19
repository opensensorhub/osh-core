/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.persistence;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.opengis.sensorml.v20.AbstractProcess;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;


/**
 * <p>
 * Storage for one or more data streams coming from a single source.
 * (for instance, the source can be a sensor, a service or a process)<br/> 
 * This also supports storing lineage information if a description of the
 * data source is provided. 
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @since Nov 27, 2014
 */
public interface IBasicStorage
{
    
    /**
     * Retrieves latest data source description (i.e. most recent version)
     * @return SensorML process description
     */
    public AbstractProcess getLatestDataSourceDescription();
    
    
    /**
     * Retrieves history of data source description for the given time period
     * @param startTime lower bound of the time period
     * @param endTime upper bound of the time period
     * @return list of SensorML process descriptions (with disjoint time validity periods) 
     */
    public List<AbstractProcess> getDataSourceDescriptionHistory(double startTime, double endTime);
    
    
    /**
     * Retrieves data source description valid at specified time
     * @param time
     * @return SensorML process description
     */
    public AbstractProcess getDataSourceDescriptionAtTime(double time);
    
    
    /**
     * Stores a new data source description into storage.
     * Validity period must not overlap with existing descriptions
     * @param process SensorML process description to store
     */
    public void storeDataSourceDescription(AbstractProcess process);
    
    
    /**
     * Update the data source description in storage.
     * Validity period must be exactly the same as one in storage
     * @param process SensorML process description to update
     */
    public void updateDataSourceDescription(AbstractProcess process);
    

    /**
     * Removes data source description valid at specified time
     * @param time any time falling within the validity period of the description version to remove 
     */
    public void removeDataSourceDescription(double time);
    
    
    /**
     * Removes data source descriptions whose validity periods lie within the given time period
     * @param startTime lower bound of the time period
     * @param endTime upper bound of the time period
     */
    public void removeDataSourceDescriptionHistory(double startTime, double endTime);
    
    
    /**
     * Gets the list of available record types in this storage
     * @return map of name to data store instance
     */
    public Map<String, ? extends IRecordStoreInfo> getRecordStores();
    
    
    /**
     * Adds a data store for a new record type in this storage
     * @param name name of record type (should match output name of the data source)
     * @param recordStructure SWE data component describing the record structure
     * @param recommendedEncoding recommended encoding for this record type
     */
    public void addRecordStore(String name, DataComponent recordStructure, DataEncoding recommendedEncoding);
    
    
    /**
     * Updates metadata describing the structure of records in a record store.<br/>
     * <b>The provided data structure MUST be compatible with the previous one, so only
     * documentation and semantic level information can be changed</b>
     * @param name name of record type (should match output name of the data source)
     * @param recordStructure SWE data component describing the record structure
     */
    public void updateRecordStore(String name, DataComponent recordStructure);
        
    
    /**
     * Helper method to retrieve the total number of method for the specified
     * record type
     * @param recordType name of record type
     * @return total number of records
     */
    public int getNumRecords(String recordType);
    
    
    /**
     * Retrieves time range spanned by all records of the specified type
     * @param recordType name of record type
     * @return array of length 2 in the form [minTime maxTime] or [NaN NaN]
     * if no records of this type are available
     */
    public double[] getRecordsTimeRange(String recordType);
    
    
    /**
     * Retrieves record counts within bins defined by the given time stamps.
     * Each bin is defined by two consecutive time stamps.
     * @param recordType name of record type
     * @param timeStamps
     * @return
     */
    public int[] getEstimatedRecordCounts(String recordType, double[] timeStamps);
    
    
    /**
     * Retrieves raw data block with the specified key
     * @param key Record key
     * @return data block or null if no record with the specified key was found
     */
    public DataBlock getDataBlock(DataKey key);
    
    
    /**
     * Gets iterator of raw data blocks matching the specified filter
     * @param filter filtering parameters
     * @return A read-only iterator among data blocks matching the filter
     */
    public Iterator<DataBlock> getDataBlockIterator(IDataFilter filter);
    
    
    /**
     * Gets iterator of records matching the specified filter
     * @param filter filtering parameters
     * @return A read-only iterator among records matching the filter
     */
    public Iterator<? extends IDataRecord> getRecordIterator(IDataFilter filter);
    
    
    /**
     * Computes the (potentially approximate) number of records matching the
     * given filter.<br/>
     * Since the returned value can be approximate and the number of matching
     * records can change before or even during the actual call to
     * {@link #getRecordIterator(IDataFilter)}, the exact number of records can
     * only be obtained by counting the records returned by the iterator next()
     * function.
     * @param filter filtering parameters
     * @param maxCount counting will stop once this threshold is reached
     * @return number of matching records
     */
    public int getNumMatchingRecords(IDataFilter filter, long maxCount);
    
    
    /**
     * Persists data block in storage
     * @param key key object to associate to record
     * @param data actual record data
     */
    public void storeRecord(DataKey key, DataBlock data);
    
    
    /**
     * Updates record with specified key with new data
     * @param key key of record to update
     * @param data new data block to assign to the record
     */
    public void updateRecord(DataKey key, DataBlock data);
    
    
    /**
     * Removes record with the specified key
     * @param key record key
     */
    public void removeRecord(DataKey key);
    
    
    /**
     * Removes all records matching the filter
     * @param filter filtering parameters
     * @return number of deleted records
     */
    public int removeRecords(IDataFilter filter);
        
   
    /**
     * @return true if read operations are supported by this storage, false otherwise
     */
    public boolean isReadSupported();
    
    
    /**
     * @return true if write operations are supported by this storage, false otherwise
     */
    public boolean isWriteSupported();
}
