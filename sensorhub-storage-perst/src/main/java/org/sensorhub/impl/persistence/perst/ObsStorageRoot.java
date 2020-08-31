/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.persistence.perst;

import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import net.opengis.gml.v32.AbstractFeature;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import org.garret.perst.Storage;
import org.sensorhub.api.persistence.IFoiFilter;
import org.sensorhub.api.persistence.IObsFilter;
import org.sensorhub.api.persistence.IObsStorage;
import org.sensorhub.api.persistence.ObsPeriod;
import org.sensorhub.impl.persistence.perst.FoiTimesStoreImpl.FoiTimePeriod;
import org.vast.util.Bbox;


/**
 * <p>
 * PERST implementation of an observation storage fed by a single producer.<br/>
 * This also stores information about features of interest and their
 * observation times.
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @since May 8, 2015
 */
class ObsStorageRoot extends BasicStorageRoot implements IObsStorage
{
    FeatureStoreImpl featureStore;
    
    
    // default constructor needed on Android JVM
    ObsStorageRoot() {}
    
    
    public ObsStorageRoot(Storage db)
    {        
        this(db, new FeatureStoreImpl(db));
    }
    
    
    public ObsStorageRoot(Storage db, FeatureStoreImpl featureStore)
    {
        super(db);
        this.featureStore = featureStore;
    }
    
    
    @Override
    public int getNumFois(IFoiFilter filter)
    {
        return featureStore.getNumMatchingFeatures(filter);
    }
    
    
    @Override
    public Bbox getFoisSpatialExtent()
    {
        return featureStore.getFeaturesSpatialExtent();
    }


    @Override
    public Iterator<String> getFoiIDs(IFoiFilter filter)
    {
        return featureStore.getFeatureIDs(filter);
    }


    @Override
    public Iterator<AbstractFeature> getFois(IFoiFilter filter)
    {
        return featureStore.getFeatures(filter);
    }


    @Override
    public Iterator<ObsPeriod> getFoiTimeRanges(IObsFilter filter)
    {
        ObsSeriesImpl dataStore = (ObsSeriesImpl)getRecordStore(filter.getRecordType());
        Set<FoiTimePeriod> timePeriods = dataStore.getFoiTimePeriods(filter, true);
        if (timePeriods == null)
            return Collections.emptyIterator();
        
        Iterator<FoiTimePeriod> it = timePeriods.iterator();
        return new Iterator<ObsPeriod>() {

            @Override
            public boolean hasNext()
            {
                return it.hasNext();
            }

            @Override
            public ObsPeriod next()
            {
                FoiTimePeriod p = it.next();
                return new ObsPeriod(p.uid, p.start, p.stop);
            }
        };
    }
    
    
    @Override
    public void storeFoi(String producerID, AbstractFeature foi)
    {
        featureStore.store(foi);
    }


    @Override
    public void addRecordStore(String name, DataComponent recordStructure, DataEncoding recommendedEncoding)
    {
        try
        {
            exclusiveLock();
            recordStructure.setName(name);
            ObsSeriesImpl newTimeSeries = new ObsSeriesImpl(this, recordStructure, recommendedEncoding);
            dataStores.put(name, newTimeSeries);
            modify();
        }
        finally
        {
            unlock();
        }
    }
}
