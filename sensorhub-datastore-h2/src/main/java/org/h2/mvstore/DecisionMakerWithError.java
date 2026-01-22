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

import org.h2.mvstore.MVMap.DecisionMaker;
import org.sensorhub.api.datastore.DataStoreException;


/**
 * <p>
 * Extension of MVMap DecisionMaker adding support for error reporting
 * </p>
 *
 * @author Alex Robin
 * @since Jan 22, 2026
 */
public abstract class DecisionMakerWithError<V> extends DecisionMaker<V>
{
    protected DataStoreException error;
    
    
    public DataStoreException getError()
    {
        return error;
    }
}
