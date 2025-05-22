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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.h2.mvstore.MVBTreeMap;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.type.DataType;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.datastore.IdProvider;
import org.sensorhub.api.datastore.resource.IResourceStore;
import org.sensorhub.api.datastore.resource.IResourceStore.ResourceField;
import org.sensorhub.api.resource.ResourceFilter;
import org.sensorhub.impl.datastore.DataStoreUtils;
import org.sensorhub.impl.datastore.h2.index.FullTextIndex;
import org.vast.util.Asserts;
import org.vast.util.IResource;


/**
 * <p>
 * Resource Store implementation based on H2 MVStore.
 * </p>
 * 
 * @param <K> Key Type
 * @param <V> Resource Type
 * @param <VF> Resource Field Type
 * @param <F> Filter Type
 * 
 * @author Alex Robin
 * @date Oct 8, 2023
 */
public abstract class MVBaseResourceStoreImpl<K extends Comparable<? super K>, V extends IResource, VF extends ResourceField, F extends ResourceFilter<? super V>> implements IResourceStore<K, V, VF, F>
{
    protected static final String RESOURCE_OBJECTS_MAP_NAME = "resource_main";
    protected static final String RESOURCE_FULLTEXT_MAP_NAME = "resource_text";

    protected MVStore mvStore;
    protected MVDataStoreInfo dataStoreInfo;
    protected IdProvider<V> idProvider;
    protected int idScope;
    
    /*
     * Main index holding resource objects
     */
    protected MVBTreeMap<K, V> mainIndex;
    
    /*
     * Full text index pointing to main index
     * Key references are parentID/internalID pairs
     */
    protected FullTextIndex<V, K> fullTextIndex;
    
    
    protected MVBaseResourceStoreImpl()
    {
    }
    
    
    protected MVBaseResourceStoreImpl<K, V, VF, F> init(MVStore mvStore, int idScope, IdProvider<V> idProvider, MVDataStoreInfo dataStoreInfo)
    {
        this.mvStore = Asserts.checkNotNull(mvStore, MVStore.class);
        this.dataStoreInfo = Asserts.checkNotNull(dataStoreInfo, MVDataStoreInfo.class);
        this.idProvider = Asserts.checkNotNull(idProvider, IdProvider.class);
        this.idScope = idScope;
        
        // persistent class mappings for Kryo
        var kryoClassMap = mvStore.openMap(MVObsSystemDatabase.KRYO_CLASS_MAP_NAME, new MVBTreeMap.Builder<String, Integer>());
        
        // feature records map
        String mapName = dataStoreInfo.getName() + ":" + RESOURCE_OBJECTS_MAP_NAME;
        this.mainIndex = mvStore.openMap(mapName, 
                new MVBTreeMap.Builder<K, V>()
                         .keyType(getResourceKeyDataType(idScope))
                         .valueType(getResourceDataType(kryoClassMap, idScope)));
        
        // full-text index
        mapName = dataStoreInfo.getName() + ":" + RESOURCE_FULLTEXT_MAP_NAME;
        this.fullTextIndex = new FullTextIndex<>(mvStore, mapName, getResourceKeyDataType(idScope));
        
        return this;
    }
    
    
    protected abstract DataType getResourceKeyDataType(int idScope);
    
    protected abstract DataType getResourceDataType(MVMap<String, Integer> kryoClassMap, int idScope);

    protected abstract K generateKey(V f);
    
    protected abstract K getFullKey(BigId id);

    
    @Override
    public String getDatastoreName()
    {
        return dataStoreInfo.getName();
    }


    @Override
    public long getNumRecords()
    {
        return mainIndex.sizeAsLong();
    }
    
    
    @Override
    public synchronized K add(V resource) throws DataStoreException
    {
        var newKey = generateKey(resource);
        
        // add to store
        put(newKey, resource, false);
        return newKey;
    }
    
    
    protected Stream<Entry<K, V>> getIndexedStream(F filter)
    {
        Stream<K> fkStream = null;
        
        // if filtering by internal IDs
        if (filter.getInternalIDs() != null)
        {
            fkStream = filter.getInternalIDs().stream()
                .map(id -> getFullKey(id));
        }
        
        // if full-text filter is used, use full-text index as primary
        else if (filter.getFullTextFilter() != null)
        {
            fkStream = fullTextIndex.selectKeys(filter.getFullTextFilter());
        }
        
        // if some resources were selected by ID
        if (fkStream != null)
        {
            return fkStream
                .filter(Objects::nonNull)
                .map(k -> mainIndex.getEntry(k));
        }
        
        return null;
    }


    @Override
    public Stream<Entry<K, V>> selectEntries(F filter, Set<VF> fields)
    {
        var resultStream = getIndexedStream(filter);
        
        // if no suitable index was found, just scan all resources
        if (resultStream == null)
            resultStream = mainIndex.entrySet().stream();
        
        // add full-text predicate
        if (filter.getFullTextFilter() != null)
            resultStream = resultStream.filter(e -> filter.testFullText(e.getValue()));
        
        // apply value predicate
        if (filter.getValuePredicate() != null)
            resultStream = resultStream.filter(e -> filter.testValuePredicate(e.getValue()));
        
        // apply limit
        if (filter.getLimit() < Long.MAX_VALUE)
            resultStream = resultStream.limit(filter.getLimit());
        
        // casting is ok since keys are subtypes of FeatureKey
        return resultStream;
    }
    
    
    protected V getFeatureWithAdjustedValidTime(MVFeatureParentKey fk, V f)
    {
        return f;
    }


    @Override
    public long countMatchingEntries(F filter)
    {
        return selectEntries(filter).count();
    }


    @Override
    public void commit()
    {
        mainIndex.getStore().commit();
        mainIndex.getStore().sync();
    }


    @Override
    public void backup(OutputStream output) throws IOException
    {
        // TODO Auto-generated method stub

    }


    @Override
    public void restore(InputStream input) throws IOException
    {
        // TODO Auto-generated method stub

    }


    @Override
    public boolean isReadOnly()
    {
        return mvStore.isReadOnly();
    }


    @Override
    public synchronized void clear()
    {
        // store current version so we can rollback if an error occurs
        long currentVersion = mvStore.getCurrentVersion();
        
        try
        {
            mainIndex.clear();
            fullTextIndex.clear();
        }
        catch (Exception e)
        {
            mvStore.rollbackTo(currentVersion);
            throw e;
        }
    }


    @Override
    public boolean containsKey(Object key)
    {
        var k = ensureFullKey(key);
        if (k == null)
            return false;
        return mainIndex.containsKey(key);
    }


    @Override
    public boolean containsValue(Object value)
    {
        return mainIndex.containsValue(value);
    }


    @Override
    public V get(Object key)
    {
        var fk = ensureFullKey(key);
        return fk != null ? mainIndex.get(fk) : null;
    }
    
    
    @SuppressWarnings("unchecked")
    protected K ensureFullKey(Object key)
    {
        return (K)key;
    }


    @Override
    public boolean isEmpty()
    {
        return mainIndex.isEmpty();
    }


    @Override
    public synchronized V put(K key, V res)
    {
        try
        {
            ensureFullKey(key);
            return put(key, res, true);
        }
        catch (DataStoreException e)
        {
            throw new IllegalArgumentException(e);
        }
    }
    
    
    protected V put(K key, V res, boolean replace) throws DataStoreException
    {
        // store current version so we can rollback if an error occurs
        long currentVersion = mvStore.getCurrentVersion();
        
        try
        {
            // add to main index
            V oldValue = mainIndex.put(key, res);
            
            // check if we're allowed to replace existing entry
            boolean isNewEntry = (oldValue == null);
            if (!isNewEntry)
            {
                if (!replace)
                    throw new DataStoreException(DataStoreUtils.ERROR_EXISTING_KEY);
            }
            
            updateIndexes(key, oldValue, res, isNewEntry);
            return oldValue;
        }
        catch (Exception e)
        {
            mvStore.rollbackTo(currentVersion);
            throw e;
        }
    }
    
    
    protected void updateIndexes(K key, V oldRes, V res, boolean isNewEntry)
    {
        // update full-text index
        if (isNewEntry)
            fullTextIndex.add(key, res);
        else
            fullTextIndex.update(key, oldRes, res);
    }
    
    
    @Override
    public synchronized V remove(Object key)
    {
        var k = ensureFullKey(key);
        if (k == null)
            return null;
        
        // store current version so we can rollback if an error occurs
        long currentVersion = mvStore.getCurrentVersion();
            
        try
        {
            // remove from main index
            V oldValue = mainIndex.remove(k);
            if (oldValue == null)
                return null;
            
            removeFromIndexes(k, oldValue);
            
            return oldValue;
        }
        catch (Exception e)
        {
            mvStore.rollbackTo(currentVersion);
            throw e;
        }
    }
    
    
    protected void removeFromIndexes(K key, V oldValue)
    {
        // remove from full-text index
        fullTextIndex.remove(key, oldValue);
    }


    @Override
    public int size()
    {
        return mainIndex.size();
    }


    @Override
    public Set<K> keySet()
    {
        return new AbstractSet<>() {
            @Override
            public Iterator<K> iterator()
            {
                return mainIndex.keySet().iterator();
            }

            @Override
            public boolean contains(Object k)
            {
                return MVBaseResourceStoreImpl.this.containsKey(k);
            }

            @Override
            public int size()
            {
                return MVBaseResourceStoreImpl.this.size();
            }
        };
    }


    @Override
    public Set<Entry<K, V>> entrySet()
    {
        return mainIndex.entrySet();
    }


    @Override
    public Collection<V> values()
    {
        return mainIndex.values();
    }
}
