/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.feature;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import org.sensorhub.api.common.IdEncoders;
import org.sensorhub.api.database.IDatabase;
import org.sensorhub.api.datastore.feature.FeatureKey;
import org.sensorhub.impl.service.consys.ResourceParseException;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceBindingJson;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.sensorhub.impl.service.consys.resource.ResourceLink;
import org.vast.ogc.gml.GeoJsonBindings;
import org.vast.ogc.gml.IFeature;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.google.gson.stream.MalformedJsonException;


/**
 * <p>
 * Base class for all GeoJSON feature formatter.
 * </p>
 * 
 * @param <V> Feature type
 * @param <DB> Database type
 *
 * @author Alex Robin
 * @since Jan 26, 2021
 */
public abstract class AbstractFeatureBindingGeoJson<V extends IFeature, DB extends IDatabase> extends ResourceBindingJson<FeatureKey, V>
{
    protected final DB db;
    protected GeoJsonBindings geoJsonBindings;
    protected boolean showLinks;
    
    
    public AbstractFeatureBindingGeoJson(RequestContext ctx, IdEncoders idEncoders, DB db, boolean forReading) throws IOException
    {
        super(ctx, idEncoders, forReading);
        this.db = db;
        this.geoJsonBindings = getJsonBindings();
    }
    
    
    /*
     * To be implemented by subclasses to wrap feature to assign internal ID
     */
    protected abstract V getFeatureWithId(FeatureKey k, V f);


    @Override
    @SuppressWarnings("unchecked")
    public V deserialize(JsonReader reader) throws IOException
    {
        // if array, prepare to parse first element
        if (reader.peek() == JsonToken.BEGIN_ARRAY)
            reader.beginArray();
        
        if (reader.peek() == JsonToken.END_DOCUMENT || !reader.hasNext())
            return null;
        
        try
        {
            return (V)geoJsonBindings.readFeature(reader);
        }
        catch(MalformedJsonException | IllegalStateException e)
        {
            throw new ResourceParseException(e.getMessage());
        }
    }


    @Override
    public void serialize(FeatureKey key, V res, boolean showLinks, JsonWriter writer) throws IOException
    {
        try
        {
            var f = key != null ? getFeatureWithId(key, res) : res;
            this.showLinks = showLinks;
            geoJsonBindings.writeFeature(writer, f);
            writer.flush();
        }
        catch (IOException e)
        {
            IOException wrappedEx = new IOException("Error writing feature JSON", e);
            throw new IllegalStateException(wrappedEx);
        }
    }


    @Override
    public void startCollection() throws IOException
    {
        if (ctx.getFormat().equals(ResourceFormat.GEOJSON))
        {
            writer.beginObject();
            writer.name("type").value("FeatureCollection");
            writer.name("features");
            writer.beginArray();
        }
        else
            startJsonCollection(writer);
    }


    @Override
    public void endCollection(Collection<ResourceLink> links) throws IOException
    {
        endJsonCollection(writer, links);
    }
    
    
    @Override
    protected JsonReader getJsonReader(InputStream is) throws IOException
    {
        JsonReader reader = new JsonReader(new InputStreamReader(is, StandardCharsets.UTF_8.name()));
        reader.setStrictness(Strictness.LENIENT);
        return reader;
    }
    
    
    protected GeoJsonBindings getJsonBindings()
    {
        return new GeoJsonBindings(true);
    }
}
