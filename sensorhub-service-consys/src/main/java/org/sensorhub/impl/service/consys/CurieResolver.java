/***************************** BEGIN COPYRIGHT BLOCK **************************

Copyright (C) 2025 Delta Air Lines, Inc. All Rights Reserved.

Notice: All information contained herein is, and remains the property of
Delta Air Lines, Inc. The intellectual and technical concepts contained herein
are proprietary to Delta Air Lines, Inc. and may be covered by U.S. and Foreign
Patents, patents in process, and are protected by trade secret or copyright law.
Dissemination, reproduction or modification of this material is strictly
forbidden unless prior written permission is obtained from Delta Air Lines, Inc.

******************************* END COPYRIGHT BLOCK ***************************/

package org.sensorhub.impl.service.consys;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * <p>
 * TODO CurieResolver type description
 * </p>
 *
 * @author Alex Robin
 * @since Jun 18, 2025
 */
public class CurieResolver
{
    static Pattern CURIE_PATTERN = Pattern.compile("^\\[(([a-zA-Z_][\\w._-]*)(:([\\w._:*-]+))?)\\]$");
    Map<String, String> prefixMap = new HashMap<>();
    
    
    
    public void addPrefix(String prefix, String fullUriPrefix)
    {
        prefixMap.put(prefix, fullUriPrefix);
    }
    
    
    /**
     * Resolve a compact URI (CURIE) into a full URI
     * @param curie
     * @return The expanded URI or null if none could be generated
     */
    public String expand(final String id)
    {
        var m = CURIE_PATTERN.matcher(id);
        if (!m.matches())
            return null;
        
        // first try to map the entire id
        var curie = m.group(1);
        var uri = prefixMap.get(curie);
        if (uri == null)
        {
            // if nothing found try to map the prefix only
            var prefix = m.group(2);
            uri = prefixMap.get(prefix);
            if (uri == null)
                return null;
        
            var ref = m.group(3);
            if (ref != null)
            {
                var separator = uri.contains("/") ? "/" : ":";
                if (!uri.endsWith(separator))
                    uri += separator;
                return uri + ref.substring(1);
            }
        }
        
        return uri;
    }
    
    
    /**
     * Resolve a compact URI (CURIE) into a full URI if possible
     * @param curie
     * @return The expanded URI or the original string if CURIE prefix could not be looked up
     */
    public String maybeExpand(final String id)
    {
        var uri = expand(id);
        return uri != null ? uri : id;
    }
}
