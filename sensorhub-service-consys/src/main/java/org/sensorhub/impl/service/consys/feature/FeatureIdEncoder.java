/***************************** BEGIN COPYRIGHT BLOCK **************************

Copyright (C) 2025 Delta Air Lines, Inc. All Rights Reserved.

Notice: All information contained herein is, and remains the property of
Delta Air Lines, Inc. The intellectual and technical concepts contained herein
are proprietary to Delta Air Lines, Inc. and may be covered by U.S. and Foreign
Patents, patents in process, and are protected by trade secret or copyright law.
Dissemination, reproduction or modification of this material is strictly
forbidden unless prior written permission is obtained from Delta Air Lines, Inc.

******************************* END COPYRIGHT BLOCK ***************************/

package org.sensorhub.impl.service.consys.feature;

import org.sensorhub.api.common.BigId;
import org.sensorhub.api.common.IdEncoder;
import org.sensorhub.api.datastore.feature.IFeatureStoreBase;
import org.sensorhub.impl.service.consys.CurieResolver;
import org.vast.util.Asserts;


/**
 * <p>
 * Wraps a raw ID encoder to add support for URI and CURIE lookup
 * </p>
 *
 * @author Alex Robin
 * @since Jun 18, 2025
 */
public class FeatureIdEncoder implements IdEncoder
{
    final IdEncoder delegate;
    final CurieResolver curieResolver;
    final IFeatureStoreBase<?,?,?> featureStore;
    
    
    public FeatureIdEncoder(IdEncoder delegate, CurieResolver curieResolver, IFeatureStoreBase<?,?,?> featureStore)
    {
        this.delegate = Asserts.checkNotNull(delegate, IdEncoder.class);
        this.curieResolver = Asserts.checkNotNull(curieResolver, CurieResolver.class);
        this.featureStore = Asserts.checkNotNull(featureStore, "featureStore");
    }
    
    
    @Override
    public String encodeID(BigId internalID)
    {
        return delegate.encodeID(internalID);
    }


    @Override
    public BigId decodeID(String encodedID)
    {
        // if it's a UID or CURIE, lookup the corresponding internal ID
        // datastream CURIEs must be of the form [prefix:sysName] or just [sysName]
        if (encodedID.contains(":") || encodedID.contains("[")) {
            
            String uri = curieResolver.maybeExpand(encodedID);
            var fk = featureStore.getCurrentVersionKey(uri);
            if (fk == null)
                throw new IllegalArgumentException("Unknown ID: " + encodedID);
            return fk.getInternalID(); 
        }
        
        return delegate.decodeID(encodedID);
    }

}
