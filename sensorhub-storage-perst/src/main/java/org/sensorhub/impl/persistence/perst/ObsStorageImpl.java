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

import java.util.Iterator;
import net.opengis.gml.v32.AbstractFeature;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import org.garret.perst.Persistent;
import org.garret.perst.Storage;
import org.sensorhub.api.persistence.IFoiFilter;
import org.sensorhub.api.persistence.IObsFilter;
import org.sensorhub.api.persistence.IObsStorage;
import org.sensorhub.api.persistence.IObsStorageModule;
import org.sensorhub.api.persistence.ObsPeriod;
import org.vast.util.Bbox;


/**
 * <p>
 * PERST implementation of {@link IObsStorage} for storing observations.
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @since Nov 15, 2015
 */
public class ObsStorageImpl extends BasicStorageImpl implements IObsStorageModule<BasicStorageConfig>
{
        
    @Override
    protected Persistent createRoot(Storage db)
    {
        ObsStorageRoot dbRoot = new ObsStorageRoot(db);
        return dbRoot;
    }
    
    
    @Override
    public final int getNumFois(IFoiFilter filter)
    {
        return ((ObsStorageRoot)dbRoot).getNumFois(filter);
    }
    
    
    @Override
    public Bbox getFoisSpatialExtent()
    {
        return ((ObsStorageRoot)dbRoot).getFoisSpatialExtent();        
    }


    @Override
    public final Iterator<String> getFoiIDs(IFoiFilter filter)
    {
        return ((ObsStorageRoot)dbRoot).getFoiIDs(filter);
    }


    @Override
    public Iterator<AbstractFeature> getFois(IFoiFilter filter)
    {
        return ((ObsStorageRoot)dbRoot).getFois(filter);
    }


    @Override
    public Iterator<ObsPeriod> getFoiTimeRanges(IObsFilter filter)
    {
        return ((ObsStorageRoot)dbRoot).getFoiTimeRanges(filter);
    }
    
    
    @Override
    public synchronized void storeFoi(String producerID, AbstractFeature foi)
    {
        ((ObsStorageRoot)dbRoot).storeFoi(producerID, foi);       
    }


    @Override
    public synchronized void addRecordStore(String name, DataComponent recordStructure, DataEncoding recommendedEncoding)
    {
        ((ObsStorageRoot)dbRoot).addRecordStore(name, recordStructure, recommendedEncoding);
        if (autoCommit)
            commit();
    }
}
