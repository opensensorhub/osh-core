/***************************** BEGIN COPYRIGHT BLOCK **************************

Copyright (C) 2025 Delta Air Lines, Inc. All Rights Reserved.

Notice: All information contained herein is, and remains the property of
Delta Air Lines, Inc. The intellectual and technical concepts contained herein
are proprietary to Delta Air Lines, Inc. and may be covered by U.S. and Foreign
Patents, patents in process, and are protected by trade secret or copyright law.
Dissemination, reproduction or modification of this material is strictly
forbidden unless prior written permission is obtained from Delta Air Lines, Inc.

******************************* END COPYRIGHT BLOCK ***************************/

package org.h2.mvstore;


/**
 * <p>
 * TODO FullTextSearchKey type description
 * </p>
 *
 * @author Alex Robin
 * @since Jun 4, 2025
 */
public class FullTextKey<K>
{
    String word;
    K refKey; // primary key of indexed entry
    
    
    public FullTextKey(String word, K refKey)
    {
        this.word = word;
        this.refKey = refKey;
    }
    
    
    public String getWord()
    {
        return word;
    }
    
    
    public K getRefKey()
    {
        return refKey;
    }
}
