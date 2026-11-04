/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.datastore.h2;

import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.h2.mvstore.MVBTreeMap;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.type.DataType;
import org.sensorhub.api.datastore.procedure.IProcedureStore;
import org.sensorhub.api.datastore.procedure.IProcedureStore.ProcedureField;
import org.sensorhub.api.datastore.procedure.ProcedureFilter;
import org.sensorhub.api.procedure.IProcedureWithDesc;
import org.sensorhub.impl.datastore.DataStoreUtils;
import org.sensorhub.impl.datastore.h2.MVDatabaseConfig.IdProviderType;
import net.opengis.sensorml.v20.AbstractProcess;


/**
 * <p>
 * Procedure description store implementation based on H2 MVStore.<br/>
 * Most of the work is done in {@link MVBaseFeatureStoreImpl} 
 * </p>
 *
 * @author Alex Robin
 * @date Oct 4, 2021
 */
public class MVProcedureStoreImpl extends MVBaseFeatureStoreImpl<IProcedureWithDesc, ProcedureField, ProcedureFilter> implements IProcedureStore
{

    static class ProcedureValidTimeAdapter extends FeatureValidTimeAdapter<IProcedureWithDesc> implements IProcedureWithDesc
    {
        public ProcedureValidTimeAdapter(MVFeatureParentKey fk, IProcedureWithDesc f, MVBTreeMap<MVFeatureParentKey, IProcedureWithDesc> featuresIndex)
        {
            super(fk, f, featuresIndex);
        }
        
        @Override
        public AbstractProcess getFullDescription()
        {
            return ((IProcedureWithDesc)f).getFullDescription();
        }
    }
    
    
    protected MVProcedureStoreImpl()
    {
    }
    
    
    /**
     * Opens an existing procedure store or create a new one with the specified name
     * @param mvStore MVStore instance containing the required maps
     * @param idScope Internal ID scope (database num)
     * @param idProviderType Type of ID provider to use to generate new IDs
     * @param newStoreInfo Data store info to use if a new store needs to be created
     * @return The existing datastore instance 
     */
    public static MVProcedureStoreImpl open(MVStore mvStore, int idScope, IdProviderType idProviderType, MVDataStoreInfo newStoreInfo)
    {
        var dataStoreInfo = H2Utils.getDataStoreInfo(mvStore, newStoreInfo.getName());
        if (dataStoreInfo == null)
        {
            dataStoreInfo = newStoreInfo;
            H2Utils.addDataStoreInfo(mvStore, dataStoreInfo);
        }
        
        return (MVProcedureStoreImpl)new MVProcedureStoreImpl().init(mvStore, idScope, idProviderType, dataStoreInfo);
    }
    
    
    @Override
    protected DataType<Object> getFeatureDataType(MVMap<String, Integer> kryoClassMap)
    {
        return new SensorMLDataType(kryoClassMap);
    }
    
    
    @Override
    protected Stream<Entry<MVFeatureParentKey, IProcedureWithDesc>> getIndexedStream(ProcedureFilter filter)
    {
        var resultStream = super.getIndexedStream(filter);
        
        if (filter.getParentFilter() != null)
        {
            var parentIDStream = DataStoreUtils.selectProcedureIDs(this, filter.getParentFilter());
            
            if (resultStream == null)
            {
                resultStream = parentIDStream
                    .flatMap(id -> getParentResultStream(id, filter.getValidTime()));
            }
            else
            {
                var parentIDs = parentIDStream
                    .map(id -> id.getIdAsLong())
                    .collect(Collectors.toSet());
                
                resultStream = resultStream.filter(
                    e -> parentIDs.contains(((MVFeatureParentKey)e.getKey()).getParentID()));
                
                // post filter using keys valid time if needed
                if (filter.getValidTime() != null)
                    resultStream = postFilterKeyValidTime(resultStream, filter.getValidTime());
            }
        }
        
        return resultStream;
    }
    
    
    protected IProcedureWithDesc getFeatureWithAdjustedValidTime(MVFeatureParentKey fk, IProcedureWithDesc sys)
    {
        return new ProcedureValidTimeAdapter(fk, sys, featuresIndex);
    }

}
