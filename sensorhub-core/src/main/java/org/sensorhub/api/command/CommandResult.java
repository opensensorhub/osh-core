/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2019 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import org.sensorhub.api.common.BigId;
import org.sensorhub.utils.ObjectUtils;
import org.vast.ogc.xlink.IXlinkReference;
import org.vast.util.Asserts;
import net.opengis.swe.v20.DataBlock;


/**
 * <p>
 * Immutable class used to describe the result of a command
 * </p>
 *
 * @author Alex Robin
 * @date Sep 10, 2022
 */
public class CommandResult implements ICommandResult
{
    protected Collection<DataBlock> inlineRecords;
    protected Collection<BigId> obsIDs;
    protected Collection<BigId> dsIDs;
    protected Collection<IXlinkReference<?>> links;
    
    
    protected CommandResult()
    {
        // can only instantiate with builder or static methods
    }
    
    
    /**
     * Add an entire datastream to the command result
     * @param dataStreamID The ID of the datastream that contains the result
     * @return The result object
     */
    public static ICommandResult withDatastream(BigId dataStreamID)
    {
        Asserts.checkNotNull(dataStreamID, BigId.class);
        
        var res = new CommandResult();
        if (res.dsIDs == null)
            res.dsIDs = new ArrayList<>();
        res.dsIDs.add(dataStreamID);
        return res;
    }
    
    
    /**
     * Add an observation to the command result
     * @param obsID The internal ID of an observation to add to the result
     * @return The result object
     */
    public static ICommandResult withObservation(BigId obsID)
    {
        Asserts.checkNotNull(obsID, BigId.class);
        
        var res = new CommandResult();
        if (res.obsIDs == null)
            res.obsIDs = new ArrayList<>();
        res.obsIDs.add(obsID);
        return res;
    }
    
    
    /**
     * Add data to the inline command result
     * @param data The data record to be added
     * @return The result object
     */
    public static ICommandResult withData(DataBlock data)
    {
        Asserts.checkNotNull(data, DataBlock.class);
        
        var res = new CommandResult();
        if (res.inlineRecords == null)
            res.inlineRecords = new ArrayList<>();
        res.inlineRecords.add(data);
        return res;
    }
    
    
    /**
     * Add multiple data records to the inline command result
     * @param records List of data records to be added
     * @return The result object
     */
    public static ICommandResult withData(Collection<DataBlock> records)
    {
        Asserts.checkNotNull(records, Collection.class);
        
        var res = new CommandResult();
        if (res.inlineRecords == null)
            res.inlineRecords = new ArrayList<>();
        res.inlineRecords.addAll(records);
        return res;
    }


    @Override
    public Collection<DataBlock> getInlineRecords()
    {
        return inlineRecords != null ?
            Collections.unmodifiableCollection(inlineRecords) : null;
    }


    @Override
    public Collection<BigId> getObservationIDs()
    {
        return obsIDs != null ?
            Collections.unmodifiableCollection(obsIDs) : null;
    }


    @Override
    public Collection<BigId> getDataStreamIDs()
    {
        return dsIDs != null ?
            Collections.unmodifiableCollection(dsIDs) : null;
    }


    @Override
    public Collection<IXlinkReference<?>> getExternalLinks()
    {
        return links != null ?
            Collections.unmodifiableCollection(links) : null;
    }
    
    
    @Override
    public String toString()
    {
        return ObjectUtils.toString(this, true);
    }
}
