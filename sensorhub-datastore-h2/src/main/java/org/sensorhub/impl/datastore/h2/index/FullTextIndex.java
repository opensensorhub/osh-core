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

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.h2.mvstore.FullTextKey;
import org.h2.mvstore.FullTextKeyDataType;
import org.h2.mvstore.MVBTreeMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.RangeCursor;
import org.h2.mvstore.type.DataType;
import org.sensorhub.api.datastore.FullTextFilter;
import org.sensorhub.impl.datastore.h2.MVVoidDataType;
import org.vast.util.IResource;
import com.google.common.base.Strings;


/**
 * <p>
 * Implementation of a full-text index based on a radix tree
 * @param <T> Resource type
 * @param <K> Key reference type
 * </p>
 *
 * @author Alex Robin
 * @since Oct 30, 2020
 */
public class FullTextIndex<T extends IResource, K extends Comparable<?>> 
{
    protected static final Pattern NUMBER_PATTERN = Pattern.compile("^\\.?\\d+(\\.\\d+)?");
        
    //MVRadixTreeMap<byte[], K> radixTreeMap;
    MVBTreeMap<FullTextKey<K>, Boolean> btreeMap;
    
    /* Default analyzer */
    EnglishAnalyzer analyzer = new EnglishAnalyzer();
        
    
    public FullTextIndex(MVStore mvStore, String mapName, DataType valueType)
    {
        //this.radixTreeMap = mvStore.openMap(mapName, new MVRadixTreeMap.Builder<byte[], K>()
        //    .keyType(new ResourceRadixKeyDataType())
        //    .valueType(valueType));
        this.btreeMap = mvStore.openMap(mapName, new MVBTreeMap.Builder<FullTextKey<K>, Boolean>()
            .keyType(new FullTextKeyDataType(valueType))
            .valueType(new MVVoidDataType()));
    }
        
    
    public void add(K key, T res)
    {
        // we first create a token set to remove duplicates
        HashSet<String> tokenSet = new HashSet<>();
        addToTokenSet(res, tokenSet);
        for (String token: tokenSet)
            //radixTreeMap.put(token.getBytes(), key);
            btreeMap.put(new FullTextKey<>(token, key), Boolean.TRUE);
    }
    
        
    public void update(K key, T old, T new_)
    {
        remove(key, old);
        add(key, new_);
    }
    
    
    public void remove(K key, T res)
    {
        HashSet<String> tokenSet = new HashSet<>();
        addToTokenSet(res, tokenSet);
        for (String token: tokenSet)
            //radixTreeMap.remove(token.getBytes(), key);
            btreeMap.put(new FullTextKey<>(token, key), Boolean.TRUE);
    }
    
    
    public Stream<K> selectKeys(FullTextFilter filter)
    {
        //return getKeywordStream(filter)
        //    .flatMap(w -> radixTreeMap.entryCursor(w.getBytes()).valueStream())
        //    .distinct(); // remove duplicate IDs
        
        return getKeywordStream(filter)
            .flatMap(w -> {
                var startKey = new FullTextKey<K>(w, null);
                var endKey = new FullTextKey<K>(w + '\uffff', null);
                return new RangeCursor<>(btreeMap, startKey, endKey).keyStream();
            })
            .map(k -> (K)k.getRefKey())
            .distinct(); // remove duplicate IDs*/
    }
    
    
    protected void addToTokenSet(T resource, Set<String> tokenSet)
    {
        if (!Strings.isNullOrEmpty(resource.getName()))
            addToTokenSet(resource.getName(), tokenSet);
        
        if (!Strings.isNullOrEmpty(resource.getDescription()))
            addToTokenSet(resource.getDescription(), tokenSet);
    }
    
    
    protected void addToTokenSet(String fieldValue, Set<String> tokenSet)
    {
        try (TokenStream tokenStream = analyzer.tokenStream(null, fieldValue))
        {
            CharTermAttribute attr = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();
            while (tokenStream.incrementToken())
            {
                String token = NUMBER_PATTERN.matcher(attr).replaceAll("");
                if (!token.isEmpty())
                    tokenSet.add(token);
            }
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Error analyzing text", e);
        }
    }
    
    
    protected Stream<String> getKeywordStream(FullTextFilter filter)
    {
        Set<String> tokens = new HashSet<>();
        for (String keyword: filter.getKeywords())
            addToTokenSet(keyword, tokens);
        return tokens.stream();
    }
    
    
    public Stream<K> addFullTextPostFilter(Stream<K> pkStream, FullTextFilter filter)
    {
        /*Set<byte[]> keywordSet = getKeywordStream(filter)
            .map(String::getBytes)
            .collect(Collectors.toSet());
        
        return pkStream.filter(k -> {
            if (k == null)
                return false;
            for (byte[] w: keywordSet)
            {
                if (radixTreeMap.containsValue(w, k))
                    return true;
            }
            return false;
        });*/
        
        var keywordSet = getKeywordStream(filter)
            .collect(Collectors.toSet());
        
        return pkStream.filter(k -> {
            if (k == null)
                return false;
            for (var w: keywordSet)
            {
                var fullTextKey = new FullTextKey<>(w, k);
                if (btreeMap.containsKey(fullTextKey))
                    return true;
            }
            return false;
        });
    }
    
    
    public void clear()
    {
        //radixTreeMap.clear();
        btreeMap.clear();
    }
    
}
