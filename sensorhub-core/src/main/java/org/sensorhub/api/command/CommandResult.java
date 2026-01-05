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
import java.util.List;
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
     * @param dataStreamID The internal ID of the datastream that contains the result
     * @return The result object
     */
    public static ICommandResult withDatastream(BigId dataStreamID)
    {
        return withDatastreams(List.of(dataStreamID));
    }
    
    
    /**
     * Create a command result with multiple datastream references
     * @param dataStreamIDs The internal IDs of the datastreams
     * @return The result object
     */
    public static ICommandResult withDatastreams(Collection<BigId> dataStreamIDs)
    {
        Asserts.checkNotNull(dataStreamIDs, Collection.class);
        
        var res = new CommandResult();
        res.dsIDs = new ArrayList<>();
        res.dsIDs.addAll(dataStreamIDs);
        return res;
    }
    
    
    /**
     * Create a command result with a single observation
     * @param obsID The internal ID of the observation
     * @return The result object
     */
    public static ICommandResult withObservation(BigId obsID)
    {
        return withObservations(List.of(obsID));
    }
    
    
    /**
     * Create a command result with multiple observation references
     * @param obsIDs The internal IDs of the observations
     * @return The result object
     */
    public static ICommandResult withObservations(Collection<BigId> obsIDs)
    {
        Asserts.checkNotNull(obsIDs, Collection.class);
        
        var res = new CommandResult();
        res.obsIDs = new ArrayList<>();
        res.obsIDs.addAll(obsIDs);
        return res;
    }
    
    
    /**
     * Create a command result with single record
     * @param data The data record to add
     * @return The result object
     */
    public static ICommandResult withData(DataBlock data)
    {
        return withData(List.of(data));
    }
    
    
    /**
     * Add multiple data records to the inline command result
     * @param records The list of data records to add
     * @return The result object
     */
    public static ICommandResult withData(Collection<DataBlock> records)
    {
        Asserts.checkNotNull(records, Collection.class);
        
        var res = new CommandResult();
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
