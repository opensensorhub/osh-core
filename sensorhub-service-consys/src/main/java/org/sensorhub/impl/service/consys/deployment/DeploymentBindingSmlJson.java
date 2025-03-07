/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.deployment;

import java.io.IOException;
import java.util.Collection;
import org.sensorhub.api.common.IdEncoders;
import org.sensorhub.api.datastore.feature.FeatureKey;
import org.sensorhub.api.system.IDeploymentWithDesc;
import org.sensorhub.impl.service.consys.ResourceParseException;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceBindingJson;
import org.sensorhub.impl.service.consys.resource.ResourceLink;
import org.sensorhub.impl.service.consys.sensorml.DeploymentAdapter;
import org.sensorhub.impl.service.consys.sensorml.SMLConverter;
import org.vast.sensorML.SMLJsonBindings;
import com.google.gson.JsonParseException;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;


/**
 * <p>
 * SensorML JSON formatter for deployment resources
 * </p>
 *
 * @author Alex Robin
 * @since July 7, 2023
 */
public class DeploymentBindingSmlJson extends ResourceBindingJson<FeatureKey, IDeploymentWithDesc>
{
    SMLJsonBindings smlBindings;
    SMLConverter converter;
    
    
    public DeploymentBindingSmlJson(RequestContext ctx, IdEncoders idEncoders, boolean forReading) throws IOException
    {
        super(ctx, idEncoders, forReading);
        this.smlBindings = new SMLJsonBindings();
        this.converter = new SMLConverter();
    }


    @Override
    public IDeploymentWithDesc deserialize(JsonReader reader) throws IOException
    {
        // if array, prepare to parse first element
        if (reader.peek() == JsonToken.BEGIN_ARRAY)
            reader.beginArray();
        
        if (reader.peek() == JsonToken.END_DOCUMENT || !reader.hasNext())
            return null;
        
        try
        {
            var sml = smlBindings.readDeployment(reader);
            return new DeploymentAdapter(sml);
        }
        catch (JsonParseException | IOException e)
        {
            throw new ResourceParseException(INVALID_JSON_ERROR_MSG + e.getMessage(), e);
        }
    }


    @Override
    public void serialize(FeatureKey key, IDeploymentWithDesc res, boolean showLinks, JsonWriter writer) throws IOException
    {
        try
        {
            var sml = res.getFullDescription();
            if (sml == null)
                sml = new SMLConverter().genericFeatureToDeployment(res);
            
            if (key != null && !ctx.isClientSide())
            {
                var idStr = idEncoders.getDeploymentIdEncoder().encodeID(key.getInternalID());
                sml.setId(idStr);
            }
            
            smlBindings.writeDescribedObject(writer, sml);
            writer.flush();
        }
        catch (IllegalStateException e)
        {
            if (e.getCause() instanceof IOException)
                throw (IOException)e.getCause();
            else
                throw e;
        }
        catch (Exception e)
        {
            throw new IOException("Error writing deployment JSON", e);
        }  
    }


    @Override
    public void startCollection() throws IOException
    {
        startJsonCollection(writer);
    }


    @Override
    public void endCollection(Collection<ResourceLink> links) throws IOException
    {
        endJsonCollection(writer, links);
    }
}
