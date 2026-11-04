/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2023 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.datastore.h2;

import java.util.Objects;
import java.util.stream.Stream;
import org.h2.mvstore.MVBTreeMap;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.RangeCursor;
import org.h2.mvstore.type.DataType;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.datastore.IdProvider;
import org.sensorhub.api.datastore.property.IPropertyStore;
import org.sensorhub.api.datastore.property.PropertyFilter;
import org.sensorhub.api.datastore.property.PropertyKey;
import org.sensorhub.api.datastore.property.IPropertyStore.PropertyField;
import org.sensorhub.api.semantic.IDerivedProperty;
import org.sensorhub.impl.datastore.DataStoreUtils;
import org.sensorhub.impl.datastore.h2.MVDatabaseConfig.IdProviderType;
import org.vast.util.Asserts;


public class MVPropertyStoreImpl extends MVBaseResourceStoreImpl<PropertyKey, IDerivedProperty, PropertyField, PropertyFilter> implements IPropertyStore
{
    protected static final String PROPERTY_URI_MAP_NAME = "property_uri";
    protected static final String BASE_PROPERTY_URI_MAP_NAME = "base_property_uri";
    
    /*
     * URI index pointing to main index
     * Map of URI to internal ID pairs
     */
    protected MVBTreeMap<String, PropertyKey> uriIndex;
    
    /*
     * base property URI index pointing to main index
     * Map of base property URI to internal ID pairs
     */
    protected MVBTreeMap<String, PropertyKey> basePropIndex;
    
    
    public static MVPropertyStoreImpl open(MVStore mvStore, int idScope, IdProviderType idProviderType, MVDataStoreInfo newStoreInfo)
    {
        var dataStoreInfo = H2Utils.getDataStoreInfo(mvStore, newStoreInfo.getName());
        if (dataStoreInfo == null)
        {
            dataStoreInfo = newStoreInfo;
            H2Utils.addDataStoreInfo(mvStore, dataStoreInfo);
        }
        
        return (MVPropertyStoreImpl)new MVPropertyStoreImpl().init(mvStore, idScope, idProviderType, dataStoreInfo);
    }
    
    
    protected MVPropertyStoreImpl init(MVStore mvStore, int idScope, IdProviderType idProviderType, MVDataStoreInfo dataStoreInfo)
    {
        Asserts.checkNotNull(idProviderType, IdProviderType.class);
        
        // create ID provider
        IdProvider<IDerivedProperty> idProvider = null;
        switch (idProviderType)
        {
            case UID_HASH:
                idProvider = DataStoreUtils.getConceptHashIdProvider(14484323);
                break;
                
            default:
            case SEQUENTIAL:
                idProvider = dsInfo -> {
                    if (mainIndex.isEmpty())
                        return 1;
                    else
                        return mainIndex.lastKey().getInternalID().getIdAsLong()+1;
                };
        }
        
        super.init(mvStore, idScope, idProvider, dataStoreInfo);
        
        // concept URI index
        String mapName = dataStoreInfo.getName() + ":" + PROPERTY_URI_MAP_NAME;
        this.uriIndex = mvStore.openMap(mapName, new MVBTreeMap.Builder<String, PropertyKey>()
            .valueType(new MVPropertyKeyDataType(idScope)));
        
        // base property URI index
        mapName = dataStoreInfo.getName() + ":" + BASE_PROPERTY_URI_MAP_NAME;
        this.basePropIndex = mvStore.openMap(mapName, new MVBTreeMap.Builder<String, PropertyKey>()
            .valueType(new MVPropertyKeyDataType(idScope)));
        
        return this;
    }
    
    
    @Override
    protected DataType<Object> getResourceKeyDataType(int idScope)
    {
        return new MVPropertyKeyDataType(idScope);
    }
    
    
    @Override
    protected DataType<Object> getResourceDataType(MVMap<String, Integer> kryoClassMap, int idScope)
    {
        return new PropertyDataType(kryoClassMap);
    }

    
    @Override
    protected PropertyKey generateKey(IDerivedProperty p)
    {
        var internalID = BigId.fromLong(idScope, idProvider.newInternalID(p));
        return new PropertyKey(internalID);
    }

    
    @Override
    protected PropertyKey getFullKey(BigId id)
    {
        return new PropertyKey(id);
    }
    
    
    @Override
    protected Stream<Entry<PropertyKey, IDerivedProperty>> getIndexedStream(PropertyFilter filter)
    {
        Stream<PropertyKey> fkStream = null;
        
        // if filtering by URI, use URI index as primary
        if (filter.getUniqueIDs() != null)
        {
            fkStream = filter.getUniqueIDs().stream()
                .map(uid -> uriIndex.get(uid));
        }
        
        // if filtering by base property URI, use base prop index as primary
        if (filter.getBasePropertyFilter() != null)
        {
            if (!filter.getBasePropertyFilter().getUniqueIDs().isEmpty())
            {
                fkStream = filter.getBasePropertyFilter().getUniqueIDs().stream()
                    .flatMap(uri -> getBasePropertyCursor(uri).valueStream());
            }
            else
            {
                fkStream = select(filter.getBasePropertyFilter())
                    .flatMap(prop -> getBasePropertyCursor(prop.getURI()).valueStream());
            }
        }
        
        // if some resources were selected by ID
        if (fkStream != null)
        {
            return fkStream
                .filter(Objects::nonNull)
                .map(k -> mainIndex.getEntry(k))
                .filter(Objects::nonNull);
        }
        
        return super.getIndexedStream(filter);
    }
    
    
    protected RangeCursor<String, PropertyKey> getBasePropertyCursor(String basePropUri)
    {
        var first = basePropUri;
        var last = basePropUri + "\uFFFF";
        return new RangeCursor<>(basePropIndex, first, last);
    }
    
    
    @Override
    protected IDerivedProperty put(PropertyKey key, IDerivedProperty prop, boolean replace) throws DataStoreException
    {
        // check that no other property with same URI exists
        var uri = prop.getURI();
        var existingKey = uriIndex.get(uri);
        if (existingKey != null && existingKey.getInternalID().getIdAsLong() != key.getInternalID().getIdAsLong())
            throw new DataStoreException(DataStoreUtils.ERROR_EXISTING_PROPERTY + uri);
        
        return super.put(key, prop, replace);
    }
    
    
    @Override
    protected void updateIndexes(PropertyKey key, IDerivedProperty oldRes, IDerivedProperty res, boolean isNewEntry)
    {
        super.updateIndexes(key, oldRes, res, isNewEntry);
        
        // update URI index
        uriIndex.put(res.getURI(), key);
        
        // update base prop index
        var basePropIdxKey = getBasePropIndexKey(key, res);
        basePropIndex.put(basePropIdxKey, key);
        
        // remove old base prop assoc if needed
        if (oldRes != null && !oldRes.getBaseProperty().equals(res.getBaseProperty()))
        {
            basePropIdxKey = getBasePropIndexKey(key, oldRes);
            basePropIndex.remove(basePropIdxKey);
        }
    }
    
    
    @Override
    protected void removeFromIndexes(PropertyKey key, IDerivedProperty oldValue)
    {
        super.removeFromIndexes(key, oldValue);
        
        // remove from URI index
        uriIndex.remove(oldValue.getURI());
        
        // remove from base prop index
        var basePropIdxKey = getBasePropIndexKey(key, oldValue);
        basePropIndex.remove(basePropIdxKey);
    }
    
    
    protected String getBasePropIndexKey(PropertyKey key, IDerivedProperty prop)
    {
        return prop.getBaseProperty() + "_" + key.getInternalID();
    }
}
