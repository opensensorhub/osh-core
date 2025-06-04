/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.datastore.h2.index;

import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.Page;
import org.h2.mvstore.RTreeEntryCursor;
import org.h2.mvstore.rtree.MVRTreeMap;
import org.h2.mvstore.rtree.SpatialKey;
import org.h2.mvstore.rtree.SpatialDataType;
import org.h2.mvstore.type.DataType;
import org.sensorhub.api.datastore.SpatialFilter;
import org.sensorhub.impl.datastore.h2.H2Utils;
import org.vast.util.Bbox;
import org.vast.util.IResource;


/**
 * <p>
 * Implementation of a spatial index based on an R-Tree
 * @param <T> Resource type
 * @param <K> Key reference type
 * </p>
 *
 * @author Alex Robin
 * @since Oct 31, 2020
 */
public abstract class SpatialIndex<T extends IResource, K extends Comparable<?>>
{
    protected MVRTreeMap<K> rTreeMap;
    
       
    protected abstract SpatialKey getSpatialKey(K key, T resource);
    
    
    public SpatialIndex(MVStore mvStore, String mapName, DataType valueType)
    {
        this.rTreeMap = mvStore.openMap(mapName, new MVRTreeMap.Builder<K>()
            .dimensions(3)
            .valueType(valueType));
    }
    
    
    public void add(K key, T res)
    {
        SpatialKey spatialKey = getSpatialKey(key, res);
        if (spatialKey != null)
            rTreeMap.put(spatialKey, key);
    }
    
    
    public void update(K key, T old, T new_)
    {
        remove(key, old);
        add(key, new_);
    }
    
    
    public void remove(K key, T res)
    {
        SpatialKey spatialKey = getSpatialKey(key, res);
        if (spatialKey != null)
            rTreeMap.remove(spatialKey);
    }
    
    
    public Stream<K> selectKeys(SpatialFilter filter)
    {
        SpatialKey bbox = H2Utils.getBoundingRectangle(0, filter.getRoi());
        
        /*final RTreeCursor geoCursor = rTreeMap.findIntersectingKeys(bbox);
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(geoCursor, Spliterator.DISTINCT), true)
            .map(k -> rTreeMap.get(k));*/
        
        var rTreeKeyType = ((SpatialDataType)rTreeMap.getKeyType());
        var geoCursor = new RTreeEntryCursor<K>(rTreeMap.getRoot().root, bbox) {
            @Override
            protected boolean check(boolean leaf, SpatialKey key, SpatialKey test)
            {
                return rTreeKeyType.isOverlap(key, test);
            }
        };
        
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(geoCursor, Spliterator.DISTINCT), true)
            .map(e -> e.getValue());
        
        /*return StreamSupport.stream(Spliterators.spliteratorUnknownSize(geoCursor, Spliterator.DISTINCT), true)
            .map(e -> geoCursor.getValue());*/
    }
    
    
    public Bbox getFullExtent()
    {
        Bbox extent = new Bbox();
        
        Page root = rTreeMap.getRoot().root;
        for (int i = 0; i < root.getKeyCount(); i++)
        {
            SpatialKey key = (SpatialKey)root.getKey(i);
            extent.add(new Bbox(key.min(0), key.min(1), key.min(2),
                                key.max(0), key.max(1), key.max(2)));
        }
        
        return extent;
    }
    
    
    public void clear()
    {
        rTreeMap.clear();
    }
    
}
