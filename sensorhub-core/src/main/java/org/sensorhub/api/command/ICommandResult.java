/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2022 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.command;

import java.util.Collection;
import org.sensorhub.api.common.BigId;
import org.vast.ogc.xlink.IXlinkReference;
import net.opengis.swe.v20.DataBlock;


/**
 * <p>
 * Interface for command results
 * </p><p>
 * Several ways it can work:
 * <ol>
 * <li>Process creates a dedicated datastream, ingests obs in it, then provides
 * the datastream ID in the command result</li> 
 * <li>Process uses a persistent output datastream, ingests obs in it, then
 * provides only obs IDs (can publish to several different datastreams) in
 * the command result</li> 
 * <li>Process provides a list of datablock inline. In this case,
 * the result is not stored in a separate datastream and is only accessible via
 * the command channel. This is typically used for on-demand processes where only
 * the user who called the process (i.e. sent the command) is interested by the
 * result)</li>
 * </p>
 *
 * @author Alex Robin
 * @since Sep 10, 2022
 */
public interface ICommandResult
{
    /**
     * @return inline data records matching the result schema defined by
     * {@link ICommandStreamInfo}, or null if none provided.
     */
    Collection<DataBlock> getInlineRecords();
    
    
    /**
     * @return IDs to observations generated during the execution of the command
     * and available on the sensor hub, or null if none provided.
     */
    Collection<BigId> getObservationIDs();
    
    
    /**
     * @return references to datastreams that contain observations generated during
     * the execution of the command, or null if none provided.
     */
    Collection<BigId> getDataStreamIDs();
    
    
    /**
     * @return reference to external resources that contain data generated during
     * the execution of the command, or null if none provided.
     */
    Collection<IXlinkReference<?>> getExternalLinks();
}
