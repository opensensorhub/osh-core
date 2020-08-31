/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.persistence;

import java.util.Iterator;
import org.vast.util.Bbox;
import net.opengis.gml.v32.AbstractFeature;


/**
 * <p>
 * Adds support for storing/retrieving features of interests.<br/>
 * {@link ObsKey} and {@link IObsFilter} can be used in all record retrieval
 * methods and concrete implementations must handle them properly (e.g. they
 * must handle all obs filter criteria when they are specified)
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @since April 25, 2015
 */
public interface IObsStorage extends IBasicStorage
{    
    
    /**
     * Gets the number of features of interest matching the filter
     * @param filter filtering criterias
     * @return Number of features of interest registered in this storage
     */
    int getNumFois(IFoiFilter filter);
    
    
    /**
     * Gets the bounding rectangle of all features of interest contained
     * in this storage
     * @return bounding rectangle as Bbox instance
     */
    Bbox getFoisSpatialExtent();
    
    
    /**
     * Gets IDs of FOIs matching the filter
     * @param filter filtering criterias
     * @return A read-only iterator over IDs of features of interest
     */
    public Iterator<String> getFoiIDs(IFoiFilter filter);
    
    
    /**
     * Retrieves features of interest matching the given filter 
     * @param filter filtering criterias
     * @return A read-only iterator over matching FOIs
     */
    public Iterator<AbstractFeature> getFois(IFoiFilter filter);
    
    
    /**
     * Retrieves the time ranges during which observations are available for 
     * features of interest matching the given filter 
     * @param filter filtering criterias
     * @return A read-only iterator over observation periods
     */
    public Iterator<ObsPeriod> getFoiTimeRanges(IObsFilter filter);
    
    
    /**
     * Stores a new feature of interest description into storage.
     * @param producerID ID of producer by which this FOI has been observed
     * @param foi feature object to store
     */
    public void storeFoi(String producerID, AbstractFeature foi);
    
}
