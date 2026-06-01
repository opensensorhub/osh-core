/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.ui.table;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.common.IdEncoder;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.obs.ObsFilter;
import org.vast.swe.ScalarIndexer;
import org.vast.util.TimeExtent;
import com.vaadin.v7.data.Item;
import com.vaadin.v7.data.util.IndexedContainer;


public class LazyLoadingObsContainer extends IndexedContainer
{
    final IObsSystemDatabase db;
    final IdEncoder foiIdEncoder;
    final BigId dataStreamID;
    final Set<BigId> foiIDs;
    final List<ScalarIndexer> indexers;
    final int pageSize;
    int startIndexCache = -1;
    int prevStartIndex = 0;
    int size = -1;
    TimeExtent timeRange;
    Instant firstObsTime, lastObsTime;
    BigId firstObsId, lastObsId;
        
    
    public LazyLoadingObsContainer(IObsSystemDatabase db, IdEncoder foiIdEncoder, BigId dataStreamID, Set<BigId> foiIDs, List<ScalarIndexer> indexers, int pageSize)
    {
        this.db = db;
        this.foiIdEncoder = foiIdEncoder;
        this.dataStreamID = dataStreamID;
        this.foiIDs = foiIDs;
        this.indexers = indexers;
        this.pageSize = pageSize;
    }
    
    
    public void updateTimeRange(TimeExtent timeRange)
    {
        this.size = -1;
        this.timeRange = timeRange;
        onPageChanged();
    }
    
    
    public void onPageChanged()
    {
        this.startIndexCache = -1;
    }
    
        
    @Override
    public List<Object> getItemIds(int startIndex, int numberOfIds)
    {
        if (timeRange != null && startIndexCache != startIndex)
        {
            TimeExtent pageTimeRange;
            long limit = 0;
            long skipCount = 0;
            BigId nextKey;
            boolean descending = false;
            
            // next page
            if (startIndex == prevStartIndex+pageSize && lastObsTime != null && lastObsId != null) {
                pageTimeRange = TimeExtent.period(lastObsTime, timeRange.end());
                skipCount = 1;
                limit = pageSize*100;
                nextKey = lastObsId;
            }
            
            // previous page
            else if (startIndex == prevStartIndex-pageSize && firstObsTime != null && firstObsId != null) {
                pageTimeRange = TimeExtent.period(timeRange.begin(), firstObsTime);
                skipCount = 1;
                limit = pageSize*100;
                nextKey = firstObsId;
                descending = true;
            }
            
            // last page
            else if (startIndex >= size()-10) {
                pageTimeRange = TimeExtent.period(timeRange.begin(), timeRange.end());
                skipCount = 0;
                limit = size() % pageSize;
                nextKey = null;
                descending = true;
            }
            
            // any other page, use skip (inefficient for large page number)
            else {
                pageTimeRange = timeRange;
                skipCount = startIndex;
                limit = pageSize + startIndex;
                nextKey = null;
            }
            
            startIndexCache = prevStartIndex = startIndex;
            //System.out.println("Loading from " + startIndex + ", count=" + numberOfIds);
            
            var filter = new ObsFilter.Builder()
                .withDataStreams(dataStreamID)
                .withPhenomenonTime()
                    .fromTimeExtent(pageTimeRange)
                    .descendingOrder(descending)
                    .done()
                .withLimit(limit);
            if (!foiIDs.isEmpty())
                filter.withFois(foiIDs);
            
            // prefetch range from DB
            // wee seek by time using the filter but we also need to go to the exact key
            // since there can be multiple FOIs with the same timestamp
            AtomicInteger count = new AtomicInteger(0);
            var obsPage = db.getObservationStore().selectEntries(filter.build())
                .dropWhile(e -> nextKey != null && !e.getKey().equals(nextKey))
                .skip(skipCount)
                .limit(pageSize)
                .collect(Collectors.toList());
            
            // reverse order if data was collected in descending order
            if (descending)
                Collections.reverse(obsPage);
            
            // add all obs items to container
            removeAllItems();
            obsPage.forEach(e -> {
                var obs = e.getValue();
                
                if (count.get() == 0) {
                    firstObsTime = obs.getPhenomenonTime();
                    firstObsId = e.getKey();
                    //System.out.println("First: " + firstObsId + " -> " + firstObsTime);
                }
                else if (count.get() == pageSize-1) {
                    lastObsTime = obs.getPhenomenonTime();
                    lastObsId = e.getKey();
                    //System.out.println("Last: " + lastObsId + " -> " + lastObsTime);
                }
                
                //System.out.println("Adding " + e.getKey() + " -> " + obs.getResultTime());
                var dataBlk = obs.getResult();
                var itemId = count.getAndIncrement();
                Item item = addItem(itemId);
                if (item != null)
                {
                    int i = -1;
                    for (Object colId: getContainerPropertyIds())
                    {
                        String value;
                        
                        if (i < 0)
                            value = foiIdEncoder.encodeID(obs.getFoiID());
                        else
                            value = indexers.get(i).getStringValue(dataBlk);
                        
                        item.getItemProperty(colId).setValue(value);
                        i++;
                    }
                }
            });
        }
        
        return (List<Object>)super.getItemIds();
    }

    @Override
    public int size()
    {
        if (timeRange == null)
            return 0;
        
        if (size < 0)
        {
            var filter = new ObsFilter.Builder()
                .withDataStreams(dataStreamID)
                .withPhenomenonTime().fromTimeExtent(timeRange).done();
            if (!foiIDs.isEmpty())
                filter.withFois(foiIDs);
            
            size = (int)db.getObservationStore().countMatchingEntries(filter.build());
        }
        
        return size;
    }
}
