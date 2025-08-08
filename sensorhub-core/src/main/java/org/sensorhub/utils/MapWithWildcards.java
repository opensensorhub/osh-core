/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.utils;

import java.util.concurrent.ConcurrentSkipListMap;


/**
 * <p>
 * Implementation of a concurrent tree map that can also accept and match
 * toward entries with containing wildcards.
 * <p></p>
 * When an entry with a wildcard is inserted, the get method will return
 * its value for any key that has the wildcard key as prefix.
 * </p>
 * 
 * @param <V> Value Type
 * 
 * @author Alex Robin
 * @date Dec 5, 2020
 */
@SuppressWarnings("serial")
public class MapWithWildcards<V> extends ConcurrentSkipListMap<String, V>
{
    static final String WILDCARD_CHAR = "*";
    static final String END_PREFIX_CHAR = "\0";
    
    
    @Override
    public V get(Object obj)
    {
        var key = (String)obj;
        
        // case of exact match
        var v = super.get(key);
        if (v != null)
            return v;
        
        // else look for most specific wildcard match
        // iterate through previous entries in descending order until we find a matching prefix
        var prevEntries = headMap(key).descendingMap().entrySet();
        for (var e: prevEntries)
        {
            var nextKey = e.getKey();
            if (nextKey.endsWith(END_PREFIX_CHAR))
            {
                var prefix = nextKey.substring(0, nextKey.length()-1);
                if (key.startsWith(prefix))
                    return e.getValue();
            }
        }
        
        return null;
    }
    
    
    protected String fixKey(String key)
    {
        // replace wildcard with special char so it gets sorted before anything else with that prefix
        if (key.endsWith(WILDCARD_CHAR))
            return key.substring(0, key.length()-1) + END_PREFIX_CHAR;
        
        return key;
    }
    
    
    @Override
    public V put(String key, V value)
    {   
        return super.put(fixKey(key), value);
    }
    
    
    @Override
    public V putIfAbsent(String key, V value)
    {
        return super.putIfAbsent(fixKey(key), value);
    }
    
    
    @Override
    public V remove(Object key)
    {   
        return super.remove(fixKey((String)key));
    }
    
    
    @Override
    public boolean remove(Object key, Object value)
    {   
        return super.remove(fixKey((String)key), value);
    }
}
