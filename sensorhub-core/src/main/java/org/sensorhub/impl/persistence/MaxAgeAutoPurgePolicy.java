/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.persistence;

import org.sensorhub.api.persistence.DataFilter;
import org.sensorhub.api.persistence.IRecordStoreInfo;
import org.slf4j.Logger;
import org.vast.util.DateTimeFormat;
import org.sensorhub.api.persistence.IRecordStorageModule;


/**
 * <p>
 * Implementation of purging policy removing records when they reach a 
 * certain age
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @since Jul 26, 2015
 */
public class MaxAgeAutoPurgePolicy implements IStorageAutoPurgePolicy
{
    MaxAgeAutoPurgeConfig config;
    DateTimeFormat df = new DateTimeFormat();
    
    MaxAgeAutoPurgePolicy(MaxAgeAutoPurgeConfig config)
    {
        this.config = config;
    }
    
    
    @Override
    public int trimStorage(IRecordStorageModule<?> storage, Logger log)
    {
        int numDeletedRecords = 0;
        
        for (IRecordStoreInfo streamInfo: storage.getRecordStores().values())
        {
            double[] timeRange = storage.getRecordsTimeRange(streamInfo.getName());
            double beginTime = timeRange[0];
            double endTime = timeRange[1];
            
            if (beginTime < endTime - config.maxRecordAge)
            {
                final double[] obsoleteTimeRange = new double[] {0, endTime - config.maxRecordAge};
                
                if (log.isInfoEnabled())
                {
                    log.info("Purging {} data until {}",
                             streamInfo.getName(),
                             df.formatIso(obsoleteTimeRange[1], 0));
                }

                // remove records
                numDeletedRecords += storage.removeRecords(new DataFilter(streamInfo.getName())
                {
                    @Override
                    public double[] getTimeStampRange()
                    {
                        return obsoleteTimeRange;
                    }
                });
                
                log.info("{} records deleted", numDeletedRecords);
                
                // remove data source descriptions
                storage.removeDataSourceDescriptionHistory(obsoleteTimeRange[0], obsoleteTimeRange[1]);
            }
        }        
        
        storage.commit();
        return numDeletedRecords;
    }

}
