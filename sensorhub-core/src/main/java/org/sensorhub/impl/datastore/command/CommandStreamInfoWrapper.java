/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.datastore.command;

import org.sensorhub.api.command.ICommandStreamInfo;
import org.sensorhub.api.feature.FeatureId;
import org.vast.util.Asserts;
import org.vast.util.TimeExtent;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;


/**
 * <p>
 * Base wrapper class for {@link ICommandStreamInfo} objects
 * </p>
 *
 * @author Alex Robin
 * @date Mar 24, 2021
 */
public abstract class CommandStreamInfoWrapper implements ICommandStreamInfo
{
    ICommandStreamInfo delegate;
    

    public CommandStreamInfoWrapper(ICommandStreamInfo csInfo)
    {
        this.delegate = Asserts.checkNotNull(csInfo, ICommandStreamInfo.class);
    }
    
    
    @Override
    public FeatureId getSystemID()
    {
        return delegate.getSystemID();
    }


    @Override
    public String getControlInputName()
    {
        return delegate.getControlInputName();
    }


    @Override
    public String getName()
    {
        return delegate.getName();
    }


    @Override
    public String getDescription()
    {
        return delegate.getDescription();
    }


    @Override
    public DataComponent getRecordStructure()
    {
        return delegate.getRecordStructure();
    }


    @Override
    public DataEncoding getRecordEncoding()
    {
        return delegate.getRecordEncoding();
    }


    @Override
    public TimeExtent getIssueTimeRange()
    {
        return delegate.getIssueTimeRange();
    }


    @Override
    public TimeExtent getValidTime()
    {
        return delegate.getValidTime();
    }


    @Override
    public TimeExtent getExecutionTimeRange()
    {
        return delegate.getExecutionTimeRange();
    }


    @Override
    public boolean hasResult()
    {
        return delegate.hasResult();
    }


    @Override
    public DataComponent getResultStructure()
    {
        return delegate.getResultStructure();
    }


    @Override
    public DataEncoding getResultEncoding()
    {
        return delegate.getResultEncoding();
    }

    @Override
    public DataComponent getFeasibilityResultStructure()
    {
        return delegate.getFeasibilityResultStructure();
    }

    @Override
    public DataEncoding getFeasibilityResultEncoding() {
        return delegate.getFeasibilityResultEncoding();
    }
}
