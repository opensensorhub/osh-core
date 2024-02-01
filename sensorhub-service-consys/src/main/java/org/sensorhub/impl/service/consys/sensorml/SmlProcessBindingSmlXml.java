/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.sensorml;

import java.io.IOException;
import java.util.Collection;
import javax.xml.stream.XMLStreamException;
import org.sensorhub.api.common.IdEncoders;
import org.sensorhub.api.datastore.feature.FeatureKey;
import org.sensorhub.api.feature.ISmlFeature;
import org.sensorhub.impl.service.consys.ResourceParseException;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceBindingXml;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.sensorhub.impl.service.consys.resource.ResourceLink;
import org.sensorhub.impl.system.wrapper.SmlFeatureWrapper;
import org.vast.sensorML.SMLStaxBindings;
import net.opengis.sensorml.v20.AbstractProcess;
import net.opengis.sensorml.v20.Deployment;


/**
 * <p>
 * SensorML XML formatter for system resources
 * </p>
 * 
 * @param <V> Type of SML feature resource
 *
 * @author Alex Robin
 * @since Jan 26, 2021
 */
public class SmlProcessBindingSmlXml<V extends ISmlFeature<?>> extends ResourceBindingXml<FeatureKey, V>
{
    SMLStaxBindings smlBindings;
    
    
    public SmlProcessBindingSmlXml(RequestContext ctx, IdEncoders idEncoders, boolean forReading) throws IOException
    {
        super(ctx, idEncoders, forReading);
        
        try
        {
            this.smlBindings = new SMLStaxBindings();
            if (!forReading)
            {
                smlBindings.setNamespacePrefixes(xmlWriter);
                smlBindings.declareNamespacesOnRootElement();
            }
        }
        catch (XMLStreamException e)
        {
            throw new IOException("Error initializing XML bindings", e);
        }
    }


    @Override
    public V deserialize() throws IOException
    {
        try
        {
            if (!xmlReader.hasNext())
                return null;
            
            try
            {
                xmlReader.nextTag();
            }
            catch (XMLStreamException e)
            {
                // If the xmlReader is not advanced to END_OF_DOCUMENT
                // before calling nextTag(), hasNext() above will still
                // return true and nextTag() will fail. The best
                // heuristic of this situation we have is to catch the
                // exception and call hasNext. If so, that just means
                // there was nothing (except maybe whitespace and
                // comments) after the previous document, and that is
                // not an exception.
                if (!xmlReader.hasNext())
                    return null;

                throw e;
            }
            var sml = smlBindings.readDescribedObject(xmlReader);
            
            if (sml instanceof Deployment)
            {
                @SuppressWarnings("unchecked")
                var wrapper = (V)new DeploymentAdapter(sml);
                return wrapper;
            }
            else
            {
                @SuppressWarnings("unchecked")
                var wrapper = (V)new SmlFeatureWrapper((AbstractProcess)sml);
                return wrapper;
            }
        }
        catch (XMLStreamException e)
        {
            throw new ResourceParseException(INVALID_XML_ERROR_MSG + e.getMessage(), e);
        }
    }


    @Override
    public void serialize(FeatureKey key, V res, boolean showLinks) throws IOException
    {
        ctx.setResponseContentType(ResourceFormat.APPLI_XML.getMimeType());
        
        try
        {
            try
            {
                var sml = res.getFullDescription();
                if (sml != null)
                    smlBindings.writeDescribedObject(xmlWriter, sml);
                xmlWriter.flush();
            }
            catch (Exception e)
            {
                IOException wrappedEx = new IOException("Error writing SensorML XML", e);
                throw new IllegalStateException(wrappedEx);
            }
        }
        catch (IllegalStateException e)
        {
            if (e.getCause() instanceof IOException)
                throw (IOException)e.getCause();
            else
                throw e;
        }   
    }


    @Override
    public void startCollection() throws IOException
    {
        try
        {
            xmlWriter.writeStartElement("systems");
        }
        catch (XMLStreamException e)
        {
            throw new IOException(e);
        }
    }


    @Override
    public void endCollection(Collection<ResourceLink> links) throws IOException
    {
        try
        {
            xmlWriter.writeEndElement();
        }
        catch (XMLStreamException e)
        {
            throw new IOException(e);
        }
    }
}
