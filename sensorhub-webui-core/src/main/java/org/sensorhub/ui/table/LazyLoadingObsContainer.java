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

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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
    int startIndexCache = -1;
    int size = -1;
    TimeExtent timeRange;
    
    
    public LazyLoadingObsContainer(IObsSystemDatabase db, IdEncoder foiIdEncoder, BigId dataStreamID, Set<BigId> foiIDs, List<ScalarIndexer> indexers)
    {
        this.db = db;
        this.foiIdEncoder = foiIdEncoder;
        this.dataStreamID = dataStreamID;
        this.foiIDs = foiIDs;
        this.indexers = indexers;
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
        removeAllItems();
    }
    
        
    @Override
    public List<Object> getItemIds(int startIndex, int numberOfIds)
    {
        if (timeRange != null && startIndexCache != startIndex)
        {
            startIndexCache = startIndex;
            //System.out.println("Loading from " + startIndex + ", count=" + numberOfIds);
            
            // prefetch range from DB
            AtomicInteger count = new AtomicInteger(startIndex);
            db.getObservationStore().select(new ObsFilter.Builder()
                    .withDataStreams(dataStreamID)
                    .withFois(!foiIDs.isEmpty() ? foiIDs : null)
                    .withPhenomenonTime().fromTimeExtent(timeRange).done()
                    .withLimit((long)startIndex+numberOfIds)
                    .build())
                .skip(startIndex)
                .forEach(obs -> {
                    var dataBlk = obs.getResult();
                    Item item = addItem(count.getAndIncrement());
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
            size = (int)db.getObservationStore().countMatchingEntries(new ObsFilter.Builder()
                .withDataStreams(dataStreamID)
                .withFois(!foiIDs.isEmpty() ? foiIDs : null)
                .withPhenomenonTime().fromTimeExtent(timeRange).done()
                .build());
        }
        
        return size;
    }
}
