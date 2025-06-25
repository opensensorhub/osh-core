/***************************** BEGIN COPYRIGHT BLOCK **************************

Copyright (C) 2025 Delta Air Lines, Inc. All Rights Reserved.

Notice: All information contained herein is, and remains the property of
Delta Air Lines, Inc. The intellectual and technical concepts contained herein
are proprietary to Delta Air Lines, Inc. and may be covered by U.S. and Foreign
Patents, patents in process, and are protected by trade secret or copyright law.
Dissemination, reproduction or modification of this material is strictly
forbidden unless prior written permission is obtained from Delta Air Lines, Inc.

******************************* END COPYRIGHT BLOCK ***************************/

package org.sensorhub.impl.service.consys.task;

import org.sensorhub.api.common.BigId;
import org.sensorhub.api.common.IdEncoder;
import org.sensorhub.api.datastore.command.ICommandStreamStore;
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
public class CommandStreamIdEncoder implements IdEncoder
{
    final IdEncoder delegate;
    final CurieResolver curieResolver;
    final ICommandStreamStore csStore;
    
    
    public CommandStreamIdEncoder(IdEncoder delegate, CurieResolver curieResolver, ICommandStreamStore csStore)
    {
        this.delegate = Asserts.checkNotNull(delegate, IdEncoder.class);
        this.curieResolver = Asserts.checkNotNull(curieResolver, CurieResolver.class);
        this.csStore = Asserts.checkNotNull(csStore, "csStore");
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
        // controlstream CURIEs must be of the form [prefix:sysName:controlInputName]
        if (encodedID.contains(":") || encodedID.contains("[")) {
            
            String uri = curieResolver.maybeExpand(encodedID);
            var lastSep = uri.lastIndexOf(':');
            if (lastSep < 0)
                throw new IllegalArgumentException("Unknown ID: " + encodedID);
            var sysUri = uri.substring(0, lastSep);
            var controlInputName = uri.substring(lastSep+1);
            var k = csStore.getLatestVersionKey(sysUri, controlInputName);
            if (k == null)
                throw new IllegalArgumentException("Unknown ID: " + encodedID);
            return k.getInternalID(); 
        }
        
        return delegate.decodeID(encodedID);
    }

}
